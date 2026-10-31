package app.glasstv.volna;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Маленькие обложки для списка треков: картинка ролика YouTube по id, грузится в фоне и запоминается
final class Thumbs {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(ImageView iv, String id) {
        iv.setTag(id);
        Bitmap b = CACHE.get(id);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        EX.execute(() -> {
            Bitmap got = fetch(id);
            if (got == null) return;
            CACHE.put(id, got);
            MAIN.post(() -> { if (id.equals(iv.getTag())) iv.setImageBitmap(got); });
        });
    }

    private static Bitmap fetch(String id) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                Bitmap full = BitmapFactory.decodeStream(in);
                if (full == null) return null;
                // Картинка 16:9, в строке квадрат: берём середину
                int s = Math.min(full.getWidth(), full.getHeight());
                Bitmap sq = Bitmap.createBitmap(full, (full.getWidth() - s) / 2, (full.getHeight() - s) / 2, s, s);
                return Bitmap.createScaledBitmap(sq, 96, 96, true);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
