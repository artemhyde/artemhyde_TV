package app.glasstv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Часы-стекло (Liquid Glass), один из трёх видов часов в «Оформлении» (10.11).
// История: 09.11 выбрано «Матовое», вечером того же дня стекло стало по-настоящему прозрачным (внутри цифр живое фото,
// при сжатии цвет не прыгает). Потом забраковано: «пиксельная лесенка по контуру». Причины были две: готовая картинка
// цифр рисовалась без сглаживания (краска null), а на плитках и во время сжатия она уменьшена до 0,8, и видеокарта брала
// ближайшую точку вместо смеси соседних; и тонкая кромка получалась порогом по размытой маске, её край был почти ступенчатым.
// Теперь: картинка всегда рисуется со сглаживанием (FILTER_BITMAP_FLAG), а свет по краю считается по точкам из гладкой
// «высоты» цифр: у каждого места края есть направление наружу, грань к свету сверху слева светится сильнее, снизу справа
// слабый ответный отсвет, внутри лёгкая вуаль и свечение у края. Ни одного порога: край стекла это край сглаженной буквы.
// Картинка от фона не зависит (сквозь цифры видно живое фото) и собирается фоновым потоком раз в минуту
final class GlassClock extends View {
    static final float SIZE_SP = 112;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    // Свет сверху слева (ось y вниз)
    private static final float LX = -0.55f, LY = -0.835f;

    // Готовый кадр часов: тень и стекло для одного и того же времени
    private static final class Frame {
        String text;
        int w, h, sx, sy;
        Bitmap shadow, glass;
    }

    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private int pad;
    private float capTop;
    private String text = "";
    private int w, h;
    private int gen;              // номер размера: кадр старого размера не показываем
    private volatile Frame cur;   // рисуется сейчас

