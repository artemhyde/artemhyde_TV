package app.glasstv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Часы-стекло в стиле «Vision». 09.11 из трёх проб выбрано «Матовое» (как Liquid Glass на айфонах), вечером того же дня:
// прозрачнее, в стиле плашек приложений, без скачка цвета при сжатии, тоньше и меньше.
// Поэтому стекло теперь по-настоящему прозрачное: внутри цифр ничего не подставляется, сквозь них видно живое фото под ними
// (прежнее тело было снимком фона, и при сжатии он не совпадал с новым местом, отсюда скачок цвета). Видны только свет
// и кромки, как у шаров ряда (Tile.glassShape): лёгкая белая вуаль, свечение по краю изнутри (у низа сильнее), яркая грань
// сверху слева, отблеск снизу справа, мягкая тень под цифрами. Шрифт тонкий, Inter Light, как у прежних часов «Графита».
// Картинка цифр от фона не зависит: собирается фоновым потоком только при смене времени
final class GlassClock extends View {
    // Выключатель стекла. В выпуск 1.1.0 стекло не входит: по контуру цифр видна лесенка, доработка в следующем обновлении.
    // В «Vision» пока прежние тонкие часы текстом. В личной сборке стекло включено, это единственное отличие файлов,
    // кроме имени пакета
    static final boolean ON = false;
    static final float SIZE_SP = 112;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    // Готовый кадр часов: тень и стекло для одного и того же времени
    private static final class Frame {
        String text;
        int w, h, sx, sy;
        Bitmap shadow, glass;
    }

    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int pad;
    private final float capTop;
    private String text = "";
    private int w, h;
    private volatile Frame cur;   // рисуется сейчас

