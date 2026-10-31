package app.glasstv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.LruCache;
import android.view.View;
import android.view.animation.PathInterpolator;

// Одна таблетка быстрой полосы: графитовое стекло «visionOS» с запечённой мягкой тенью снизу, линейный значок
// и подпись. В теме «Объём» стекло светлое: числа обеих тем в Theme. Стекло лежит в двух готовых картинках на размер: основа с тенью и тонкий слой «подсветки» поверх неё
// (белая вуаль и яркая кромка). В кадре это две картинки, значок и текст, ни одного градиента и ни одной тени,
// которые считались бы заново. В покое перерисовок нет совсем; при смене фокуса 220 мс роста 1,0 → 1,08 и просветления
final class PillView extends View {
    // Мягкое торможение без перелёта, как доезд ряда на главном экране
    static final PathInterpolator EASE = new PathInterpolator(0.25f, 0.46f, 0.3f, 1f);
    // Размеры в dp (экран 960x540 dp, плотность 2)
    static final float HEIGHT = 64, PAD_X = 26, ICON = 26, GAP = 12, TEXT_SP = 19, GROW = 1.08f;
    static final long FOCUS_MS = 220;
    // Запас картинки под тень, dp: по бокам, сверху, снизу. Тень уходит вниз, вверх и в стороны почти нет
    static final float SH_X = 14, SH_TOP = 6, SH_BOTTOM = 22;
    static final int ICON_ALPHA = 0xE6;   // значок белый на 90 %, в фокусе чистый белый
    static final int MOON = 0, SOUND = 1, SUN = 2, WAVE = 3, GRID = 4;

    // Стекло на размер: основа с тенью и слой подсветки. Несколько таблеток на разные ширины, картинки живут
    // между открытиями полосы, чтобы второй раз их не рисовать
    private static final LruCache<String, Bitmap[]> GLASS = new LruCache<>(8);

    private final float dp;
    private final String label;
    private final Path icon;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int w, h;
    private Bitmap[] glass;
    private float focus;   // 0..1: плавно, от него зависят рост, светлота стекла и яркость подписи
    private ValueAnimator anim;

