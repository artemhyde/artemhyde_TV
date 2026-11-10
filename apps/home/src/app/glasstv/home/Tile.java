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
// Тема «Объём» (выбор на ТВ, ThemeChoice) даёт светлое матовое стекло: числа слоёв лежат в Theme, а логика здесь одна.
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
        setContentDescription(label);   // подпись для озвучки экрана и проверок (uiautomator видит, на какой плитке фокус)
        // Тень своя, нарисованная: системная просвечивала бы сквозь стекло. Подъём над соседями только для порядка рисования
        setOutlineProvider(null);
        // Перспектива как в макете (1100 точек): камера View меряется в дюймах по 72 точки
        setCameraDistance(1100f * c.getResources().getDisplayMetrics().densityDpi / 72f);
        setOnFocusChangeListener((v, f) -> { if (fx == null || !fx.take(this, f)) focus(f); });
    }

    // Фокус плиток «Все приложения» ведёт «Дом» (HomeActivity, «Двойники фокуса»): сетка лежит готовым слоем видеокарты,
    // а движение фокуса рисует двойник плитки поверх слоя. take отвечает true, если взял фокус на себя
    interface FocusFx { boolean take(Tile t, boolean focused); }
    static FocusFx fx;

    // Двойник плитки сетки: то же содержимое, стекло и подсветка, но не ловит фокус и не нажимается
    Tile(Tile o) {
        super(o.getContext());
        pkg = o.pkg;
        label = o.label;
        raw = o.raw;
        rawBox.set(o.rawBox);
        fullH = o.fullH;
        full = o.full;
        tint = o.tint;
        grow = o.grow;
        glowRx = o.glowRx;
        glowRy = o.glowRy;
        glowRest = o.glowRest;
        glowFocus = o.glowFocus;
        content = o.content;
        box.set(o.box);
        badge = o.badge;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setOutlineProvider(null);
        setCameraDistance(o.getCameraDistance());
        // Двойник встаёт ровно в то состояние, в каком была плитка: её уход мог ещё не доиграть
        focus = o.focus;
        setScaleX(o.getScaleX());
        setScaleY(o.getScaleY());
        setTranslationY(o.getTranslationY());
        setTranslationZ(o.getTranslationZ());
        setRotationX(o.getRotationX());
        setRotationY(o.getRotationY());
    }

    boolean isLit() { return lit; }

    // Плитка в покое: рост и стекло фокуса доиграли уход
    boolean resting() { return focus < 0.01f && Math.abs(getScaleX() - 1f) < 0.002f; }

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

    // ===== Сферы стиля «Vision» (ноябрь) =====
    // Та же плитка, но контур круглый: стекло тем же рецептом (glassShape с радиусом в половину стороны), значок внутри
    // шара, подпись под ним. В покое сфера это одна готовая картинка (стекло, значок, подпись): кадр на ТВ всегда
    // рисуется целиком, а видеоядро слабое.
    // Движение (05.11, второй заход: «не живое, не дышит»; числа сведены из разборов Apple TV, Google TV и консолей):
    // - фокус: рост до 1,17 на пружине с заметным перелётом (затухание 0,46: пик около 1,20) и подъём на 8 dp. Прошлый
    //   перелёт был на 1,5 точки, глаз его не видел;
    // - стекло фокуса и цветной ореол цвета значка зажигаются вместе с ростом и гаснут с уходом, ореол вспыхивает
    //   при посадке и оседает (постоянное свечение выглядело дёшево);
    // - зажатая стрелка: спокойный режим (1,12 без перелёта, без блика и ореола), на отпускании полная посадка с толчком;
    // - OK: сфера проседает на 7 % и отпружинивает, приложение открывается после отскока;
    // - дыхание заметное (±2,2 % за 3,6 с), начинается после посадки и через 25 с тишины затихает: экран становится
    //   неподвижным и ничего не тратит
    static final float ORB_GROW = Style.GROW;
    static boolean holding;    // стрелка зажата (повторы клавиши): спокойный режим, ставит HomeActivity
    private boolean orb;
    private int orbD;          // диаметр шара в точках экрана; шар стоит вверху ячейки, по центру
    private float orbK;        // масштаб чисел стекла (тени, кромка) для шара
    private float orbDp = 1f;
    private Bitmap orbArt;
    private int orbExt;        // подпись шире ячейки на столько с каждой стороны: длинные названия заходят в зазор
    private RadialGradient orbGlow;
    private Bitmap orbRest;    // сфера в покое одной картинкой
    private int restX, restY;
    private Spring spring;     // масштаб сферы
    private Spring push;       // сдвиг вбок: соседи сферы в фокусе расступаются
    private float lift;        // подъём в фокусе, точки (рисуется сдвигом холста: свойство подъёма ведёт каскад)
    private float halo;        // яркость ореола 0..1 (вспышка при посадке, дыхание)
    private float breath;      // текущая добавка дыхания к ореолу
    private long breathAt;
    private ValueAnimator haloAnim;
    private static final LruCache<String, Bitmap> HALO = new LruCache<>(2);
    private Bitmap haloBmp;
    private final Paint haloPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final LruCache<String, Bitmap> ORB_SHEEN = new LruCache<>(2);
    private Bitmap orbSheen;
    private final Paint sheenCircle = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private android.graphics.BitmapShader sheenShader;
    private final Matrix sheenM = new Matrix();

    // Свой значок для шара 160x160 со значком 128 посередине, как картинки assets/orbs (папка: четыре значка)
    private Bitmap ownIcon;

    // Материал Liquid Glass (10.11, Liquid.java): у шара прозрачное стекло с размытыми обоями внутри и белый знак
    private boolean liquid;
    private Drawable liquidIcon;
    private Liquid.FrostPaint frostP;
    private final Matrix frostM = new Matrix(), frostT = new Matrix(), frostLast = new Matrix();
    private int frostVer = -1;

    Tile liquid(Drawable icon) { liquid = true; liquidIcon = icon; return this; }

    boolean isLiquidOrb() { return liquid && orb; }

    // Перед кадром «Дом» спрашивает: сдвинулся ли шар относительно обоев (карусель, пружина, каскад)? Тогда перерисовать,
    // чтобы матовое стекло показывало то, что лежит за шаром сейчас. Стоит на месте: ничего не делаем
    void frostCheck(View root) {
        if (!liquid || !orb || Liquid.frost == null) return;
        if (!Liquid.toRoot(this, root, frostT)) return;
        frostT.preTranslate(0, -lift);
        if (frostVer != Liquid.version || !frostT.equals(frostLast)) invalidate();
    }

    Tile icon(Bitmap b) { ownIcon = b; return this; }

    Tile orb(int d, int cellW, int cellH, float dp) {
        orb = true;
        orbD = d;
        orbDp = dp;
        orbK = d / (W0 * 0.5f);
        orbExt = Math.round(8 * dp);
        orbArt = orbArt(cellW, cellH, dp);
        // Ореол цвета значка; у бесцветного значка холодный белый
        float[] hsv = new float[3];
        Color.colorToHSV(tint, hsv);
        int col = hsv[1] < 0.25f ? 0xFFDCE6FF : tint;
        haloPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(col | 0xFF000000, PorterDuff.Mode.SRC_IN));
        return this;
    }

    private Bitmap orbArt(int cw, int ch, float dp) {
        Bitmap b = Bitmap.createBitmap(cw + 2 * orbExt, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = cw / 2f + orbExt, cy = orbD / 2f;
        float[] hsv = new float[3];
        Color.colorToHSV(tint, hsv);
        boolean grey = hsv[1] < 0.25f;
        if (Theme.TILE_GLOW) {
            // Подсветка цвета значка изнутри шара, у бесцветного значка слабее
            float a = grey ? 0.12f : 0.20f;
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setShader(new RadialGradient(cx, cy - orbD * 0.04f, orbD * 0.46f, new int[]{withAlpha(tint, a), tint & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, orbD * 0.47f, g);
        }
        Bitmap own = ownIcon != null ? ownIcon : liquid ? null : orbIcon(getContext(), pkg);
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (liquid && own == null) {
            // Liquid Glass: белый знак (свой для плиток HYDE, у чужого приложения силуэт значка) с лёгкой тенью
            int ms = Math.round(orbD * 0.40f);
            Bitmap mk = Liquid.mark(pkg, liquidIcon, label, ms);
            ip.setShadowLayer(cssBlur(10 * dp / 2f), 0, dp, 0x59000000);
            if (mk != null) c.drawBitmap(mk, cx - ms / 2f, cy - ms / 2f, ip);
            ip.clearShadowLayer();
        } else if (own != null) {
            // Свой значок 160x160 (значок 128 по центру, вокруг тень): значок занимает 0,46 диаметра
            float s = orbD * 0.46f / 128f;
            float iw = own.getWidth() * s, ih = own.getHeight() * s;
            c.drawBitmap(own, null, new RectF(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f), ip);
        } else if (raw != null) {
            // Чужое приложение: значок из картинки плитки (подпись в ней ниже 0,6 высоты)
            int cut = Math.max(1, Math.min(raw.getHeight(), Math.round(fullH * 0.6f) - rawBox.top));
            Bitmap icon = trim(Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), cut));
            float nat = Grid.TILE_W * dp / full * 1.25f, box = orbD * 0.5f;
            float s = Math.min(nat, Math.min(box / icon.getWidth(), box / icon.getHeight()));
            float iw = icon.getWidth() * s, ih = icon.getHeight() * s;
            c.drawBitmap(icon, null, new RectF(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f), ip);
        }
        // Подпись под шаром: Inter Medium 16 dp, белая на 86 %, с мягкой тенью, как подписи плиток
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(Fonts.med18);
        tp.setTextSize(16f * dp);
        tp.setColor(0xDBFFFFFF);
        tp.setTextAlign(Paint.Align.CENTER);
        CharSequence s = TextUtils.ellipsize(label, tp, cw + 2 * orbExt - 2 * dp, TextUtils.TruncateAt.END);
        float base = orbD + 27 * dp;
        tp.setShadowLayer(cssBlur(12 * dp / 2f), 0, 2 * dp, 0x80000000);
        c.drawText(s, 0, s.length(), cx, base, tp);
        return Tile.fixed(b);
    }

    // Свой крупный значок сферы (assets/orbs, собирает Home/tiles-src/build_orbs.py); нет: null
    static Bitmap orbIcon(Context c, String key) {
        try (InputStream in = c.getAssets().open("orbs/" + key + ".png")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Папка (10.11) =====

    // Четыре значка папки сеткой 2x2 в картинке 160x160 (блок 128 посередине, как у картинок assets/orbs)
    static Bitmap folderIcon(java.util.List<android.graphics.drawable.Drawable> icons) {
        Bitmap b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int s = 58, gap = 12, x0 = (160 - 2 * s - gap) / 2;
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x59000000);
        sh.setMaskFilter(new BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL));
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 4; i++) {
            float x = x0 + (i % 2) * (s + gap), y = x0 + (i / 2) * (s + gap);
            if (i < icons.size()) {
                c.drawRoundRect(new RectF(x, y + 3, x + s, y + s + 3), s * 0.26f, s * 0.26f, sh);
                c.drawBitmap(Sheet.appIcon(icons.get(i), s * 2, false), null, new RectF(x, y, x + s, y + s), ip);
            } else {
                Paint e = new Paint(Paint.ANTI_ALIAS_FLAG);
                e.setColor(0x1FFFFFFF);
                c.drawRoundRect(new RectF(x, y, x + s, y + s), s * 0.26f, s * 0.26f, e);
            }
        }
        return Tile.fixed(b);
    }

    // Плитка-папка «Графита»: четыре значка над подписью, как у плитки приложения (Tile.app)
    static Bitmap folderContent(Context ctx, java.util.List<android.graphics.drawable.Drawable> icons, String label, int w, int h) {
        float k = w / W0;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Bitmap ic = folderIcon(icons);
        float side = 112 * k;
        c.drawBitmap(ic, null, new RectF(200 * k - side / 2f, 82 * k - side / 2f, 200 * k + side / 2f, 82 * k + side / 2f),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Fonts.med18);
        t.setTextSize(32 * k);
        t.setLetterSpacing(-0.005f);
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

    // Обрезать прозрачные поля, не трогая размеров основной картинки
    private static Bitmap trim(Bitmap b) {
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
        return r < 0 ? b : Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1);
    }

    // Сфера в покое одной картинкой: стекло покоя и сверху значок с подписью
    private void buildRest() {
        if (chrome == null || orbArt == null || getWidth() == 0) return;
        int ox = (getWidth() - orbD) / 2;
        int gx = ox - Math.round(PAD_X[0] * orbK), gy = -Math.round(PAD_TOP[0] * orbK);
        int ax = -orbExt, ay = 0;
        int l = Math.min(gx, ax), tp = Math.min(gy, ay);
        int r = Math.max(gx + chrome[0].getWidth(), ax + orbArt.getWidth()), btm = Math.max(gy + chrome[0].getHeight(), ay + orbArt.getHeight());
        Bitmap b = Bitmap.createBitmap(r - l, btm - tp, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawBitmap(chrome[0], gx - l, gy - tp, null);
        c.drawBitmap(orbArt, ax - l, ay - tp, null);
        orbRest = Tile.fixed(b);
        restX = l;
        restY = tp;
    }

    // Ореол: белая маска (цвет даёт фильтр краски), пусто внутри шара, ярче всего у кромки, к 1,5 радиуса в ноль.
    // Одна картинка на размер, общая для всех сфер
    private static Bitmap haloArt(int d) {
        float r = d / 2f, outer = r * 1.5f;
        int s = Math.round(outer * 2);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, outer, new int[]{0x00FFFFFF, 0x00FFFFFF, 0xFFFFFFFF, 0x59FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, r / outer, (r * 1.18f) / outer, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(s / 2f, s / 2f, outer, p);
        return Tile.fixed(b);
    }

    private float focusOf(float scale) { return Math.max(0f, Math.min(1f, (scale - 1f) / (ORB_GROW - 1f))); }

    private void drawOrb(Canvas c) {
        float f = quiet ? focus * 0.35f : focus;
        int ox = (getWidth() - orbD) / 2;
        if (lift != 0f) c.translate(0, -lift);
        if (f < 0.001f && orbRest != null) {
            c.drawBitmap(orbRest, restX, restY, paint);
            return;
        }
        if (liquid && Liquid.frost != null && getRootView() != null) {
            // Матовое стекло: размытые обои кругом, совмещённые с экраном (Liquid.frostPaint)
            if (frostP == null) frostP = new Liquid.FrostPaint();
            View rootV = frostRoot();
            if (rootV != null && Liquid.frostPaint(this, rootV, frostP, frostM, frostT, lift)) {
                c.drawCircle(ox + orbD / 2f, orbD / 2f, orbD / 2f - 0.5f, frostP);
                Liquid.toRoot(this, rootV, frostLast);
                frostLast.preTranslate(0, -lift);
                frostVer = Liquid.version;
            }
        }
        float ha = Math.min(1f, (halo + breath) * f) * (quiet ? 0.35f : 1f);
        if (haloBmp != null && ha > 0.004f) {
            // Ореол под стеклом: снаружи шара светится цветом значка, внутри его закрывает стекло
            haloPaint.setAlpha(Math.round(255 * ha));
            c.drawBitmap(haloBmp, ox + orbD / 2f - haloBmp.getWidth() / 2f, orbD / 2f - haloBmp.getHeight() / 2f, haloPaint);
        }
        if (chrome != null) {
            if (f < 0.999f) {
                paint.setAlpha(Math.round(255 * (1f - f)));
                c.drawBitmap(chrome[0], ox - Math.round(PAD_X[0] * orbK), -Math.round(PAD_TOP[0] * orbK), paint);
            }
            if (f > 0.001f) {
                paint.setAlpha(Math.round(255 * f));
                c.drawBitmap(chrome[1], ox - Math.round(PAD_X[1] * orbK), -Math.round(PAD_TOP[1] * orbK), paint);
            }
            paint.setAlpha(255);
        }
        if (Theme.TILE_GLOW && f > 0.001f) {
            // В фокусе шар чуть наполняется цветом своего значка: одна прибавка, только у шара в фокусе
            if (orbGlow == null) orbGlow = new RadialGradient(getWidth() / 2f, orbD / 2f, orbD * 0.5f,
                    new int[]{tint | 0xFF000000, tint & 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            glow.setShader(orbGlow);
            glow.setAlpha(Math.round(255 * 0.12f * f));
            c.drawCircle(getWidth() / 2f, orbD / 2f, orbD * 0.5f, glow);
        }
        if (orbArt != null) c.drawBitmap(orbArt, -orbExt, 0, paint);
        if (sweep > 0f && sweep < 1f && orbSheen != null && sheenShader != null && !quiet) {
            // Блик: светлая косая полоса один раз проходит по шару слева направо; рисуется кругом с картинкой полосы
            // внутри, без обрезки по контуру
            float bw = orbSheen.getWidth();
            sheenM.setTranslate(ox - bw + (orbD + bw) * sweep, 0);
            sheenShader.setLocalMatrix(sheenM);
            c.drawCircle(ox + orbD / 2f, orbD / 2f, orbD / 2f - 1f, sheenCircle);
        }
        drawChevrons(c, getWidth() / 2f, orbD / 2f, orbD / 2f);
    }

    // Корень главного экрана, относительно которого лежат обои (его задаёт «Дом»)
    static View frostRootView;

    private View frostRoot() { return frostRootView; }

    // ===== Пружина, ореол, дыхание и блик сферы =====

    private void ensureSprings() {
        if (spring != null) return;
        spring = new Spring(getScaleX(), v -> {
            setScaleX(v);
            setScaleY(v);
            float p = (v - 1f) / (ORB_GROW - 1f);
            float l = p * 8f * orbDp;   // подъём идёт вместе с ростом и тоже перелетает
            float fo = focusOf(v);
            if (Math.abs(l - lift) > 0.05f || Math.abs(fo - focus) > 0.002f) {
                lift = l;
                focus = fo;
                invalidate();
            }
        });
        spring.eps = 0.0015f;
        push = new Spring(0f, this::setTranslationX).tune(0.85f, 0.38f);
        push.eps = 0.5f;
    }

    static boolean calmFocus;   // фокус без посадки (вход в «Оформление»): шар сразу в своём размере, без ореола и дыхания

    private void orbFocus(boolean f) {
        ensureSprings();
        if (f && calmFocus) {
            removeCallbacks(breathTick);
            removeCallbacks(landLater);
            setTranslationZ(1);
            spring.snap(ORB_GROW);
            return;
        }
        removeCallbacks(breathTick);
        removeCallbacks(landLater);
        if (breath != 0f) breath = 0f;
        spring.value = getScaleX();   // дыхание двигало масштаб мимо пружины: продолжаем с того, что на экране
        setTranslationZ(f ? 1 : 0);
        if (sheenAnim != null) sheenAnim.cancel();
        sweep = -1f;
        if (f) {
            if (holding) {
                // Стрелка зажата: сфера спокойно подрастает и отдаёт фокус дальше, без перелёта, блика и ореола
                spring.tune(0.85f, 0.22f);
                spring.onRest = null;
                setHalo(0f, 120);
                spring.to(1.12f);
            } else {
                land(0.35f);
            }
        } else {
            spring.tune(holding ? 0.85f : 0.78f, holding ? 0.22f : 0.30f);
            spring.onRest = null;
            setHalo(0f, 160);
            spring.to(1f);
        }
    }

    // Посадка: рост с перелётом, вспышка ореола, блик, потом дыхание. kick: толчок скорости (масштаб в секунду)
    void land(float kick) {
        if (!orb || !isFocused()) return;
        ensureSprings();
        removeCallbacks(breathTick);
        spring.value = getScaleX();
        spring.velocity += kick;
        spring.tune(0.46f, 0.40f);
        spring.onRest = () -> { if (isFocused()) { breathAt = 0; postDelayed(breathTick, 700); } };
        spring.to(ORB_GROW);
        // Ореол вспыхивает вместе с ростом и оседает до спокойного свечения
        if (haloAnim != null) haloAnim.cancel();
        haloAnim = ValueAnimator.ofFloat(halo, 0.55f, 0.24f);
        haloAnim.setDuration(M.t(900));
        haloAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        haloAnim.addUpdateListener(a -> { halo = (float) a.getAnimatedValue(); invalidate(); });
        haloAnim.start();
        if (orbSheen != null) {
            if (sheenAnim != null) sheenAnim.cancel();
            sheenAnim = ValueAnimator.ofFloat(0f, 1f);
            sheenAnim.setStartDelay(M.t(140));
            sheenAnim.setDuration(M.t(650));
            sheenAnim.setInterpolator(SHEEN_EASE);
            sheenAnim.addUpdateListener(a -> { sweep = (float) a.getAnimatedValue(); invalidate(); });
            sheenAnim.start();
        }
    }

    private final Runnable landLater = () -> land(0.8f);

    private void setHalo(float to, long ms) {
        if (haloAnim != null) haloAnim.cancel();
        if (halo == to) return;
        haloAnim = ValueAnimator.ofFloat(halo, to);
        haloAnim.setDuration(M.t(ms));
        haloAnim.addUpdateListener(a -> { halo = (float) a.getAnimatedValue(); invalidate(); });
        haloAnim.start();
    }

    // OK на сфере: нажали, сфера проседает; отпустили, отпружинивает с перелётом. Действие выполняет HomeActivity
    // после отскока, чтобы отклик был виден до смены экрана
    void press(boolean down) {
        if (!orb) return;
        ensureSprings();
        removeCallbacks(breathTick);
        breath = 0f;
        spring.value = getScaleX();
        spring.onRest = null;
        if (down) {
            spring.tune(0.75f, 0.12f);
            spring.to(ORB_GROW * 0.93f);
            setHalo(0.14f, 120);
        } else {
            spring.tune(0.40f, 0.30f);
            spring.to(ORB_GROW);
            setHalo(0.5f, 160);
        }
    }

    // Соседи расступаются: сдвиг вбок в точках (плюс вправо)
    void pushTo(float px) {
        if (!orb) return;
        ensureSprings();
        if (Math.abs(push.target - px) < 0.5f && !push.running()) return;
        push.value = getTranslationX();
        push.to(px);
    }

    // Стрелка упёрлась в край ряда: сфера на миг проседает (ряд толкает HomeActivity)
    void squeeze() {
        if (!orb) return;
        ensureSprings();
        spring.value = getScaleX();
        spring.velocity -= 1.1f;
        spring.tune(0.5f, 0.32f);
        spring.to(moving ? ORB_GROW * MOVE_GROW : isFocused() ? ORB_GROW : 1f);
    }

    // Отпустили зажатую стрелку: на последней сфере полная посадка
    void holdEnded() {
        if (!orb || !isFocused()) return;
        removeCallbacks(landLater);
        post(landLater);
    }

    // ===== Отметка «Новое» (06.11) =====
    // Плитка в «Все приложения» для приложения, поставленного за последние 3 дня и ещё не открытого с «Дома»:
    // маленькая мятная плашка в правом верхнем углу. Рисуется вместе с плиткой, отдельного слоя нет
    private String badge;
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.text.TextPaint badgeText;

    void badge(String s) {
        badge = s;
        invalidate();
    }

    private void drawBadge(Canvas c) {
        if (badge == null || orb) return;
        float d = getResources().getDisplayMetrics().density;
        if (badgeText == null) {
            badgeText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            badgeText.setTypeface(Fonts.med18);
            badgeText.setTextSize(12f * d);
            badgeText.setColor(0xFF0E1A16);
            badgeBg.setColor(Fonts.ACCENT);
        }
        float tw = badgeText.measureText(badge), ph = 7f * d, pv = 3.5f * d;
        float h = badgeText.getTextSize() + 2 * pv, w = tw + 2 * ph;
        float r = getWidth() - 12f * d, t = 12f * d;
        c.drawRoundRect(r - w, t, r, t + h, h / 2f, h / 2f, badgeBg);
        c.drawText(badge, r - w + ph, t + pv + badgeText.getTextSize() * 0.86f, badgeText);
    }

    // ===== Перестановка ряда (05.11) =====
    // Плитка «в руке»: крупнее обычного фокуса, по бокам светлые стрелки «‹ ›», дыхание и блик молчат.
    // Соседи при обмене местами доезжают на пружине от старого места (slide), рывка нет
    static final float MOVE_GROW = 1.08f;
    private boolean moving;
    private float moveA;   // 0..1: проявление стрелок
    private ValueAnimator moveAnim;
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path chevPath = new android.graphics.Path();

    void setMoving(boolean m) {
        if (moving == m) return;
        moving = m;
        if (moveAnim != null) moveAnim.cancel();
        moveAnim = ValueAnimator.ofFloat(moveA, m ? 1f : 0f);
        moveAnim.setDuration(M.t(m ? 260 : 200));
        moveAnim.setInterpolator(EASE);
        moveAnim.addUpdateListener(a -> { moveA = (float) a.getAnimatedValue(); invalidate(); });
        moveAnim.start();
        if (orb) {
            ensureSprings();
            removeCallbacks(breathTick);
            removeCallbacks(landLater);
            breath = 0f;
            spring.value = getScaleX();
            spring.tune(0.55f, 0.32f);
            spring.onRest = m ? null : () -> { if (isFocused()) { breathAt = 0; postDelayed(breathTick, 700); } };
            spring.to(m ? ORB_GROW * MOVE_GROW : ORB_GROW);
            setHalo(m ? 0.6f : 0.24f, 220);
        } else {
            boolean f = isFocused();
            float s = m ? grow * 1.06f : f ? grow : 1f;
            animate().scaleX(s).scaleY(s).rotationX(m || !f ? 0 : TILT_X).rotationY(m || !f ? 0 : TILT_Y)
                    .setStartDelay(0).setDuration(M.t(260)).setInterpolator(EASE).start();
        }
    }

    // Обмен местами: плитка уже стоит на новом месте, рисуем её со сдвига dx и отпускаем пружиной
    void slide(float dx) {
        ensureSprings();
        push.value = getTranslationX() + dx;
        setTranslationX(push.value);
        push.to(push.target);
    }

    private void drawChevrons(Canvas c, float cx, float cy, float half) {
        if (moveA <= 0.01f) return;
        float d = getResources().getDisplayMetrics().density;
        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(3f * d);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        chevron.setColor(Color.WHITE);
        chevron.setAlpha(Math.round(235 * moveA));
        chevron.setShadowLayer(4f * d, 0, 1f * d, 0x66000000);
        float gap = half + (10f + 8f * moveA) * d, w = 7f * d, hh = 12f * d;
        chevPath.reset();
        chevPath.moveTo(cx - gap + w, cy - hh);
        chevPath.lineTo(cx - gap, cy);
        chevPath.lineTo(cx - gap + w, cy + hh);
        chevPath.moveTo(cx + gap - w, cy - hh);
        chevPath.lineTo(cx + gap, cy);
        chevPath.lineTo(cx + gap - w, cy + hh);
        c.drawPath(chevPath, chevron);
    }

    // Дыхание сферы в фокусе: ±2,2 % за 3,6 с (прошлые 0,9 % глаз не видел) и вместе с ним ореол. Шаг 12,5 раза
    // в секунду: за шаг край шара сдвигается меньше чем на полточки, ступенек не видно, а видеоядру впятеро легче.
    // Через 25 с без нажатий дыхание за 0,8 с затихает, и экран неподвижен. Пока ряд скрыт, дыхание стоит
    static final float BREATH = 0.022f;
    static final long BREATH_PERIOD = 3600, BREATH_FOR = 25_000;
    private final Runnable breathTick = new Runnable() {
        @Override public void run() {
            if (!isFocused() || moving) return;
            if (quiet || !isShown() || getAlpha() < 0.99f || !hasWindowFocus()) {
                if (getScaleX() != ORB_GROW) { setScaleX(ORB_GROW); setScaleY(ORB_GROW); }
                breath = 0f;
                breathAt = 0;
                postDelayed(this, 500);
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (breathAt == 0) breathAt = now;
            float t = (now - breathAt) / 1000f;
            float amp = Math.min(1f, t / 0.6f);
            float end = BREATH_FOR / 1000f;
            if (t > end) amp *= Math.max(0f, 1f - (t - end) / 0.8f);
            float b = (float) Math.sin(2 * Math.PI * t * 1000f / BREATH_PERIOD) * amp;
            float s = ORB_GROW * (1f + BREATH * b);
            setScaleX(s);
            setScaleY(s);
            breath = 0.07f * b;
            invalidate();
            if (amp <= 0f && t > end) { breath = 0f; setScaleX(ORB_GROW); setScaleY(ORB_GROW); return; }
            postDelayed(this, 80);
        }
    };

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(breathTick);
        removeCallbacks(landLater);
        if (spring != null) spring.stop();
        if (push != null) push.stop();
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
        if (orb) {
            setPivotX(w / 2f);
            setPivotY(orbD / 2f);
            String key = "orb" + orbD + (liquid ? "L" : "");
            chrome = CHROME.get(key);
            if (chrome == null) {
                chrome = liquid ? new Bitmap[]{Liquid.orbGlass(orbD, false), Liquid.orbGlass(orbD, true)}
                        : new Bitmap[]{glassShape(orbD, orbD, orbK, orbD / 2f, false), glassShape(orbD, orbD, orbK, orbD / 2f, true)};
                CHROME.put(key, chrome);
            }
            buildRest();
            haloBmp = HALO.get("h" + orbD);
            if (haloBmp == null) { haloBmp = haloArt(orbD); HALO.put("h" + orbD, haloBmp); }
            orbSheen = ORB_SHEEN.get("s" + orbD);
            if (orbSheen == null) { orbSheen = sheenBand(orbD, orbD, orbD * 0.42f, false); ORB_SHEEN.put("s" + orbD, orbSheen); }
            sheenShader = new android.graphics.BitmapShader(orbSheen, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sheenCircle.setShader(sheenShader);
            return;
        }
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
        if (orb) { drawOrb(c); return; }
        drawChrome(c);
        if (content != null) {
            float s = getWidth() / (float) full;
            dst.set(Math.round(box.left * s), Math.round(box.top * s), Math.round(box.right * s), Math.round(box.bottom * s));
            c.drawBitmap(content, null, dst, paint);
            // Подпись в фокусе не светлеет до белого, как в макете: второй слой содержимого для этого стоил видеоядру
            // столько, что кадр снова копился до 41 мс (замер 27.10). Разница видна только вблизи
        }
        drawBadge(c);
        if (sweep > 0f && sweep < 1f && sheenBmp != null && !quiet) {
            // Полоса едет слева направо: входит из-за левого края в первом кадре и уходит за правый в последнем
            int w = getWidth(), bw = sheenBmp.getWidth();
            float x = -bw + (w + bw) * sweep;
            c.save();
            c.clipRect(0, 0, w, getHeight());
            c.drawBitmap(sheenBmp, x, 0, sheen);
            c.restore();
        }
        drawChevrons(c, getWidth() / 2f, getHeight() / 2f, getWidth() / 2f);
    }

    // Стекло покоя и фокуса с тенями и подсветка цвета значка, в точках плитки (стекло выходит за её края)
    void drawChrome(Canvas c) {
        if (chrome == null) return;
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
        if (r < 0) { out.set(0, 0, w, h); b = Tile.fixed(b); return b; }
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

    // Открыто меню плитки: фокус ушёл в меню, а плитка остаётся выбранной (крупной и светлой), чтобы было видно, чьё меню
    private boolean lit;

    void keepLit(boolean on) {
        if (lit == on) return;
        lit = on;
        if (!on && !isFocused() && (fx == null || !fx.take(this, false))) focus(false);
    }

    void focus(boolean f) {
        if (!f && lit) return;
        float k = getWidth() > 0 ? getWidth() / W0 : 1f;
        if (orb) {
            orbFocus(f);   // у сферы рост, стекло и ореол ведёт пружина
            return;
        } else {
            animate().scaleX(f ? grow : 1f).scaleY(f ? grow : 1f).translationY(f ? -6 * k : 0)
                    .rotationX(f ? TILT_X : 0).rotationY(f ? TILT_Y : 0).translationZ(f ? 1 : 0)
                    .setStartDelay(0).setDuration(M.t(f ? 420 : 320)).setInterpolator(EASE).start();
        }
        if (!orb && sheenAnim != null) sheenAnim.cancel();
        if (!orb) sweep = -1f;
        if (f && !orb) {
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

    // Готовая картинка неизменяемой копией (05.11). Изменяемую картинку видеоядро этого ТВ грузило заново на каждом
    // кадре: одно стекло капсулы стоило 10 мс из 16, и ряд при зажатой стрелке шёл рывками
    static Bitmap fixed(Bitmap m) {
        if (m == null || !m.isMutable()) return m;
        Bitmap f = m.copy(m.getConfig(), false);
        return f == null ? m : f;
    }

    private static int withAlpha(int c, float a) { return (Math.round(255 * a) << 24) | (c & 0xFFFFFF); }

    // ===== Стекло =====

    // Одно состояние стекла с тенями, в картинке с запасом PAD_* вокруг плитки. Слои и числа повторяют макет
    // «Графит» (демо/варианты-плиток/тёмные/graphite/graphite-home.html, правила .tile и .tile.focus): тени снаружи,
    // торец снизу, графитовая заливка, светлое свечение по краю изнутри, кромка-градиент, блик сверху слева
    static Bitmap glass(int w, int h, boolean f) { return glassShape(w, h, w / W0, 40 * (w / W0), f); }

    // Стекло скруглённого контура w x h с радиусом r: плитка (радиус 40 из 400) или шар (радиус в половину стороны).
    // k переводит числа теней и кромки из макета в точки экрана
    static Bitmap glassShape(int w, int h, float k, float r, boolean f) {
        int s = f ? 1 : 0;
        int px = Math.round(PAD_X[s] * k), pt = Math.round(PAD_TOP[s] * k), pb = Math.round(PAD_BOTTOM[s] * k);
        Bitmap b = Bitmap.createBitmap(w + 2 * px, h + pt + pb, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(px, pt);
        RectF card = new RectF(0, 0, w, h);
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
        return Tile.fixed(b);
    }

    // Картинка полосы блика: светлая полоса шириной в половину плитки, наклонена на 18° («/»), к краям в ноль,
    // в середине 30 % белого. Сверху и снизу полоса гаснет, чтобы у скруглённых углов не светилось за краем стекла
    static Bitmap sheenBand(int w, int h, float band) { return sheenBand(w, h, band, true); }

    // fadeEdges: гасить полосу сверху и снизу (плитка); у шара контур и так круглый
    static Bitmap sheenBand(int w, int h, float band, boolean fadeEdges) {
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
        if (!fadeEdges) { b = Tile.fixed(b); return b; }
        Paint fade = new Paint();
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, h, fade);
        return Tile.fixed(b);
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
