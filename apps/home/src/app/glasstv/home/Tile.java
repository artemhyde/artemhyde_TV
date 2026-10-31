package app.glasstv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.io.InputStream;

// Плитка в стиле «Графит» (выбран 27.10 вместо светлого «Объёма»): тёмное дымчатое стекло без белой заливки,
// светлое свечение по краю изнутри, тонкая кромка-градиент (ярче сверху слева), снизу торец (светлая нить и тёмная
// грань) и мягкие тени. Под значком еле заметная подсветка его цвета. В фокусе плитка приподнимается и чуть
// разворачивается к зрителю, стекло светлеет, кромка ярче, подсветка сильнее; толстой белой рамки нет.
// Тема «Объём» (Config.THEME = "volume") даёт светлое матовое стекло: числа слоёв лежат в Theme, а логика здесь одна.
// Стекло обоих состояний (с тенями) и содержимое (значок, подпись) рисуются в картинки один раз,
// в кадре плитка это две готовые картинки и один градиент: на слабой графике ТВ так держатся 60 кадров
final class Tile extends View {
    static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    // Блик идёт ровно и мягко: разгон и торможение одинаковые, как свет, скользящий по стеклу
    static final PathInterpolator SHEEN_EASE = new PathInterpolator(0.45f, 0f, 0.25f, 1f);
    // Макет нарисован для плитки 400x224 точек экрана; размеры ниже в этих точках, k переводит их в настоящие
    static final float W0 = 400f;
    // Запас картинки стекла вокруг плитки, отдельно для покоя и фокуса: в покое тени только вниз и чуть в стороны,
    // в фокусе свечение во все стороны и тень длиннее. Чем меньше пустых полей, тем меньше работы видеоядру
    // 27.10: края подрезаны там, где тень «Графита» уже слабее 2–3 % (было 64/84 и 128/186): меньше пустого смешивания.
    // Числа лежат в Theme: у «Объёма» тени длиннее, и поля прежние
    static final float[] PAD_X = Theme.TILE_PAD_X, PAD_TOP = Theme.TILE_PAD_TOP, PAD_BOTTOM = Theme.TILE_PAD_BOTTOM;
    static final float TILT_X = 6f, TILT_Y = -9f;
    private static final LruCache<String, Bitmap[]> CHROME = new LruCache<>(2);
    // Полоса блика готовой картинкой, одна на размер плитки: в кадре это простое перемещение картинки с прямоугольной
    // обрезкой. Градиент с поворотом, пересчитываемый каждый кадр, на этом видеоядре поднимал задержку кадра до 41 мс
    private static final LruCache<String, Bitmap> SHEEN = new LruCache<>(2);
    private Bitmap sheenBmp;

    final String pkg, label;
    private Bitmap content;                // значок, подпись и подсветка в покое, одной картинкой
    private final Rect box = new Rect();   // где содержимое лежит на плитке (в точках картинки 400x224)
    private final Bitmap raw;              // значок и подпись без подсветки
    private final Rect rawBox = new Rect();
    private int fullH = 1;
    private Bitmap[] chrome;
    private final float grow;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private float focus;   // 0..1, плавно: от него зависит, какое стекло видно
    private boolean quiet;
    private ValueAnimator anim;

    // Подсветка цвета значка изнутри (в макете .glow): эллипс с центром над значком, к краю в ноль.
    // В ряду 280x190 и 16 % (у бесцветного значка 11 %), в сетке 240x170 и 15 %; в фокусе ярче
    private final int tint;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Бегущий блик (27.10): когда плитка получает фокус,
    // по стеклу один раз наискосок проходит мягкая светлая полоса. Это один градиент поверх плитки, только у той,
    // что сейчас в фокусе, и только пока полоса идёт. Видеоядру он обошёлся в запас, освобождённый рядом:
    // подсветка покоя запечена в содержимое, поля теней подрезаны, у капсул сняты невидимые мягкие тени
    // Блик идёт, когда фокус остановился на плитке: при быстром листании он не запускается и не нагружает видеоядро
    static final long SHEEN_MS = 950, SHEEN_DELAY_MS = 280;
    private final Paint sheen = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float sheenW, sweep = -1f;
    private ValueAnimator sheenAnim;
    private float glowRx = 120, glowRy = 85, glowRest = 0.15f, glowFocus = 0.24f;
    private float glowK = -1;

