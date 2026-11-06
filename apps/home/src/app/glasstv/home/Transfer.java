package app.glasstv.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Перенос настроек (06.11, план Codex, блок 3): один файл с выбором человека в «Доме», «Стороже» и «Волне».
// Для другого ТВ, после переустановки и перед заменой своих сборок общими. В файл не попадают кэш погоды и курсов,
// что смотрели, код записок, журнал и служебные отметки: только то, что человек выбирал сам.
// Схема v1: {"hyde":"settings","v":1,"made":"…","home":{"setup","style","dock"},"guard":{…},"volna":{…}},
// значения с типом («b:true», «i:5», «s:текст», Prefs.java). «Дом» собирает и раскладывает: свою часть сам,
// части «Сторожа» и «Волны» через их AppCmd (do export, do import). Кто не ответил за 5 секунд, того в итоге нет
final class Transfer {
    static final int V = 1;
    static final String GUARD = "app.glasstv.guard", GUARD_CMD = "app.glasstv.guard.AppCmd";
    static final String PREFIX = "hyde-settings-";
    static final long WAIT = 5_000;

    interface Got { void got(JSONObject bundle); }
    interface Done { void done(String text); }
    private interface Answer { void answer(String data); }

    private Transfer() { }

    // ===== Своя часть «Дома» =====

    static JSONObject homePart(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("setup", Prefs.dump(c.getSharedPreferences(Setup.PREFS, Context.MODE_PRIVATE), null, Prefs.set("rev")));
        o.put("style", Prefs.dump(c.getSharedPreferences(Style.PREFS, Context.MODE_PRIVATE), Prefs.set(Style.KEY), null));
        o.put("dock", Prefs.dump(c.getSharedPreferences(HomeActivity.DOCK_PREFS, Context.MODE_PRIVATE),
                Prefs.set(HomeActivity.DOCK_KEY, HomeActivity.HIDE_KEY), null));
        return o;
    }

    static int applyHome(Context c, JSONObject o) {
        if (o == null) return 0;
        int n = load(c, Setup.PREFS, o.optJSONObject("setup"), null, Prefs.set("rev"))
                + load(c, Style.PREFS, o.optJSONObject("style"), Prefs.set(Style.KEY), null)
                + load(c, HomeActivity.DOCK_PREFS, o.optJSONObject("dock"), Prefs.set(HomeActivity.DOCK_KEY, HomeActivity.HIDE_KEY), null);
        Setup.bump(c);   // «Дом» перерисует экран с новыми настройками
        return n;
    }

    private static int load(Context c, String prefs, JSONObject part, Set<String> only, Set<String> skip) {
        if (part == null) return 0;
        SharedPreferences.Editor e = c.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit();
        int n = Prefs.load(e, part, only, skip);
        e.commit();
        return n;
    }

    // ===== Собрать и разложить =====

    static void collect(Context c, Got got) {
        final Context app = c.getApplicationContext();
        final JSONObject b = new JSONObject();
        try {
            b.put("hyde", "settings").put("v", V).put("made", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            b.put("home", homePart(app));
        } catch (Exception ignored) { }
        final int[] left = {2};
        final boolean[] fin = {false};
        final Runnable end = () -> { if (!fin[0]) { fin[0] = true; got.got(b); } };
        final Runnable one = () -> { if (--left[0] == 0) end.run(); };
        ask(app, GUARD, GUARD_CMD, new Intent().putExtra("do", "export"), d -> { put(b, "guard", d); one.run(); });
        ask(app, SetupActivity.VOLNA, SetupActivity.VOLNA_CMD, new Intent().putExtra("do", "export"), d -> { put(b, "volna", d); one.run(); });
        new Handler(Looper.getMainLooper()).postDelayed(end, WAIT);
    }

    static void apply(Context c, JSONObject b, Done done) {
        if (b == null || !"settings".equals(b.optString("hyde"))) { done.done("Это не файл настроек HYDE TV"); return; }
        if (b.optInt("v", 0) > V) { done.done("Копию сделала более новая версия. Сначала обновите приложения HYDE TV"); return; }
        final Context app = c.getApplicationContext();
        final StringBuilder out = new StringBuilder("Загружено настроек. «Дом»: " + applyHome(app, b.optJSONObject("home")));
        final int[] left = {2};
        final boolean[] fin = {false};
        final Runnable end = () -> { if (!fin[0]) { fin[0] = true; done.done(out.toString()); } };
        final Runnable one = () -> { if (--left[0] == 0) end.run(); };
        part(app, b, "guard", GUARD, GUARD_CMD, "«Сторож»", out, one);
        part(app, b, "volna", SetupActivity.VOLNA, SetupActivity.VOLNA_CMD, "«Волна»", out, one);
        new Handler(Looper.getMainLooper()).postDelayed(end, WAIT);
    }

    private static void part(Context c, JSONObject b, String key, String pkg, String cls, String name, StringBuilder out, Runnable then) {
        JSONObject p = b.optJSONObject(key);
        if (p == null) { then.run(); return; }
        ask(c, pkg, cls, new Intent().putExtra("do", "import").putExtra("json", p.toString()), d -> {
            out.append(", ").append(name).append(": ").append(d == null ? "нет ответа" : d.replace("загружено настроек: ", ""));
            then.run();
        });
    }

    private static void put(JSONObject b, String key, String d) {
        if (d == null || !d.startsWith("{")) return;
        try { b.put(key, new JSONObject(d)); } catch (Exception ignored) { }
    }

    // Ответ приложения через упорядоченную рассылку; приложения нет: ответ null сразу
    private static void ask(Context c, String pkg, String cls, Intent i, Answer a) {
        i.setClassName(pkg, cls).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            c.sendOrderedBroadcast(i, null, new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent y) { a.answer(getResultData()); }
            }, null, Activity.RESULT_OK, null, null);
        } catch (Exception e) {
            a.answer(null);
        }
    }

    // ===== Файл =====

    // В «Загрузки»: видно в «Файлах», можно унести на флешке. Ответ: имя файла, как его назвала система, или null
    static String save(Context c, JSONObject b) {
        String name = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            try (OutputStream o = c.getContentResolver().openOutputStream(u)) {
                o.write(b.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            try (Cursor q = c.getContentResolver().query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (q != null && q.moveToFirst() && q.getString(0) != null) name = q.getString(0);
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    static final class Copy {
        final Uri uri;
        final String when;
        Copy(Uri uri, String when) { this.uri = uri; this.when = when; }
    }

    // Свои копии в «Загрузках», новые первыми. Чужие файлы (с флешки, с другого ТВ) так не видны: для них выбор в «Файлах»
    static List<Copy> copies(Context c) {
        List<Copy> l = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru"));
        try (Cursor q = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{PREFIX + "%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (q != null && q.moveToNext() && l.size() < 5)
                l.add(new Copy(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, q.getLong(0)),
                        f.format(new Date(q.getLong(1) * 1000))));
        } catch (Exception ignored) { }
        return l;
    }

    static JSONObject read(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) { return new JSONObject(readAll(in)); }
    }

    static JSONObject readFile(String path) throws Exception {
        try (InputStream in = new FileInputStream(path)) { return new JSONObject(readAll(in)); }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("файл не открылся");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            b.write(buf, 0, n);
            if (b.size() > 262_144) throw new IOException("файл больше 256 КБ, это не копия настроек");
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
