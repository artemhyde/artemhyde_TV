package app.glasstv.volna;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Обложки. Маленькие для строк списка: картинка ролика по id, в памяти.
// Большие для плеера: лучшее качество, что есть у ролика (1280x720, потом 640x480 и 480x360), середина режется в чистый квадрат
// без чёрных полос и кладётся на диск. Там же, в фоне, считаются размытый фон и цвет обложки: главный поток только показывает готовое
final class Thumbs {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final LruCache<String, Art> BIG = new LruCache<>(4);
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final ExecutorService EX_BIG = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final int BIG_PX = 480;

    // Большая обложка трека и то, что из неё посчитано заранее
    static final class Art {
        final Bitmap cover, blur;
        final int accent;
        Art(Bitmap cover, Bitmap blur, int accent) { this.cover = cover; this.blur = blur; this.accent = accent; }
    }

    interface Got { void on(Art a); }

    static void load(ImageView iv, String id) {
        iv.setTag(id);
        Bitmap b = CACHE.get(id);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        EX.execute(() -> {
            Bitmap got = small(id);
            if (got == null) return;
            CACHE.put(id, got);
            MAIN.post(() -> { if (id.equals(iv.getTag())) iv.setImageBitmap(got); });
        });
    }

    // Уже готовая большая обложка, если она есть в памяти: тогда экран показывает её сразу, без проявления
    static Art ready(String id) { return BIG.get(id); }

    // Большая обложка для плеера. got получает null, если картинки нет совсем
    static void large(Context c, String id, Got got) { large(c, id, "", got); }

    // thumb: квадратная обложка YouTube Music (lh3); без неё картинка ролика
    static void large(Context c, String id, String thumb, Got got) {
        Art a = BIG.get(id);
        if (a != null) { if (got != null) MAIN.post(() -> got.on(a)); return; }
        final File dir = new File(c.getCacheDir(), "covers");
        EX_BIG.execute(() -> {
            Art done = BIG.get(id);
            if (done == null) {
                Bitmap cover = fromDisk(dir, id);
                if (cover == null) {
                    cover = fetchBig(id, thumb);
                    if (cover != null) toDisk(dir, id, cover);
                }
                if (cover != null) {
                    done = new Art(cover, Blur.of(cover, 6), Ui.accent(cover));
                    BIG.put(id, done);
                }
            }
            final Art out = done;
            if (got != null) MAIN.post(() -> got.on(out));
        });
    }

    // Следующий трек: обложку готовим заранее, тогда при смене она появляется сразу
    static void prefetch(Context c, String id, String thumb) { if (BIG.get(id) == null) large(c, id, thumb, null); }

    private static Bitmap small(String id) {
        Bitmap full = get("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
        if (full == null) return null;
        return Bitmap.createScaledBitmap(square(full), 96, 96, true);
    }

    private static Bitmap fetchBig(String id, String thumb) {
        if (thumb != null && !thumb.isEmpty()) {
            Bitmap sq = get(thumb);
            if (sq != null && sq.getWidth() >= 300) return square(sq);
        }
        for (String name : new String[]{"maxresdefault", "sddefault", "hqdefault"}) {
            Bitmap full = get("https://i.ytimg.com/vi/" + id + "/" + name + ".jpg");
            // Вместо отсутствующей картинки YouTube иногда отдаёт серую заглушку 120x90: такую не берём
            if (full == null || full.getWidth() < 300) continue;
            Bitmap sq = square(full);
            return sq.getWidth() > BIG_PX ? Bitmap.createScaledBitmap(sq, BIG_PX, BIG_PX, true) : sq;
        }
        return null;
    }

    // Квадрат из середины. Картинки 4:3 (sd, hq) это кадр 16:9 с чёрными полосами сверху и снизу: режем внутри самого кадра
    static Bitmap square(Bitmap full) {
        int w = full.getWidth(), h = full.getHeight();
        int top = 0, ch = h;
        if (w * 3 == h * 4) {
            ch = w * 9 / 16;
            top = (h - ch) / 2;
        }
        int s = Math.min(w, ch);
        return Bitmap.createBitmap(full, (w - s) / 2, top + (ch - s) / 2, s, s);
    }

    private static Bitmap get(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            try {
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) { return BitmapFactory.decodeStream(in); }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fromDisk(File dir, String id) {
        File f = new File(dir, id + ".jpg");
        if (!f.exists()) return null;
        f.setLastModified(System.currentTimeMillis());
        return BitmapFactory.decodeFile(f.getPath());
    }

    // На диске не больше 300 обложек: старые по времени последнего показа уходят
    private static void toDisk(File dir, String id, Bitmap b) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return;
            try (FileOutputStream o = new FileOutputStream(new File(dir, id + ".jpg"))) { b.compress(Bitmap.CompressFormat.JPEG, 90, o); }
            File[] all = dir.listFiles();
            if (all != null && all.length > 300) {
                java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < all.length - 300; i++) all[i].delete();
            }
        } catch (Exception ignored) { }
    }
}