    Tile(Context c, String pkg, String label, Bitmap content, float grow) {
        super(c);
        this.pkg = pkg;
        this.label = label;
        this.raw = crop(content, rawBox);
        this.fullH = content == null ? 1 : content.getHeight();
        this.tint = Theme.TILE_GLOW ? tint(this.raw) : 0xFFDDDDDD;
        this.grow = grow;
        compose();
        setFocusable(true);
        setClickable(true);
        // Тень своя, нарисованная: системная просвечивала бы сквозь стекло. Подъём над соседями только для порядка рисования
        setOutlineProvider(null);
        // Перспектива как в макете (1100 точек): камера View меряется в дюймах по 72 точки
        setCameraDistance(1100f * c.getResources().getDisplayMetrics().densityDpi / 72f);
        setOnFocusChangeListener((v, f) -> focus(f));
    }

    // Плитка ряда главного экрана: подсветка шире, у серого значка слабее (как в макете)
    Tile row() {
        if (!Theme.TILE_GLOW) return this;   // у «Объёма» подсветки цвета значка нет
        float[] hsv = new float[3];
        Color.colorToHSV(tint, hsv);
        glowRx = 140;
        glowRy = 95;
        glowRest = hsv[1] < 0.25f ? 0.11f : 0.16f;
        glowFocus = glowRest * 1.5f;
        glowK = -1;
        compose();
        return this;
    }

