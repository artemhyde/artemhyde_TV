package app.glasstv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

// Размытие фото один раз, на уменьшенной копии: на слабой графике ТВ живое размытие дорого,
// а растянутая маленькая размытая картинка выглядит так же мягко и ничего не стоит в кадре
final class Blur {
    // Копия 240x135 с размытием; ImageView растянет её на весь экран со сглаживанием
    static Bitmap of(Bitmap src, int radius) { return of(src, radius, 1f); }

    // bright < 1: копия сразу темнее. Тогда активному экрану не нужен отдельный слой затемнения
    static Bitmap of(Bitmap src, int radius, float bright) {
        if (src == null) return null;
        int w = 240, h = Math.max(1, Math.round(240f * src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Насыщенность +20 %: размытый фон остаётся цветным, а не серым
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.25f);
        if (bright < 1f) {
            android.graphics.ColorMatrix dark = new android.graphics.ColorMatrix();
            dark.setScale(bright, bright, bright, 1f);
            cm.postConcat(dark);
        }
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        c.drawBitmap(src, null, new android.graphics.Rect(0, 0, w, h), p);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        // Три прохода усредняющего окна дают почти гауссово размытие
        for (int pass = 0; pass < 3; pass++) {
            box(px, w, h, radius, true);
            box(px, w, h, radius, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        if (bright < 1f) vignette(small);
        return Tile.fixed(small);
    }

    // Края темнее середины, как в макете «Графит» (27.10): овал 68 % на 78 % чуть выше центра, прозрачно до половины,
    // к краю до 60 %; сверху лёгкое затемнение под часами, снизу под капсулами. Глаз собирается на плитках.
    // В теме «Объём» виньетка проще: круг радиусом 62 % диагонали, к углам до 45 %, без затемнения сверху и снизу (числа в Theme)
    private static void vignette(Bitmap b) {
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float w = b.getWidth(), h = b.getHeight(), cx = w / 2f, cy = h * Theme.VIG_CY;
        float rx = Theme.VIG_OVAL ? 0.68f * w : (float) Math.hypot(w, h) * 0.62f, ry = Theme.VIG_OVAL ? 0.78f * h : rx;
        android.graphics.RadialGradient g = new android.graphics.RadialGradient(cx, cy, rx,
                Theme.VIG_COLORS, Theme.VIG_POS, android.graphics.Shader.TileMode.CLAMP);
        if (Theme.VIG_OVAL) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(1f, ry / rx, cx, cy);
            g.setLocalMatrix(m);
        }
        p.setShader(g);
        c.drawRect(0, 0, w, h, p);
        if (Theme.SCRIM_COLORS != null) {
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, Theme.SCRIM_COLORS,
                    Theme.SCRIM_POS, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
    }

    private static void box(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] out = new int[len];
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            int win = r * 2 + 1;
            for (int i = -r; i <= r; i++) {
                int c = px[idx(Math.max(0, Math.min(len - 1, i)), line, w, horizontal)];
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[i] = 0xFF000000 | ((sr / win) << 16) | ((sg / win) << 8) | (sb / win);
                int add = px[idx(Math.min(len - 1, i + r + 1), line, w, horizontal)];
                int sub = px[idx(Math.max(0, i - r), line, w, horizontal)];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
            for (int i = 0; i < len; i++) px[idx(i, line, w, horizontal)] = out[i];
        }
    }

    private static int idx(int i, int line, int w, boolean horizontal) { return horizontal ? line * w + i : i * w + line; }
}