    GlassClock(Context c, float sizePx) {
        super(c);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Fonts.light28 != null ? Fonts.light28 : android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL));
        // Цифры обычной ширины, без «tnum»: у табличной единицы Inter есть пятка, она выглядит как на печатной машинке.
        // Ширина часов от этого меняется раз в минуту, но они стоят по центру, и соседям это не мешает
        tp.setLetterSpacing(-0.01f);
        shadowPaint.setColor(Color.argb(56, 0, 0, 0));
        setSizePx(sizePx);
    }

    // Размер цифр в точках экрана: в «Vision» крупные, в «Графите» мельче. Новый размер: кадр собирается заново
    void setSizePx(float sizePx) {
        if (Math.abs(tp.getTextSize() - sizePx) < 0.5f && w > 0) return;
        tp.setTextSize(sizePx);
        Rect r = new Rect();
        tp.getTextBounds("0123456789", 0, 10, r);
        capTop = r.top;   // отрицательное: высота цифр над базовой линией
        pad = Math.round(sizePx * 0.16f);   // место под тень
        gen++;
        cur = null;
        String t = text;
        text = "";
        w = h = 0;
        if (!t.isEmpty()) setTime(t); else requestLayout();
    }

    // От верха вида до базовой линии цифр: по ней часы встают на ту же линию, что и прежние
    float baseFromTop() { return pad - capTop; }

    // Поле слева от цифр внутри вида (под тень): «Графит» ставит часы по левой линии экрана
    int padLeft() { return pad; }

    void setTime(String t) {
        if (t == null || t.equals(text)) return;
        text = t;
        final int nw = Math.round(tp.measureText(text)) + 2 * pad, nh = Math.round(-capTop) + 2 * pad;
        if (nw != w || nh != h) { w = nw; h = nh; requestLayout(); }
        final Paint p = new Paint(tp);   // краска для фонового потока: свою он не трогает
        final int g = gen, pd = pad;
        final float ct = capTop;
        WORK.execute(() -> {
            Frame f = build(t, nw, nh, p, pd, ct);
            post(() -> {
                if (!t.equals(text) || g != gen) return;   // пока собирали, время или размер сменились
                cur = f;
                invalidate();
            });
        });
    }

    @Override protected void onMeasure(int a, int b) { setMeasuredDimension(Math.max(1, w), Math.max(1, h)); }

    @Override protected void onDraw(Canvas c) {
        Frame f = cur;
        if (f == null || f.w != getWidth() || f.h != getHeight()) return;
        c.drawBitmap(f.shadow, f.sx, f.sy + tp.getTextSize() * 0.025f, shadowPaint);
        c.drawBitmap(f.glass, 0, 0, glassPaint);
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    // Маленькая готовая картинка стеклянных часов (миниатюра в «Оформлении»): тень и стекло вместе
    static Bitmap preview(String t, float sizePx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(Color.WHITE);
        p.setTypeface(Fonts.light28 != null ? Fonts.light28 : android.graphics.Typeface.DEFAULT);
        p.setLetterSpacing(-0.01f);
        p.setTextSize(sizePx);
        Rect r = new Rect();
        p.getTextBounds("0123456789", 0, 10, r);
        int pad = Math.round(sizePx * 0.16f);
        int W = Math.round(p.measureText(t)) + 2 * pad, H = Math.round(-r.top) + 2 * pad;
        if (W <= 2 * pad || H <= 2 * pad) return null;
        Frame f = build(t, W, H, p, pad, r.top);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
        sp.setColor(Color.argb(56, 0, 0, 0));
        c.drawBitmap(f.shadow, f.sx, f.sy + sizePx * 0.025f, sp);
        c.drawBitmap(f.glass, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return Tile.fixed(out);
    }

    // Тень и стекло по цифрам (поток WORK)
    private static Frame build(String t, int W, int H, Paint tp, int pad, float capTop) {
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

        int n = W * H;
        int[] px = new int[n];
        mask.getPixels(px, 0, W, 0, 0, W, H);
        mask.recycle();
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (px[i] >>> 24) / 255f;
        // Гладкая «высота» цифр для направления края и широкая для свечения изнутри
        int r1 = Math.max(1, Math.round(size * 0.012f)), r2 = Math.max(2, Math.round(size * 0.034f));
        float[] b = blur(a, W, H, r1), b2 = blur(a, W, H, r2);
        // У размытой ступени наибольший наклон около 0,4 / сигма; сигма трёх проходов окна радиуса r около r + 0,5
        float kEdge = 2.5f * (r1 + 0.5f);
        for (int y = 0; y < H; y++) {
            float top = 1f - y / (float) H;
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                float A = a[i];
                if (A < 0.004f) { px[i] = 0; continue; }
                float gx = (b[x + 1 < W ? i + 1 : i] - b[x > 0 ? i - 1 : i]) * 0.5f;
                float gy = (b[y + 1 < H ? i + W : i] - b[y > 0 ? i - W : i]) * 0.5f;
                float gl = (float) Math.sqrt(gx * gx + gy * gy);
                float edge = Math.min(1f, gl * kEdge);
                float lit = gl > 1e-6f ? (-gx * LX - gy * LY) / gl : 0f;   // направление наружу против света
                float spec = lit > 0 ? lit * lit : 0f, back = lit < 0 ? -lit * lit * lit : 0f;
                float rim = edge * (0.28f + 0.72f * spec + 0.45f * back);
                float glow = 1f - b2[i];
                float veil = 0.05f + 0.09f * top;
                float alpha = 1f - (1f - veil) * (1f - 0.30f * glow) * (1f - 0.88f * Math.min(1f, rim));
                int al = Math.round(255f * alpha * A);
                px[i] = (Math.min(255, al) << 24) | 0x00FFFFFF;
            }
        }
        Bitmap g = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        g.setPixels(px, 0, W, 0, 0, W, H);
        d.glass = Tile.fixed(g);
        return d;
    }

    // Почти гауссово размытие: три прохода усредняющего окна по строкам и столбцам
    private static float[] blur(float[] src, int w, int h, int r) {
        float[] a = src.clone(), t = new float[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            box(a, w, h, r, true, t);
            box(a, w, h, r, false, t);
        }
        return a;
    }

    private static void box(float[] a, int w, int h, int r, boolean rows, float[] out) {
        int len = rows ? w : h, lines = rows ? h : w;
        float inv = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            float sum = 0;
            for (int k = -r; k <= r; k++) sum += a[at(Math.max(0, Math.min(len - 1, k)), line, w, rows)];
            for (int i = 0; i < len; i++) {
                out[i] = sum * inv;
                sum += a[at(Math.min(len - 1, i + r + 1), line, w, rows)] - a[at(Math.max(0, i - r), line, w, rows)];
            }
            for (int i = 0; i < len; i++) a[at(i, line, w, rows)] = out[i];
        }
    }

    private static int at(int i, int line, int w, boolean rows) { return rows ? line * w + i : i * w + line; }
}