    // Подсветка в покое запекается в картинку содержимого (27.10): отдельным слоем она проходила по каждой плитке
    // в каждом кадре, а видеоядро ТВ на пределе. Отдельно рисуется только прибавка в фокусе, у одной плитки
    private void compose() {
        if (raw == null) { content = null; return; }
        if (!Theme.TILE_GLOW) { content = raw; box.set(rawBox); return; }   // без подсветки содержимое как есть
        float kc = full / W0;
        Bitmap b = Bitmap.createBitmap(full, fullH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = 200 * kc, cy = 87 * kc, rx = glowRx * kc, ry = glowRy * kc;
        RadialGradient g = new RadialGradient(cx, cy, rx, new int[]{(Math.round(255 * glowRest) << 24) | (tint & 0xFFFFFF), tint & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, cy);
        g.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(g);
        c.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, p);
        c.drawBitmap(raw, rawBox.left, rawBox.top, null);
        int keepFull = full;
        content = crop(b, box);
        full = keepFull;
    }

    private void glowShader(float k) {
        if (k == glowK) return;
        glowK = k;
        float cx = 200 * k, cy = 87 * k, rx = glowRx * k, ry = glowRy * k;
        RadialGradient g = new RadialGradient(cx, cy, rx, new int[]{tint | 0xFF000000, tint & 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, cy);
        g.setLocalMatrix(m);
        glow.setShader(g);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        sheenW = 0.5f * w;
        sheenBmp = SHEEN.get(w + "x" + h);
        if (sheenBmp == null) {
            sheenBmp = sheenBand(w, h, sheenW);
            SHEEN.put(w + "x" + h, sheenBmp);
        }
        String key = w + "x" + h;
        chrome = CHROME.get(key);
        if (chrome == null) {
            chrome = new Bitmap[]{glass(w, h, false), glass(w, h, true)};
            CHROME.put(key, chrome);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (chrome != null) {
            float k = getWidth() / W0, f = quiet ? focus * 0.35f : focus;
            if (f < 0.999f) {
                paint.setAlpha(Math.round(255 * (1f - f)));
                c.drawBitmap(chrome[0], -Math.round(PAD_X[0] * k), -Math.round(PAD_TOP[0] * k), paint);
            }
            if (f > 0.001f) {
                paint.setAlpha(Math.round(255 * f));
                c.drawBitmap(chrome[1], -Math.round(PAD_X[1] * k), -Math.round(PAD_TOP[1] * k), paint);
            }
            paint.setAlpha(255);
            if (Theme.TILE_GLOW) {
                glowShader(k);
                int a = Math.round(255 * (glowFocus - glowRest) * f);   // подсветка покоя уже в картинке содержимого
                if (a > 0) {
                    glow.setAlpha(a);
                    c.drawRect((200 - glowRx) * k, (87 - glowRy) * k, (200 + glowRx) * k, (87 + glowRy) * k, glow);
                }
            }
        }
        if (content != null) {
            float s = getWidth() / (float) full;
            dst.set(Math.round(box.left * s), Math.round(box.top * s), Math.round(box.right * s), Math.round(box.bottom * s));
            c.drawBitmap(content, null, dst, paint);
            // Подпись в фокусе не светлеет до белого, как в макете: второй слой содержимого для этого стоил видеоядру
            // столько, что кадр снова копился до 41 мс (замер 27.10). Разница видна только вблизи
        }
        if (sweep > 0f && sweep < 1f && sheenBmp != null && !quiet) {
            // Полоса едет слева направо: входит из-за левого края в первом кадре и уходит за правый в последнем
            int w = getWidth(), bw = sheenBmp.getWidth();
            float x = -bw + (w + bw) * sweep;
            c.save();
            c.clipRect(0, 0, w, getHeight());
            c.drawBitmap(sheenBmp, x, 0, sheen);
            c.restore();
        }
    }

    private int full = 1;   // ширина исходной картинки содержимого

    // Пустые прозрачные поля вокруг значка и подписи отрезаем: их смешивание тоже стоит видеоядру
    private Bitmap crop(Bitmap b, Rect out) {
        if (b == null) return null;
        full = b.getWidth();
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, btm = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if ((px[y * w + x] >>> 24) < 3) continue;
            if (x < l) l = x;
            if (x > r) r = x;
            if (y < t) t = y;
            if (y > btm) btm = y;
        }
        if (r < 0) { out.set(0, 0, w, h); return b; }
        out.set(l, t, r + 1, btm + 1);
        return Bitmap.createBitmap(b, l, t, out.width(), out.height());
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    // Окно потеряло фокус (поверх открыта карточка): рамка и свечение приглушены
    void setQuiet(boolean q) {
        if (quiet == q) return;
        quiet = q;
        invalidate();
    }

    private void focus(boolean f) {
        float k = getWidth() > 0 ? getWidth() / W0 : 1f;
        animate().scaleX(f ? grow : 1f).scaleY(f ? grow : 1f).translationY(f ? -6 * k : 0)
                .rotationX(f ? TILT_X : 0).rotationY(f ? TILT_Y : 0).translationZ(f ? 1 : 0)
                .setStartDelay(0).setDuration(M.t(f ? 420 : 320)).setInterpolator(EASE).start();
        if (sheenAnim != null) sheenAnim.cancel();
        sweep = -1f;
        if (f) {
            sheenAnim = ValueAnimator.ofFloat(0f, 1f);
            sheenAnim.setStartDelay(M.t(SHEEN_DELAY_MS));
            sheenAnim.setDuration(M.t(SHEEN_MS));
            sheenAnim.setInterpolator(SHEEN_EASE);
            sheenAnim.addUpdateListener(a -> { sweep = (float) a.getAnimatedValue(); invalidate(); });
            sheenAnim.start();
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focus, f ? 1f : 0f);
        anim.setDuration(M.t(f ? 380 : 300));
        anim.setInterpolator(EASE);
        anim.addUpdateListener(a -> { focus = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    // ===== Содержимое =====

    // Значок и подпись плиток ряда: готовые картинки из assets/tiles (собирает Home/tiles-src/build_tiles.py);
    // у светлой темы своя папка assets/tiles-volume, потому что у неё другие тени и яркость подписи
    static Bitmap asset(Context c, String key) {
        try (InputStream in = c.getAssets().open(Theme.TILES_DIR + "/" + key + ".png")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    // Любое приложение без своей картинки: значок в скруглённом квадрате с бликом и подпись. Подсветку его цвета
    // рисует сама плитка (она ярче в фокусе); у светлой темы плитка её не рисует, и подсветка запечена сюда.
    // Новые приложения сами получают такую плитку, рисовать для них ничего не нужно
    static Bitmap app(Context ctx, Drawable icon, String label, int w, int h) {
        float k = w / W0;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (Theme.APP_GLOW > 0f) {
            // Свечение цвета значка: эллипс 240x170 с центром над значком
            int tint = tint(icon);
            float gx = 200 * k, gy = 87 * k, grx = 120 * k, gry = 85 * k;
            RadialGradient glow = new RadialGradient(gx, gy, grx, new int[]{withAlpha(tint, Theme.APP_GLOW), withAlpha(tint, 0f)}, null, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1f, gry / grx, gx, gy);
            glow.setLocalMatrix(m);
            p.setShader(glow);
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        // Значок 88x88, скругление 21, тень под ним (на тёмном стекле «Графита» тени гуще)
        RectF ic = new RectF(156 * k, 34 * k, 244 * k, 122 * k);
        float r = 21 * k;
        shadow(c, ic, r, 0, 10 * k, 16 * k, -2 * k, Theme.APP_ICON_SHADOW[0]);
        shadow(c, ic, r, 0, 3 * k, 5 * k, 0, Theme.APP_ICON_SHADOW[1]);
        shadow(c, ic, r, 0, 0, 0, 0.5f * k, Theme.APP_ICON_SHADOW[2]);
        Path shape = new Path();
        shape.addRoundRect(ic, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(shape);
        p.setColor(0xFF222222);
        c.drawRect(ic, p);
        if (icon instanceof AdaptiveIconDrawable) {
            // У адаптивного значка видна середина слоя (72 из 108): слой растягиваем так, чтобы она заняла весь квадрат
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            float s = ic.width() * 108f / 72f, o = (s - ic.width()) / 2f;
            Rect lb = new Rect(Math.round(ic.left - o), Math.round(ic.top - o), Math.round(ic.left - o + s), Math.round(ic.top - o + s));
            for (Drawable d : new Drawable[]{ad.getBackground(), ad.getForeground()}) {
                if (d == null) continue;
                d.setBounds(lb);
                d.draw(c);
            }
        } else if (icon != null) {
            // Обычный значок чуть крупнее квадрата, чтобы его собственные поля не светились по краю
            float s = ic.width() * 1.045f, o = (s - ic.width()) / 2f;
            icon.setBounds(Math.round(ic.left - o), Math.round(ic.top - o), Math.round(ic.left - o + s), Math.round(ic.top - o + s));
            icon.draw(c);
        }
        // Блик стекла на значке: светлее сверху, чуть темнее снизу
        p.setShader(new LinearGradient(0, ic.top, 0, ic.bottom, Theme.APP_ICON_SHEEN,
                new float[]{0f, 0.42f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(ic, p);
        p.setShader(null);
        // Светлая полоска по верхнему краю внутри
        Path top = new Path(shape);
        Path lower = new Path();
        RectF sh = new RectF(ic);
        sh.offset(0, 1.5f * k);
        lower.addRoundRect(sh, r, r, Path.Direction.CW);
        top.op(lower, Path.Op.DIFFERENCE);
        p.setColor(Theme.APP_ICON_TOP);
        c.drawPath(top, p);
        c.restore();
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, k));
        rim.setColor(Theme.APP_ICON_RIM);
        RectF in = new RectF(ic);
        in.inset(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f);
        c.drawRoundRect(in, r, r, rim);

        // Подпись Inter Medium 32, по центру строки 146..190, с мягкой тенью
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Fonts.med18);
        t.setTextSize(32 * k);
        t.setLetterSpacing(-0.005f);
        // Текст рисуется дважды (две тени): у «Графита» 0,69 за проход дают в сумме 0,90 белого, как в макете (было почти 1,0)
        t.setColor(Theme.APP_LABEL);
        t.setTextAlign(Paint.Align.CENTER);
        CharSequence s = TextUtils.ellipsize(label, t, w - 40 * k, TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = t.getFontMetrics();
        float base = 168 * k - (fm.ascent + fm.descent) / 2f;
        t.setShadowLayer(cssBlur(14 * k), 0, 4 * k, Theme.APP_LABEL_SHADOW[0]);
        c.drawText(s, 0, s.length(), w / 2f, base, t);
        t.setShadowLayer(cssBlur(1 * k), 0, 1 * k, Theme.APP_LABEL_SHADOW[1]);
        c.drawText(s, 0, s.length(), w / 2f, base, t);
        return b;
    }

    // Цвет подсветки по уже готовому содержимому плитки (значок и подпись): белая подпись почти не весит,
    // решают насыщенные точки значка
    private static int tint(Bitmap content) {
        if (content == null) return 0xFFDDDDDD;
        return tint(new android.graphics.drawable.BitmapDrawable(null, content));
    }

    // Цвет значка для свечения: самые насыщенные и яркие точки, по ним средний цвет
    private static int tint(Drawable icon) {
        if (icon == null) return 0xFFDDDDDD;
        Bitmap s = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(s);
        icon.setBounds(0, 0, 24, 24);
        icon.draw(c);
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, sum = 0;
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
            int px = s.getPixel(x, y);
            if (Color.alpha(px) < 128) continue;
            Color.colorToHSV(px, hsv);
            double wgt = hsv[1] * hsv[2] + 0.02;
            r += Color.red(px) * wgt; g += Color.green(px) * wgt; b += Color.blue(px) * wgt; sum += wgt;
        }
        if (sum < 0.5) return 0xFFDDDDDD;
        int avg = Color.rgb((int) (r / sum), (int) (g / sum), (int) (b / sum));
        Color.colorToHSV(avg, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.2f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int c, float a) { return (Math.round(255 * a) << 24) | (c & 0xFFFFFF); }

    // ===== Стекло =====

    // Одно состояние стекла с тенями, в картинке с запасом PAD_* вокруг плитки. Слои и числа повторяют макет
    // «Графит» (демо/варианты-плиток/тёмные/graphite/graphite-home.html, правила .tile и .tile.focus): тени снаружи,
    // торец снизу, графитовая заливка, светлое свечение по краю изнутри, кромка-градиент, блик сверху слева
    static Bitmap glass(int w, int h, boolean f) {
        float k = w / W0;
        int s = f ? 1 : 0;
        int px = Math.round(PAD_X[s] * k), pt = Math.round(PAD_TOP[s] * k), pb = Math.round(PAD_BOTTOM[s] * k);
        Bitmap b = Bitmap.createBitmap(w + 2 * px, h + pt + pb, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(px, pt);
        RectF card = new RectF(0, 0, w, h);
        float r = 40 * k;
        Path cardPath = new Path();
        cardPath.addRoundRect(card, r, r, Path.Direction.CW);

        // Тени снаружи: в CSS первая в списке лежит сверху, поэтому рисуем с конца (в Theme они уже в этом порядке).
        // «Графит»: две последние без размытия это торец, тёмная грань и над ней тонкая светлая нить
        float[][] sh = Theme.TILE_SHADOW[s];
        int[] shc = Theme.TILE_SHADOW_COLOR[s];
        for (int i = 0; i < sh.length; i++) shadow(c, card, r, sh[i][0] * k, sh[i][1] * k, sh[i][2] * k, sh[i][3] * k, shc[i]);
        // Под самим стеклом тени нет (как box-shadow в браузере): иначе она просвечивала бы сквозь него
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawPath(cardPath, clear);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Тёплый оттенок под заливкой (только «Объём»)
        if (Theme.TILE_TINT != null) {
            p.setShader(css(165, w, h, Theme.TILE_TINT[s], null));
            c.drawPath(cardPath, p);
        }
        // Заливка сверху вниз. «Графит»: дымчатый графит, полупрозрачный (сквозь него чуть видно фон), белого нет.
        // «Объём»: белая вуаль
        p.setShader(new LinearGradient(0, 0, 0, h, Theme.TILE_FILL[s], Theme.TILE_FILL_POS[s], Shader.TileMode.CLAMP));
        c.drawPath(cardPath, p);
        p.setShader(null);

        // Свечение изнутри (inset-тени). «Графит»: по всему краю и сильнее у нижнего края; в фокусе ещё светлая полоска
        // по верху и тонкая светлая кайма. «Объём»: одно свечение у нижнего края
        c.save();
        c.clipPath(cardPath);
        float[][] in = Theme.TILE_INSET[s];
        int[] inc = Theme.TILE_INSET_COLOR[s];
        for (int i = 0; i < in.length; i++) inset(c, card, r, in[i][0] * k, in[i][1] * k, in[i][2] * k, in[i][3] * k, inc[i]);
        c.restore();

        // Кромка-градиент. «Графит»: в покое 1,6 точки, в фокусе 2,2 и ярче. «Объём»: в фокусе белая рамка 4 точки
        float bw = Theme.TILE_RIM_W[s] * k;
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(bw);
        rim.setShader(css(150, w, h, Theme.TILE_RIM[s], Theme.TILE_RIM_POS[s]));
        RectF rr = new RectF(card);
        rr.inset(bw / 2f, bw / 2f);
        c.drawRoundRect(rr, r - bw / 2f, r - bw / 2f, rim);

        // Блик сверху слева («Графит»: эллипс 110 % на 58 %, слабый) и еле заметный диагональный отсвет
        float cx = Theme.TILE_TOP[0] * w, rx = Theme.TILE_TOP[1] * w, ry = Theme.TILE_TOP[2] * h;
        RadialGradient top = new RadialGradient(cx, 0, rx, new int[]{Theme.TILE_TOP_COLOR[s], 0x00FFFFFF}, new float[]{0f, 0.62f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, 0);
        top.setLocalMatrix(m);
        p.setShader(top);
        c.drawPath(cardPath, p);
        p.setShader(css(106, w, h, new int[]{0x00FFFFFF, Theme.TILE_DIAG[s], 0x00FFFFFF}, Theme.TILE_DIAG_POS[s]));
        c.drawPath(cardPath, p);
        return b;
    }

    // Картинка полосы блика: светлая полоса шириной в половину плитки, наклонена на 18° («/»), к краям в ноль,
    // в середине 30 % белого. Сверху и снизу полоса гаснет, чтобы у скруглённых углов не светилось за краем стекла
    static Bitmap sheenBand(int w, int h, float band) {
        float slant = h * 0.3249f;                       // tg 18°
        int bw = Math.round(band * 1.0515f + slant);     // ширина полосы вдоль строки (1 / cos 18°) плюс сдвиг наклона
        Bitmap b = Bitmap.createBitmap(Math.max(1, bw), h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient g = new LinearGradient(0, 0, band, 0, new int[]{0x00FFFFFF, Theme.TILE_SHEEN, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setRotate(18f);
        m.postTranslate(slant, 0);
        g.setLocalMatrix(m);
        p.setShader(g);
        c.drawRect(0, 0, bw, h, p);
        Paint fade = new Paint();
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, h, fade);
        return b;
    }

    // inset box-shadow из CSS внутри уже обрезанного по плитке холста: светится всё вокруг «дыры», а дыра это
    // плитка, сжатая на spread (минус расширяет) и сдвинутая на dx, dy
    static void inset(Canvas c, RectF card, float r, float dx, float dy, float blur, float spread, int color) {
        RectF hole = new RectF(card);
        hole.inset(spread, spread);
        hole.offset(dx, dy);
        float hr = Math.max(0, r - spread);
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addRect(card.left - card.width(), card.top - card.height(), card.right + card.width(), card.bottom + card.height(), Path.Direction.CW);
        ring.addRoundRect(hole, hr, hr, Path.Direction.CW);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (blur > 0) p.setMaskFilter(new BlurMaskFilter(cssBlur(blur), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(ring, p);
    }

    // box-shadow из CSS: смещение, размытие, растяжение (минус сжимает)
    static void shadow(Canvas c, RectF box, float r, float dx, float dy, float blur, float spread, int color) {
        RectF s = new RectF(box);
        s.inset(-spread, -spread);
        s.offset(dx, dy);
        float rr = Math.max(0, r + spread);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (blur > 0) p.setMaskFilter(new BlurMaskFilter(cssBlur(blur), BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(s, rr, rr, p);
    }

    // Размытие CSS (сигма = половина) в радиус Android (сигма = 0,577 радиуса + 0,5)
    static float cssBlur(float blur) { return Math.max(0.5f, (blur / 2f - 0.5f) / 0.57735f); }

    // linear-gradient(угол) из CSS для прямоугольника w x h
    static LinearGradient css(float angle, float w, float h, int[] colors, float[] pos) {
        double a = Math.toRadians(angle);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float len = (float) (Math.abs(w * Math.sin(a)) + Math.abs(h * Math.cos(a)));
        float cx = w / 2f, cy = h / 2f;
        return new LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f, colors, pos, Shader.TileMode.CLAMP);
    }
}