    PillView(Context c, int kind, String label) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        this.label = label;
        this.icon = icon(kind);
        // Подпись как у капсул «Дома»: Inter Medium, межбуквенный как у подписи плиток
        text.setTypeface(Fonts.medium(TEXT_SP));
        text.setTextSize(TEXT_SP * dp);
        text.setLetterSpacing(-0.005f);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStrokeWidth(1.8f);   // в клетках значка 24x24: холст растягивается до 26 dp
        // Ширина по содержимому: поля, значок, зазор и подпись
        w = Math.round((2 * PAD_X + ICON + GAP) * dp + text.measureText(label));
        h = Math.round(HEIGHT * dp);
        setFocusable(false);
        setClickable(false);
        apply(0f);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) { setMeasuredDimension(w, h); }

    @Override public boolean hasOverlappingRendering() { return false; }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        if (nw <= 0 || nh <= 0) return;
        String key = nw + "x" + nh;
        glass = GLASS.get(key);
        if (glass == null) {
            glass = glass(nw, nh, dp);
            GLASS.put(key, glass);
        }
    }

    // Выбрана ли таблетка пультом. Без анимации: полоса открывается уже с подсвеченной
    void setLit(boolean on, boolean animated) {
        if (anim != null) anim.cancel();
        float to = on ? 1f : 0f;
        if (!animated) { apply(to); return; }
        anim = ValueAnimator.ofFloat(focus, to);
        anim.setDuration(M.t(FOCUS_MS));
        anim.setInterpolator(EASE);
        anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        anim.start();
    }

    private void apply(float f) {
        focus = f;
        float s = 1f + (GROW - 1f) * f;
        setScaleX(s);
        setScaleY(s);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (glass != null) {
            c.drawBitmap(glass[0], -Math.round(SH_X * dp), -Math.round(SH_TOP * dp), paint);
            if (focus > 0.001f) {
                paint.setAlpha(Math.round(255 * focus));
                c.drawBitmap(glass[1], 0, 0, paint);
                paint.setAlpha(255);
            }
        }
        // Значок слева, по центру высоты; рисуется в клетке 24x24 и растягивается до 26 dp
        ink.setColor((Math.round(ICON_ALPHA + (255 - ICON_ALPHA) * focus) << 24) | 0xFFFFFF);
        float s = ICON * dp / 24f;
        c.save();
        c.translate(PAD_X * dp, (h - ICON * dp) / 2f);
        c.scale(s, s);
        c.drawPath(icon, ink);
        c.restore();
        // Подпись: белая на 92 %, в фокусе чистая; базовая линия так, чтобы строка стояла по центру высоты
        int a0 = Fonts.WHITE_92 >>> 24;
        text.setColor((Math.round(a0 + (255 - a0) * focus) << 24) | 0xFFFFFF);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, (PAD_X + ICON + GAP) * dp, h / 2f - (fm.ascent + fm.descent) / 2f, text);
    }

    // ===== Стекло =====

    // [0] основа: тень снизу, непрозрачный графит, свечение по краю, блик, тонкая кромка;
    // [1] подсветка размером с таблетку: белая вуаль, свечение и яркая кромка. Поверх основы стекло светлеет
    private static Bitmap[] glass(int w, int h, float dp) {
        int px = Math.round(SH_X * dp), pt = Math.round(SH_TOP * dp), pb = Math.round(SH_BOTTOM * dp);
        float rad = h / 2f;
        RectF r = new RectF(0, 0, w, h);
        Path shape = new Path();
        shape.addRoundRect(r, rad, rad, Path.Direction.CW);

        Bitmap base = Bitmap.createBitmap(w + 2 * px, h + pt + pb, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(base);
        c.translate(px, pt);
        // Мягкая тень снизу и тонкая светлая нить торца. Под самим стеклом тень стираем: иначе она просвечивала бы
        for (int i = 0; i < Theme.PILL_SHADOW.length; i++) {
            float[] e = Theme.PILL_SHADOW[i];
            Tile.shadow(c, r, rad, e[0] * dp, e[1] * dp, e[2] * dp, e[3] * dp, Theme.PILL_SHADOW_COLOR[i]);
        }
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawPath(shape, clear);

        // Графит сверху вниз, непрозрачный: при 0xD8 сквозь таблетку читался стих главного экрана, при 0xF4 еле видны подписи (снимки на ТВ 27.10)
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, Theme.PILL_FILL, null, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        // «Объём»: поверх подложки белая вуаль светлого стекла
        if (Theme.PILL_VEIL != null) {
            p.setShader(new LinearGradient(0, 0, 0, h, Theme.PILL_VEIL, Theme.PILL_VEIL_POS, Shader.TileMode.CLAMP));
            c.drawPath(shape, p);
        }
        p.setShader(null);

        // Светлое свечение по краю изнутри («Графит»)
        if ((Theme.PILL_INSET >>> 24) != 0) {
            c.save();
            c.clipPath(shape);
            Tile.inset(c, r, rad, 0, 0, 11 * dp, -1 * dp, Theme.PILL_INSET);
            c.restore();
        }

        // Блик сверху слева, как у стекла капсул
        float cx = 0.14f * w, rx = 0.9f * w, ry = 0.9f * h;
        RadialGradient rg = new RadialGradient(cx, 0, rx, new int[]{Theme.PILL_SHINE, 0x00FFFFFF}, new float[]{0f, 0.6f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, 0);
        rg.setLocalMatrix(m);
        p.setShader(rg);
        c.drawPath(shape, p);
        p.setShader(null);

        // Тонкая кромка-градиент 160°
        float bw = 0.8f * dp;
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(bw);
        edge.setShader(Tile.css(160, w, h, Theme.PILL_EDGE, Theme.PILL_EDGE_POS));
        RectF er = new RectF(r);
        er.inset(bw / 2f, bw / 2f);
        c.drawRoundRect(er, rad - bw / 2f, rad - bw / 2f, edge);

        // Подсветка фокуса отдельным слоем: плотность основы не проседает на переходе, как было бы при смене картинок
        Bitmap lit = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(lit);
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setShader(new LinearGradient(0, 0, 0, h, Theme.PILL_LIT_VEIL, null, Shader.TileMode.CLAMP));
        lc.drawPath(shape, lp);
        if ((Theme.PILL_LIT_INSET >>> 24) != 0) {
            lc.save();
            lc.clipPath(shape);
            Tile.inset(lc, r, rad, 0, 0, 11 * dp, 0, Theme.PILL_LIT_INSET);
            lc.restore();
        }
        // Кромка в фокусе: числа кромки капсул «Дома» (ярче сверху слева, к середине гаснет)
        float rw = Theme.RING_W * dp;
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(rw);
        // «Графит»: градиент; «Объём»: сплошная белая рамка, её непрозрачность запечена в цвет
        if (Theme.RING != null) ring.setShader(Tile.css(150, w, h, Theme.RING, Theme.RING_POS));
        else ring.setColor((Theme.RING_ALPHA << 24) | 0xFFFFFF);
        RectF rr = new RectF(r);
        rr.inset(rw / 2f, rw / 2f);
        lc.drawRoundRect(rr, rad - rw / 2f, rad - rw / 2f, ring);
        return new Bitmap[]{base, lit};
    }

    // ===== Значки =====

    // Простые линейные значки в клетке 24x24 (линия 1,8, круглые концы), всё кодом: ни одной картинки в ресурсах
    private static Path icon(int kind) {
        Path p = new Path();
        switch (kind) {
            case MOON:   // луна: большая дуга снаружи и малая внутри
                p.moveTo(21f, 12.79f);
                arc(p, 21f, 12.79f, 9f, true, true, 11.21f, 3f);
                arc(p, 11.21f, 3f, 7f, false, false, 21f, 12.79f);
                p.close();
                break;
            case SOUND:   // динамик и две волны
                p.moveTo(11f, 5f);
                p.lineTo(6f, 9f);
                p.lineTo(2f, 9f);
                p.lineTo(2f, 15f);
                p.lineTo(6f, 15f);
                p.lineTo(11f, 19f);
                p.close();
                p.moveTo(15.54f, 8.46f);
                arc(p, 15.54f, 8.46f, 5f, false, true, 15.54f, 15.53f);
                p.moveTo(19.07f, 4.93f);
                arc(p, 19.07f, 4.93f, 10f, false, true, 19.07f, 19.07f);
                break;
            case SUN:   // солнце: круг и восемь лучей
                p.addCircle(12f, 12f, 5f, Path.Direction.CW);
                float[][] rays = {{12, 1, 12, 3}, {12, 21, 12, 23}, {1, 12, 3, 12}, {21, 12, 23, 12},
                        {4.22f, 4.22f, 5.64f, 5.64f}, {18.36f, 18.36f, 19.78f, 19.78f},
                        {4.22f, 19.78f, 5.64f, 18.36f}, {18.36f, 5.64f, 19.78f, 4.22f}};
                for (float[] l : rays) { p.moveTo(l[0], l[1]); p.lineTo(l[2], l[3]); }
                break;
            case WAVE: {   // пять столбиков разной высоты, как значок «Волны»
                float[] hh = {3.5f, 6.6f, 9f, 5.6f, 3f};
                for (int i = 0; i < 5; i++) {
                    float x = 3.6f + 4.2f * i;
                    p.moveTo(x, 12f - hh[i]);
                    p.lineTo(x, 12f + hh[i]);
                }
                break;
            }
            default:   // сетка из четырёх квадратиков
                p.addRoundRect(new RectF(3, 3, 10, 10), 1.8f, 1.8f, Path.Direction.CW);
                p.addRoundRect(new RectF(14, 3, 21, 10), 1.8f, 1.8f, Path.Direction.CW);
                p.addRoundRect(new RectF(14, 14, 21, 21), 1.8f, 1.8f, Path.Direction.CW);
                p.addRoundRect(new RectF(3, 14, 10, 21), 1.8f, 1.8f, Path.Direction.CW);
                break;
        }
        return p;
    }

    // Дуга как в SVG (команда A): от текущей точки (x0, y0) к (x, y) по окружности радиуса r.
    // large: дуга больше полукруга; sweep: идти по часовой стрелке. Нужна, чтобы значки брались из привычной SVG-записи
    private static void arc(Path p, float x0, float y0, float r, boolean large, boolean sweep, float x, float y) {
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double d2 = dx * dx + dy * dy;
        if (d2 > r * r) r = (float) Math.sqrt(d2);   // точки дальше диаметра: радиус подтягивается
        double k = Math.sqrt(Math.max(0.0, (r * r - d2) / d2)) * (large == sweep ? -1 : 1);
        double cx = k * dy + (x0 + x) / 2.0, cy = -k * dx + (y0 + y) / 2.0;
        double a0 = Math.atan2((dy - (-k * dx)) / r, (dx - k * dy) / r);
        double a1 = Math.atan2((-dy - (-k * dx)) / r, (-dx - k * dy) / r);
        double da = a1 - a0;
        if (sweep && da < 0) da += 2 * Math.PI;
        if (!sweep && da > 0) da -= 2 * Math.PI;
        p.arcTo((float) (cx - r), (float) (cy - r), (float) (cx + r), (float) (cy + r),
                (float) Math.toDegrees(a0), (float) Math.toDegrees(da), false);
    }
}
