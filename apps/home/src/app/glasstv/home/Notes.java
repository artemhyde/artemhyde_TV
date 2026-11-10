package app.glasstv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Записка с телефона на экране (10.11, просьба: «быстренько на телефоне что-то пишу... и оно появляется на экране.
// Даже нарисовать что-то можно. Сопряжение по QR-коду»). Телефон один раз наводит камеру на код («Оформление», виджет
// «Записка»), открывается страница самого «Дома» в домашней сети: текст или рисунок пальцем и «Показать на ТВ». Страницу
// можно вынести значком на экран телефона, она помнит телевизор. Записка встаёт на экран блокировки на место цитаты и
// держится, пока её не уберут (с телефона или в «Оформлении») или не пришлют новую.
// Сервер живёт в процессе «Дома», пока записки включены (порт 8767; без запросов он ничего не стоит). Безопасность: адрес
// со случайным ключом (его видно только в коде на ТВ; «Отключить все телефоны» меняет ключ), отвечает только домашней сети,
// текст до 300 знаков, рисунок до 3 МБ, запись не чаще раза в секунду. Ключ и записка в своих настройках «notes»: в файл
// переноса они не идут. Записки «Сторожа» (поверх любого экрана на 12 секунд, по коду из шести цифр) это отдельная вещь
final class Notes {
    private Notes() { }

    static final String PREFS = "notes";
    static final int PORT = 8767, MAX_TEXT = 300, MAX_DRAW = 3 * 1024 * 1024;
    private static final String ABC = "abcdefghjkmnpqrstuvwxyz23456789";

    // Главный экран: что записка пришла или ушла, что телефон открыл страницу. Зовётся на главном потоке
    interface Listener { void changed(); }
    static Listener listener;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // Включены ли записки: выбор человека в «Оформлении» (настройки setup, ключ notes, on или off; по умолчанию выключены)
    static boolean on(Context c) { return "on".equals(Setup.sp(c).getString("notes", "off")); }

    // Без номера правки настроек: иначе «Дом» перекачал бы погоду и курсы
    static void setOn(Context c, boolean on) {
        Setup.sp(c).edit().putString("notes", on ? "on" : "off").apply();
        if (on) start(c); else stop();
        changed();
    }

    static synchronized String key(Context c) {
        String k = sp(c).getString("key", "");
        if (k.length() < 8) { k = random(10); sp(c).edit().putString("key", k).commit(); }
        return k;
    }

    // Новый ключ: страницы, сохранённые на телефонах, перестают работать
    static synchronized void newKey(Context c) { sp(c).edit().putString("key", random(10)).commit(); }

    private static String random(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(ABC.charAt(r.nextInt(ABC.length())));
        return b.toString();
    }

    // ===== Записка =====

    static final class Note {
        final String kind, text;
        final long at;

        Note(String kind, String text, long at) { this.kind = kind; this.text = text; this.at = at; }

        boolean draw() { return "draw".equals(kind); }

        // Подпись под запиской: «записка · 18:42»
        String caption() {
            return (draw() ? "рисунок" : "записка") + " · " + new SimpleDateFormat("H:mm", Locale.forLanguageTag("ru")).format(new Date(at));
        }
    }

    // Записка сейчас; null: её нет или записки выключены
    static Note current(Context c) {
        if (!on(c)) return null;
        SharedPreferences p = sp(c);
        String k = p.getString("kind", "");
        if ("text".equals(k)) return new Note(k, p.getString("text", ""), p.getLong("at", 0));
        if ("draw".equals(k) && drawFile(c).exists()) return new Note(k, "", p.getLong("at", 0));
        return null;
    }

    static boolean has(Context c) { return current(c) != null; }

    static File drawFile(Context c) { return new File(c.getFilesDir(), "note.png"); }

    static void clear(Context c) {
        sp(c).edit().remove("kind").remove("text").remove("at").commit();
        //noinspection ResultOfMethodCallIgnored
        drawFile(c).delete();
        changed();
    }

    private static void changed() { MAIN.post(() -> { if (listener != null) listener.changed(); }); }

    // Текст: переносы строк оставить (не больше двух подряд), прочие служебные знаки убрать, не длиннее 300 знаков
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        int nl = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\r') continue;
            if (ch == '\n') { if (++nl <= 2) b.append('\n'); continue; }
            if (ch < ' ' && ch != '\t') continue;
            nl = 0;
            b.append(ch == '\t' ? ' ' : ch);
        }
        String t = b.toString().trim();
        if (t.codePointCount(0, t.length()) > MAX_TEXT) t = t.substring(0, t.offsetByCodePoints(0, MAX_TEXT)).trim();
        return t;
    }

    static String saveText(Context c, String text) {
        String t = clean(text);
        if (t.isEmpty()) return "пустая записка";
        sp(c).edit().putString("kind", "text").putString("text", t).putLong("at", System.currentTimeMillis()).commit();
        //noinspection ResultOfMethodCallIgnored
        drawFile(c).delete();
        changed();
        return "";
    }

    // Рисунок: проверить, что это картинка, уменьшить до 1280 точек по ширине и записать заново (без лишних данных файла)
    static String saveDraw(Context c, byte[] body) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(body, 0, body.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return "это не рисунок";
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = 1;
            while (o.outWidth / d.inSampleSize > 2560) d.inSampleSize *= 2;
            Bitmap bm = BitmapFactory.decodeByteArray(body, 0, body.length, d);
            if (bm == null) return "рисунок не читается";
            if (bm.getWidth() > 1280) bm = Bitmap.createScaledBitmap(bm, 1280, Math.max(1, Math.round(bm.getHeight() * 1280f / bm.getWidth())), true);
            File f = drawFile(c), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { bm.compress(Bitmap.CompressFormat.PNG, 100, out); }
            if (!tmp.renameTo(f)) return "не удалось сохранить";
            sp(c).edit().putString("kind", "draw").putString("text", "").putLong("at", System.currentTimeMillis()).commit();
            changed();
            return "";
        } catch (OutOfMemoryError e) {
            return "рисунок слишком большой";
        } catch (Exception e) {
            return "ошибка: " + e.getClass().getSimpleName();
        }
    }

    // ===== Сервер =====

    private static volatile ServerSocket server;
    private static ExecutorService pool;
    private static volatile long lastWrite;
    static volatile int port = -1;

    static synchronized void start(Context c) {
        if (server != null || !on(c)) return;
        final Context app = c.getApplicationContext();
        for (int p = PORT; p < PORT + 5; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                port = p;
                if (pool == null) pool = Executors.newFixedThreadPool(2);
                Thread t = new Thread(() -> loop(app, s), "home-notes");
                t.setDaemon(true);
                t.start();
                Log.i("home", "записки: страница на порту " + p);
                return;
            } catch (Exception ignored) { }
        }
        Log.w("home", "записки: все порты заняты");
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        port = -1;
    }

    private static void loop(Context app, ServerSocket s) {
        while (!s.isClosed()) {
            final Socket sock;
            try { sock = s.accept(); } catch (Exception e) { if (s.isClosed()) return; continue; }
            pool.execute(() -> {
                try (Socket c = sock) {
                    c.setSoTimeout(15_000);
                    handle(app, c);
                } catch (Exception e) {
                    Log.w("home", "записки: запрос оборвался", e);
                }
            });
        }
    }

    private static void handle(Context app, Socket sock) throws Exception {
        InetAddress ra = sock.getInetAddress();
        // Только домашняя сеть: чужому адресу даже не отвечаем
        if (ra == null || !(ra.isSiteLocalAddress() || ra.isLoopbackAddress() || ra.isLinkLocalAddress())) return;
        InputStream in = sock.getInputStream();
        String head = Upload.readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) return;
        String method = req[0], path = req[1];
        long len = 0;
        for (String l : lines) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try { len = Long.parseLong(l.substring(i + 1).trim()); } catch (Exception ignored) { }
            }
        }
        OutputStream out = sock.getOutputStream();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        String base = "/n/" + key(app);
        if (!path.equals(base) && !path.startsWith(base + "/")) { Upload.reply(out, 404, "text/plain; charset=utf-8", "нет такой страницы".getBytes(StandardCharsets.UTF_8)); return; }
        String rest = path.substring(base.length());
        if ("GET".equals(method)) {
            switch (rest) {
                case "":
                case "/":
                    Upload.reply(out, 200, "text/html; charset=utf-8", page(app, base));
                    MAIN.post(() -> say("Телефон подключён: пишите или рисуйте на нём"));
                    return;
                case "/state":
                    Upload.reply(out, 200, "application/json; charset=utf-8", state(app));
                    return;
                case "/icon.png":
                    Upload.reply(out, 200, "image/png", icon());
                    return;
                case "/manifest.webmanifest":
                    Upload.reply(out, 200, "application/manifest+json; charset=utf-8", manifest(base));
                    return;
                default:
                    Upload.reply(out, 404, "text/plain; charset=utf-8", "нет такой страницы".getBytes(StandardCharsets.UTF_8));
                    return;
            }
        }
        if (!"POST".equals(method)) { Upload.reply(out, 400, "application/json", Upload.json(false, "не тот запрос")); return; }
        long now = System.currentTimeMillis();
        if (now - lastWrite < 1000) { Upload.reply(out, 429, "application/json", Upload.json(false, "слишком часто, подождите секунду")); return; }
        lastWrite = now;
        long max = "/draw".equals(rest) ? MAX_DRAW : 8 * MAX_TEXT;
        if (len < 0 || len > max) { Upload.reply(out, 413, "application/json", Upload.json(false, "/draw".equals(rest) ? "рисунок больше 3 МБ" : "записка слишком длинная")); return; }
        byte[] body = new byte[(int) len];
        int got = 0;
        while (got < len) {
            int n = in.read(body, got, (int) len - got);
            if (n < 0) break;
            got += n;
        }
        if (got < len) { Upload.reply(out, 400, "application/json", Upload.json(false, "пришло не целиком")); return; }
        String err;
        switch (rest) {
            case "/text": err = saveText(app, new String(body, StandardCharsets.UTF_8)); break;
            case "/draw": err = saveDraw(app, body); break;
            case "/clear": clear(app); err = ""; break;
            default: Upload.reply(out, 404, "application/json", Upload.json(false, "нет такой страницы")); return;
        }
        Upload.reply(out, err.isEmpty() ? 200 : 400, "application/json", Upload.json(err.isEmpty(), err));
        if (err.isEmpty() && !"/clear".equals(rest)) MAIN.post(() -> say("Записка пришла: она на экране блокировки"));
    }

    private static byte[] state(Context c) {
        Note n = current(c);
        String k = n == null ? "" : n.kind, t = n == null ? "" : n.text;
        String js = "{\"kind\":\"" + k + "\",\"text\":\"" + esc(t) + "\"}";
        return js.getBytes(StandardCharsets.UTF_8);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch < ' ') b.append(String.format(Locale.US, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.toString();
    }

    private static String pageCache;

    // Страница для телефона лежит в assets/notes.html; адрес страницы подставляется вместо __BASE__
    private static synchronized byte[] page(Context c, String base) throws Exception {
        if (pageCache == null) {
            try (InputStream in = c.getAssets().open("notes.html")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
                pageCache = new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return pageCache.replace("__BASE__", base).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] manifest(String base) {
        String js = "{\"name\":\"Записка на ТВ\",\"short_name\":\"Записка\",\"start_url\":\"" + base + "\",\"display\":\"standalone\","
                + "\"background_color\":\"#0b0b10\",\"theme_color\":\"#0b0b10\",\"icons\":[{\"src\":\"" + base + "/icon.png\",\"sizes\":\"180x180\",\"type\":\"image/png\"}]}";
        return js.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] iconCache;

    // Значок страницы на экране телефона: карандаш на тёмном стекле
    private static synchronized byte[] icon() {
        if (iconCache != null) return iconCache;
        int n = 180;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, n, 0xFF3A4060, 0xFF0E0F16, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        Bitmap g = Glyph.bitmap("pen", Math.round(n * 0.62f), 0xFFFFFFFF, 1.5f);
        c.drawBitmap(g, (n - g.getWidth()) / 2f, (n - g.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        iconCache = out.toByteArray();
        return iconCache;
    }

    // ===== «Оформление»: виджет «Записка» =====

    static void sheet(HomeActivity a, Widgets.Done done) {
        final boolean on = on(a), has = has(a);
        List<Sheet.Item> items = new ArrayList<>();
        items.add(Widgets.chip("pair", on ? "Подключить телефон" : "Включить и подключить телефон"));
        if (has) items.add(Widgets.chip("clear", "Убрать записку с экрана"));
        if (on) items.add(Widgets.chip("reset", "Отключить все телефоны"));
        items.add(Widgets.chip("off", "Не показывать"));
        Sheet s = Widgets.chips(a, "Записка с телефона",
                "Пишите или рисуйте на телефоне: записка встаёт на экран блокировки вместо цитаты и держится, пока её не уберут",
                items, 1, 440, on ? "" : "off");
        s.onPick = (sh, it, i) -> {
            if (it == null) return;
            sh.dismiss(false);
            switch (it.key) {
                case "pair":
                    if (!on) setOn(a, true);
                    pair(a);
                    if (!on) done.done();
                    break;
                case "clear":
                    clear(a);
                    done.done();
                    break;
                case "reset":
                    newKey(a);
                    a.showFlash("Старые ссылки на телефонах больше не работают");
                    pair(a);
                    break;
                default:
                    if (on) { setOn(a, false); done.done(); }
            }
        };
        a.showSheetBlurred(s);
    }

    private static WeakReference<TextView> status = new WeakReference<>(null);

    private static void say(String s) {
        TextView t = status.get();
        if (t != null && t.isAttachedToWindow()) t.setText(s);
    }

    // Панель с кодом: телефон наводит камеру и открывает страницу записок
    static void pair(HomeActivity a) {
        start(a);
        String ip = Upload.lanAddress();
        String url = ip == null || port < 0 ? null : "http://" + ip + ":" + port + "/n/" + key(a);
        Log.i("home", "записки: страница " + url);
        Sheet s = new Sheet(a, "Записка с телефона", null, new ArrayList<Sheet.Item>(), 1, a.vision, 300, 0);
        s.content = panel(a, url);
        s.onBack = () -> s.dismiss(false);
        a.showSheetBlurred(s);
    }

    private static LinearLayout panel(HomeActivity a, String url) {
        float dp = a.dp;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (url != null) {
            ImageView qr = new ImageView(a);
            qr.setImageBitmap(Upload.qrBitmap(url, Math.round(180 * dp)));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFFFFFFF);
            bg.setCornerRadius(18 * dp);
            qr.setBackground(bg);
            int p = Math.round(20 * dp);
            qr.setPadding(p, p, p, p);
            row.addView(qr, new LinearLayout.LayoutParams(Math.round(220 * dp), Math.round(220 * dp)));
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Math.round(360 * dp), -2);
        cl.leftMargin = Math.round(28 * dp);
        row.addView(col, cl);
        if (url == null) {
            col.addView(line(a, "ТВ не в домашней сети: телефону не до чего достучаться. Проверьте Wi-Fi или кабель", 17, 0xEBFFFFFF),
                    new LinearLayout.LayoutParams(-1, -2));
        } else {
            String[] steps = {"1. Наведите камеру телефона на код", "2. Напишите или нарисуйте и нажмите «Показать на ТВ»",
                    "3. Добавьте страницу на экран телефона: записка всегда под рукой"};
            for (String st : steps) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = Math.round(8 * dp);
                col.addView(line(a, st, 18, 0xF2FFFFFF), lp);
            }
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
            ul.topMargin = Math.round(10 * dp);
            col.addView(line(a, "Телефон и ТВ в одной сети Wi-Fi. Или откройте в браузере телефона:", 13, 0x99FFFFFF), ul);
            TextView u = line(a, url, 15, 0xE6FFFFFF);
            u.setTypeface(Fonts.med18);
            LinearLayout.LayoutParams ul2 = new LinearLayout.LayoutParams(-1, -2);
            ul2.topMargin = Math.round(4 * dp);
            col.addView(u, ul2);
        }
        TextView st = line(a, url == null ? "" : "Жду телефон…  «Назад»: закрыть", 14, Fonts.ACCENT);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Math.round(14 * dp);
        col.addView(st, sl);
        status = new WeakReference<>(st);
        return row;
    }

    private static TextView line(HomeActivity a, String s, float sp, int color) {
        TextView t = Fonts.text(a, sp, Fonts.regular(sp), color);
        t.setText(s);
        t.setFontFeatureSettings("");
        t.setLineSpacing(0, 1.12f);
        return t;
    }
}
