package app.glasstv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

// Обои «Дома» (10.11, раздел «Оформление»): фото дня из подборки (Data), четыре своих градиента и своё фото с телефона.
// Градиенты те же, что в утверждённых макетах (CSS из исходника макетов), рисуются в коде за миллисекунды: в приложении
// нет лишних картинок. Своё фото приходит с телефона (Upload), обрезается до 16:9 и хранится одним файлом рядом с данными
final class Walls {
    private Walls() {}

    interface Maker { Bitmap make(int sample); }

    // Градиент: радиальные пятна поверх линейного фона 160°. Пятно: {доля ширины, доля высоты (радиусы), центр x, центр y},
    // цвет пятна в центре; к 70 % луча прозрачно, как в CSS radial-gradient(… , цвет, прозрачный 70%)
    private static final class Grad {
        final float[][] spots;
        final int[] spotColor;
        final int[] lin;
        final float[] linPos;
        Grad(float[][] spots, int[] spotColor, int[] lin, float[] linPos) {
            this.spots = spots; this.spotColor = spotColor; this.lin = lin; this.linPos = linPos;
        }
    }

    private static int rgba(int r, int g, int b, float a) { return (Math.round(a * 255) << 24) | (r << 16) | (g << 8) | b; }

    private static final Map<String, Grad> GRADS = new HashMap<>();
    static {
        GRADS.put(Look.DUSK, new Grad(
                new float[][]{{0.6f, 0.7f, 0.78f, 0.18f}, {0.7f, 0.8f, 0.12f, 0.9f}},
                new int[]{rgba(255, 170, 130, .55f), rgba(80, 70, 160, .65f)},
                new int[]{0xFF241F3D, 0xFF4A3456, 0xFFA8665A}, new float[]{0f, 0.48f, 1f}));
        GRADS.put(Look.DEEP, new Grad(
                new float[][]{{0.6f, 0.7f, 0.2f, 0.2f}, {0.6f, 0.6f, 0.85f, 0.85f}},
                new int[]{rgba(90, 160, 220, .45f), rgba(20, 80, 120, .7f)},
                new int[]{0xFF0B1626, 0xFF13304C, 0xFF2C5A78}, new float[]{0f, 0.55f, 1f}));
        GRADS.put(Look.GRAPHITE, new Grad(
                new float[][]{{0.6f, 0.6f, 0.25f, 0.15f}},
                new int[]{rgba(255, 255, 255, .10f)},
                new int[]{0xFF18181D, 0xFF26262E, 0xFF33333D}, new float[]{0f, 0.6f, 1f}));
        GRADS.put(Look.SAND, new Grad(
                new float[][]{{0.6f, 0.7f, 0.8f, 0.2f}},
                new int[]{rgba(255, 226, 180, .45f)},
                new int[]{0xFF2E251F, 0xFF6B5643, 0xFFB89474}, new float[]{0f, 0.55f, 1f}));
    }

    static boolean gradient(String w) { return GRADS.containsKey(w); }

    // Градиент размером w x h. dither: лёгкий шум в одну ступень яркости, чтобы на большой панели не было полос
    static Bitmap render(String w, int width, int height, boolean dither) {
        Grad g = GRADS.get(w);
        if (g == null) g = GRADS.get(Look.GRAPHITE);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(Tile.css(160, width, height, g.lin, g.linPos));
        c.drawRect(0, 0, width, height, p);
        // В CSS первое пятно лежит сверху: рисуем с конца
        for (int i = g.spots.length - 1; i >= 0; i--) {
            float[] s = g.spots[i];
            float rx = s[0] * width, ry = s[1] * height, cx = s[2] * width, cy = s[3] * height;
            int col = g.spotColor[i];
            RadialGradient rg = new RadialGradient(cx, cy, rx, new int[]{col, col & 0x00FFFFFF}, new float[]{0f, 0.7f}, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1f, ry / rx, cx, cy);
            rg.setLocalMatrix(m);
            p.setShader(rg);
            c.drawRect(0, 0, width, height, p);
        }
        if (!dither) return Tile.fixed(b);
        noise(b);
        // Обои: в 16 бит, как фото. Непрозрачная картинка этому ТВ почти бесплатна, а 32-битная во весь экран
        // смешивается с тем, что под ней, и стоит больше 10 мс на кадр (замер 10.11). Шум выше прячет ступени
        Bitmap lean = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        new Canvas(lean).drawBitmap(b, 0, 0, new Paint(Paint.DITHER_FLAG));
        b.recycle();
        return Tile.fixed(lean);
    }