    GlassClock(Context c, float sizePx) {
        super(c);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Fonts.light28 != null ? Fonts.light28 : android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL));
        // Цифры обычной ширины, без «tnum»: у табличной единицы Inter есть пятка, она выглядит как на печатной машинке.
        // Ширина часов от этого меняется раз в минуту, но они стоят по центру, и соседям это не мешает
        tp.setLetterSpacing(-0.01f);
        tp.setTextSize(sizePx);
        Rect r = new Rect();
        tp.getTextBounds("0123456789", 0, 10, r);
        capTop = r.top;   // отрицательное: высота цифр над базовой линией
        pad = Math.round(sizePx * 0.16f);   // место под тень
        shadowPaint.setColor(Color.argb(64, 0, 0, 0));
    }

    // От верха вида до базовой линии цифр: по ней часы встают на ту же линию, что и прежние
    float baseFromTop() { return pad - capTop; }

    void setTime(String t) {
        if (t == null || t.equals(text)) return;
        text = t;
        final int nw = Math.round(tp.measureText(text)) + 2 * pad, nh = Math.round(-capTop) + 2 * pad;
        if (nw != w || nh != h) { w = nw; h = nh; requestLayout(); }
        WORK.execute(() -> {
            Frame f = build(t, nw, nh);
            post(() -> {
                if (!t.equals(text)) return;   // пока собирали, время сменилось: новый кадр уже в пути
                cur = f;
                invalidate();
            });
        });
    }

    @Override protected void onMeasure(int a, int b) { setMeasuredDimension(Math.max(1, w), Math.max(1, h)); }

    @Override protected void onDraw(Canvas c) {
        Frame f = cur;
        if (f == null || f.w != getWidth() || f.h != getHeight()) return;
        c.drawBitmap(f.shadow, f.sx, f.sy + tp.getTextSize() * 0.02f, shadowPaint);
        c.drawBitmap(f.glass, 0, 0, null);
    }

    // Тень и стекло по цифрам (поток WORK)
    private Frame build(String t, int W, int H) {
        Frame d = new Frame();
        d.text = t; d.w = W; d.h = H;
        float size = tp.getTextSize();
        Bitmap mask = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        new Canvas(mask).drawText(t, pad, pad - capTop, tp);

        Paint bp = new Paint();
        bp.setMaskFilter(new BlurMaskFilter(Math.max(2f, size * 0.05f), BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        d.shadow = mask.extractAlpha(bp, off);
        d.sx = off[0];
        d.sy = off[1];

        float top = pad, bottom = pad - capTop;
        float s = Math.max(1f, size * 0.008f);
        d.glass = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(d.glass);
        // Вуаль: чуть светлее того, что под цифрами, сверху светлее (блик); сквозь неё видно живое фото
        paintThrough(gc, mask, new LinearGradient(0, top, 0, bottom,
                new int[]{0x33FFFFFF, 0x17FFFFFF, 0x14FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP), W, H);
        // Свечение по краю изнутри: мягкое, к середине штриха гаснет, у низа сильнее
        paintThrough(gc, glow(mask, size * 0.025f), new LinearGradient(0, top, 0, bottom,
                new int[]{0x40FFFFFF, 0x26FFFFFF, 0x59FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP), W, H);
        // Тонкая кромка по всему краю: держит форму цифр на светлом фото
        paintThrough(gc, band(mask, Math.max(1f, size * 0.006f)), new LinearGradient(0, top, 0, bottom,
                0x66FFFFFF, 0x47FFFFFF, Shader.TileMode.CLAMP), W, H);
        // Свет сверху слева: яркая грань, к низу цифры гаснет
        paintThrough(gc, rim(mask, s, s), new LinearGradient(0, top, 0, bottom,
                new int[]{0xF2FFFFFF, 0x8CFFFFFF, 0x33FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP), W, H);
        // Отблеск снизу справа, слабее света
        paintThrough(gc, rim(mask, -s * 0.8f, -s * 0.8f), new LinearGradient(0, bottom, 0, top,
                0xA6FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP), W, H);
        return d;
    }

    // Залить градиентом там, где есть форма (форма полноцветная: у ALPHA_8 пустые места режим DST_IN не трогает)
    private static void paintThrough(Canvas dst, Bitmap shape, Shader sh, int W, int H) {
        Bitmap t = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(t);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setShader(sh);
        tc.drawRect(0, 0, W, H, q);
        q = new Paint();
        q.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        tc.drawBitmap(shape, 0, 0, q);
        dst.drawBitmap(t, 0, 0, null);
    }

    // Край со стороны сдвига: маска без самой себя, сдвинутой на (dx, dy)
    private static Bitmap rim(Bitmap mask, float dx, float dy) {
        Bitmap r = mask.copy(Bitmap.Config.ARGB_8888, true);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        q.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        new Canvas(r).drawBitmap(mask, dx, dy, q);
        return r;
    }

    // Свечение по краю изнутри: маска, из которой вычтена её размытая копия; у края ярче, к середине штриха в ноль
    private static Bitmap glow(Bitmap mask, float e) {
        Paint q = new Paint();
        q.setMaskFilter(new BlurMaskFilter(Math.max(1f, e), BlurMaskFilter.Blur.NORMAL));
        int[] o = new int[2];
        Bitmap bl = mask.extractAlpha(q, o);
        Bitmap r = mask.copy(Bitmap.Config.ARGB_8888, true);
        Paint d = new Paint(Paint.FILTER_BITMAP_FLAG);
        d.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        new Canvas(r).drawBitmap(bl, o[0], o[1], d);
        return r;
    }

    // Полоса вдоль всего края внутри цифр шириной около e: маска без её «сжатой» середины
    private static Bitmap band(Bitmap mask, float e) {
        Paint q = new Paint();
        q.setMaskFilter(new BlurMaskFilter(Math.max(1f, e), BlurMaskFilter.Blur.NORMAL));
        int[] o = new int[2];
        Bitmap bl = mask.extractAlpha(q, o);
        Bitmap inner = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
        Paint t = new Paint();
        t.setColor(Color.WHITE);
        float k = 6f;   // порог: середина там, где размытая маска почти целая
        t.setColorFilter(new ColorMatrixColorFilter(new float[]{0, 0, 0, 0, 255, 0, 0, 0, 0, 255, 0, 0, 0, 0, 255, 0, 0, 0, k, -0.82f * k * 255}));
        new Canvas(inner).drawBitmap(bl, o[0], o[1], t);
        Bitmap r = mask.copy(Bitmap.Config.ARGB_8888, true);
        Paint d = new Paint();
        d.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        new Canvas(r).drawBitmap(inner, 0, 0, d);
        return r;
    }
}
