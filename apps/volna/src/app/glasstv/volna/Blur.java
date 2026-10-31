package app.glasstv.volna;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

// Размытие фото один раз, на уменьшенной копии: на слабой графике ТВ живое размытие дорого,
// а растянутая маленькая размытая картинка выглядит так же мягко и ничего не стоит в кадре
final class Blur {
    // Копия 240x135 с размытием; ImageView растянет её на весь экран со сглаживанием
    static Bitmap of(Bitmap src, int radius) {
        if (src == null) return null;
        int w = 240, h = Math.max(1, Math.round(240f * src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Насыщенность +20 %: размытый фон остаётся цветным, а не серым
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.2f);
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
        return small;
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