    // Шум ±1 ступень по каждому цвету: на тёмных градиентах 8 бит иначе видны ступени
    private static void noise(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int seed = 0x2545F491;
        for (int i = 0; i < px.length; i++) {
            seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
            int n = (seed & 3) - 1;   // -1, 0, 1, 2 → почти симметрично вокруг нуля
            if (n == 2) n = 0;
            int c = px[i];
            int r = clamp(((c >> 16) & 255) + n), g = clamp(((c >> 8) & 255) + n), bl = clamp((c & 255) + n);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    // ===== Обои для экрана =====

    private static final Map<String, Data.Photo> CACHE = new HashMap<>();

    // Что сейчас на экране по выбору (с примеркой): фото дня даёт Data, остальное здесь
    static Data.Photo current(Context c, Data d) {
        String w = Look.wall(c);
        if (Look.OWN.equals(w)) {
            Data.Photo own = own(c);
            if (own != null) return own;
            w = Look.PHOTO;
        }
        if (!gradient(w)) return d.currentPhoto();
        Data.Photo ph = CACHE.get(w);
        if (ph == null) {
            final String kind = w;
            // Градиент одной картинкой 960x540: растянутый вдвое, он так же гладок, а памяти вчетверо меньше
            ph = new Data.Photo("wall:" + w, "", "", "", null, sample -> render(kind, 960 / Math.max(1, sample), 540 / Math.max(1, sample), true), true);
            CACHE.put(w, ph);
        }
        return ph;
    }

    // ===== Своё фото =====

    static File ownFile(Context c) { return new File(new File(c.getFilesDir(), "wall"), "own.jpg"); }

    static boolean hasOwn(Context c) { return ownFile(c).length() > 1000; }

    static Data.Photo own(Context c) {
        final File f = ownFile(c);
        if (f.length() <= 1000) return null;
        return new Data.Photo("own:" + f.lastModified() + ":" + f.length(), "", "Своё фото", "", f,
                sample -> Data.decode(f, sample), false);
    }

    // Принять фото с телефона: повернуть по EXIF, обрезать посередине до 16:9, уменьшить до 1920x1080, сохранить JPEG.
    // Возвращает пустую строку при успехе или причину словами
    static String saveOwn(Context c, byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return "это не фото (формат не читается)";
            int rot = exifRotation(data);
            int w = o.outWidth, h = o.outHeight;
            if (rot == 90 || rot == 270) { int t = w; w = h; h = t; }
            // Шаг уменьшения при чтении: пока после него обе стороны не меньше экрана
            int sample = 1;
            while (w / (sample * 2) >= 1920 && h / (sample * 2) >= 1080) sample *= 2;
            BitmapFactory.Options r = new BitmapFactory.Options();
            r.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, r);
            if (src == null) return "фото не читается";
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap t = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (t != src) src.recycle();
                src = t;
            }
            int sw = src.getWidth(), sh = src.getHeight();
            // Середина кадра 16:9
            Rect crop;
            if (sw * 9 > sh * 16) { int cw = sh * 16 / 9; crop = new Rect((sw - cw) / 2, 0, (sw - cw) / 2 + cw, sh); }
            else { int ch = sw * 9 / 16; crop = new Rect(0, (sh - ch) / 2, sw, (sh - ch) / 2 + ch); }
            int ow = Math.min(1920, crop.width()), oh = Math.round(ow * 9f / 16f);
            Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, crop, new Rect(0, 0, ow, oh), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            src.recycle();
            File f = ownFile(c);
            f.getParentFile().mkdirs();
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream fo = new FileOutputStream(part)) { out.compress(Bitmap.CompressFormat.JPEG, 92, fo); }
            out.recycle();
            if (f.exists() && !f.delete()) return "старое фото не удалилось";
            if (!part.renameTo(f)) return "не удалось сохранить";
            return "";
        } catch (OutOfMemoryError e) {
            return "фото слишком большое";
        } catch (Exception e) {
            return "ошибка: " + e.getClass().getSimpleName();
        }
    }

    private static int exifRotation(byte[] data) {
        try {
            android.media.ExifInterface e = new android.media.ExifInterface(new java.io.ByteArrayInputStream(data));
            switch (e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // ===== Миниатюры для выбора =====

    // Миниатюра обоев w x h: градиент рисуется сразу, фото читаются уменьшенными (шаг 8: 240x135 из 1920x1080)
    static Bitmap thumb(Context c, Data d, String w, int tw, int th) {
        if (gradient(w)) return render(w, tw, th, false);
        Data.Photo ph = Look.OWN.equals(w) ? own(c) : d.currentPhoto();
        if (ph == null) return null;
        Bitmap b = ph.load(8);
        if (b == null) return null;
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        int bw = b.getWidth(), bh = b.getHeight();
        Rect src = bw * th > bh * tw ? new Rect((bw - bh * tw / th) / 2, 0, (bw + bh * tw / th) / 2, bh)
                : new Rect(0, (bh - bw * th / tw) / 2, bw, (bh + bw * th / tw) / 2);
        new Canvas(out).drawBitmap(b, src, new Rect(0, 0, tw, th), new Paint(Paint.FILTER_BITMAP_FLAG));
        return Tile.fixed(out);
    }
}
