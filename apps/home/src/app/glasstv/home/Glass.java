package app.glasstv.home;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

// Подложка капсулы в стиле «Графит», как у плиток (макет .cap в демо/варианты-плиток/тёмные/graphite):
// тёмное дымчатое стекло сверху вниз, светлое свечение по краю изнутри, блик сверху слева, кромка-градиент,
// снизу тонкая светлая нить. В теме «Объём» вместо этого светлая вуаль, зеркальная кромка и тонкий торец из двух серпов
// (числа обеих тем в Theme). Живого размытия нет: стекло лежит на уже размытом фоне.
// Всё рисуется в картинку один раз на размер и состояние. Мягкие тени макета сняты 27.10: при капсулах на 45 %
// их не видно, а лишние поля картинки видеоядро смешивало в каждом кадре
final class Glass extends Drawable {
    private float radius;
    private final float stroke, k;
    private boolean lit;

    Glass(float radiusPx, float density) {
        this.radius = radiusPx;
        this.k = density / 2f;   // числа макета даны в точках экрана 1920x1080, где dp = 2
        this.stroke = Math.max(1f, 1.4f * k);
    }

    // Скругление меняется вместе со стилем: у «Vision» капсулы ниже (05.11)
    void radius(float r) {
        if (radius == r) return;
        radius = r;
        cache = null;
        cacheLit = null;
        invalidateSelf();
    }

    // В фокусе стекло светлее и кромка ярче
    void setLit(boolean on) {
        if (lit == on) return;
        lit = on;
        invalidateSelf();
    }

    // Запас картинки только под торец стекла (светлая нить «Графита», два серпа «Объёма»)
    private int side() { return 0; }
    private int below() { return Math.round(Theme.GLASS_BELOW * k) + 1; }

    // Сложные фигуры кромки и свечения видеоядро ТВ считало бы заново в каждом кадре: две капсулы
    // в прошлом стоили 14 мс кадра. Поэтому картинка, в кадре одна операция
    // 05.11: обе картинки (покой и фокус) готовятся сразу и дальше не меняются. Раньше картинка перерисовывалась при
    // каждом переходе фокуса прямо в кадре (18 мс работы и пропущенный кадр на каждом шаге по капсулам).
    // Готовая картинка неизменяемая: такую видеоядро держит у себя и не грузит заново
    private android.graphics.Bitmap cache, cacheLit;
    private int cw, ch;
    private final Paint fade = new Paint(Paint.FILTER_BITMAP_FLAG);

    private android.graphics.Bitmap make(int w, int h, int sx, boolean on) {
        android.graphics.Bitmap m = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(m);
        cc.translate(sx, 0);
        boolean keep = lit;
        lit = on;
        paint(cc, w - 2 * sx, h - below());
        lit = keep;
        return Tile.fixed(m);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        int sx = side(), by = below();
        int w = b.width() + 2 * sx, h = b.height() + by;
        if (cache == null || cw != w || ch != h) {
            cw = w;
            ch = h;
            cache = make(w, h, sx, false);
            cacheLit = make(w, h, sx, true);
        }
        c.drawBitmap(lit ? cacheLit : cache, b.left - sx, b.top, fade);
    }

    private void paint(Canvas c, float w, float h) {
        RectF r = new RectF(0, 0, w, h);
        float rad = Math.min(radius, h / 2f);
        Path shape = new Path();
        shape.addRoundRect(r, rad, rad, Path.Direction.CW);

        // Торец под нижним краем: «Графит» светлая нить, «Объём» два тонких серпа
        int st = lit ? 1 : 0;
        float[] slab = Theme.GLASS_SLAB[st];
        for (int i = 0; i < slab.length; i++) Tile.shadow(c, r, rad, 0, slab[i] * k, 0, 0, Theme.GLASS_SLAB_COLOR[st][i]);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        c.drawPath(shape, clear);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Тёплая подложка под заливкой (только «Объём»)
        if ((Theme.GLASS_BASE >>> 24) != 0) {
            p.setColor(Theme.GLASS_BASE);
            c.drawPath(shape, p);
        }
        // Заливка сверху вниз: у «Графита» графит, у «Объёма» белая вуаль
        p.setShader(new LinearGradient(0, 0, 0, h, Theme.GLASS_FILL[st], Theme.GLASS_FILL_POS, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        p.setShader(null);

        // Светлое свечение по краю изнутри (inset 0 0 22px -2px), только «Графит»
        if ((Theme.GLASS_INSET[st] >>> 24) != 0) {
            c.save();
            c.clipPath(shape);
            Tile.inset(c, r, rad, 0, 0, 22 * k, -2 * k, Theme.GLASS_INSET[st]);
            c.restore();
        }

        // Блик сверху слева (эллипс 90 % на 90 % у 14 % ширины)
        float cx = 0.14f * w, rx = 0.9f * w, ry = 0.9f * h;
        RadialGradient rg = new RadialGradient(cx, 0, rx, new int[]{Theme.GLASS_SHINE[st], 0x00FFFFFF}, new float[]{0f, 0.6f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, 0);
        rg.setLocalMatrix(m);
        p.setShader(rg);
        c.drawPath(shape, p);

        // Кромка-градиент 160°
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
        edge.setShader(Tile.css(160, w, h, Theme.GLASS_EDGE[st], Theme.GLASS_EDGE_POS));
        float s = stroke / 2f;
        r.inset(s, s);
        c.drawRoundRect(r, rad - s, rad - s, edge);
    }

    @Override public void setAlpha(int a) {
        if (fade.getAlpha() == a) return;
        fade.setAlpha(a);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
