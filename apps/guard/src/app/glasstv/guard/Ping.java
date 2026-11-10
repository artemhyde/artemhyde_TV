package app.glasstv.guard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Технический отчёт автору раз в сутки (выпуск 1.2.0, правила и поля в PingRules). Только у сборки выпуска и только при
// включённых отчётах (reportsOn): галочку «Отправлять автору технические отчёты» передаёт установщик (команда do ping),
// а там, где HYDE уже стоял и обновился сам, «Сторож» один раз спрашивает на ТВ (Ask), когда человек у главного экрана.
// Отказ автообновления не выключает. Номер устройства (deviceId) даёт установщик, нет его: «Сторож» создаёт свой.
// Шаг идёт в нитке обновлений (Updates.tick): в первый шаг дня назначается время через случайные 2–45 минут
final class Ping {
    static final String P_ON = "reportsOn", P_ASKED = "reportsAsked", P_ID = "deviceId";
    static final long ASK_TICK = 2 * 60_000;   // вопрос ждёт человека у главного экрана: смотреть раз в 2 минуты
    static final int MAX_HOPS = 3;

    private final Context c;
    private final SharedPreferences gp, up;
    private final Updates.Out out;
    private final SecureRandom rnd = new SecureRandom();

    Ping(Context c, Updates.Out out) {
        this.c = c;
        this.out = out;
        this.gp = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
        this.up = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
    }

    static boolean on(Context c) { return c.getSharedPreferences("guard", Context.MODE_PRIVATE).getBoolean(P_ON, false); }

    static boolean decided(Context c) { return c.getSharedPreferences("guard", Context.MODE_PRIVATE).contains(P_ON); }

    // Выбор человека: из мастера установки (команда), с экрана «Сторожа» или в вопросе на ТВ
    void set(boolean on, String id, String who) {
        SharedPreferences.Editor e = gp.edit().putBoolean(P_ON, on).putBoolean(P_ASKED, true);
        if (PingRules.idOk(id)) e.putString(P_ID, id);
        e.apply();
        if (on) up.edit().remove("ping.day").apply();   // включили: отправить сегодня, даже если сегодня уже пробовали
        out.log("Отчёты автору " + (on ? "включены" : "выключены") + " (" + who + ")");
    }

    String deviceId() { return deviceId(c); }

    // Номер устройства: от установщика или свой, созданный один раз (его же установщик читает перед обновлением)
    static String deviceId(Context c) {
        SharedPreferences gp = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
        String id = gp.getString(P_ID, null);
        if (PingRules.idOk(id)) return id;
        id = PingRules.newId(new SecureRandom());
        gp.edit().putString(P_ID, id).commit();
        return id;
    }

    // Проверочный приёмник (только командой с ПК, Cmd): https или сам ТВ через adb reverse. Пустой или null: убрать
    String setTestUrl(String url) {
        // Проверочный приёмник только в отладочной сборке: настоящий ТВ всегда шлёт в таблицу автора
        if (!AdbSelf.debuggable(c)) return "проверочный приёмник задаётся только в отладочной сборке «Сторожа»";
        if (url == null || url.trim().isEmpty()) {
            up.edit().remove("ping.url").apply();
            return "проверочный приёмник убран";
        }
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:"))
            return "адрес не подходит: только https://… или http://127.0.0.1:<порт>/…";
        up.edit().putString("ping.url", url).apply();
        return "проверочный приёмник " + url;
    }

    private String url() {
        String u = up.getString("ping.url", null);
        return u == null || !AdbSelf.debuggable(c) ? PingRules.URL : u;
    }

    // ===== Шаг (нитка обновлений) =====

    // Через сколько мс снова (-1: сегодня больше нечего)
    synchronized long tick() {
        if (!Hyde.releaseBuild(c)) return -1;
        if (askPending()) {
            if (askNow()) return -1;
            return ASK_TICK;
        }
        if (!on(c)) return -1;
        long now = System.currentTimeMillis();
        String day = today();
        String d = PingRules.decide(day, up.getString("ping.day", null), up.getString("ping.dueDay", null), up.getLong("ping.dueAt", 0), now);
        if (PingRules.DONE.equals(d)) return -1;
        if (PingRules.PLAN.equals(d)) {
            long at = now + PingRules.delay(rnd.nextDouble());
            up.edit().putString("ping.dueDay", day).putLong("ping.dueAt", at).apply();
            return at - now;
        }
        if (PingRules.WAIT.equals(d)) return Math.max(1_000, up.getLong("ping.dueAt", now) - now);
        up.edit().putString("ping.day", day).apply();   // одна попытка в сутки, как бы она ни кончилась
        send();
        return -1;
    }

    // Отправить сейчас (шаг или проверка с ПК). Итог: «принят», «уже был сегодня» или причина отказа
    synchronized String send() {
        String body = PingRules.json(info());
        long t0 = System.currentTimeMillis();
        String res;
        try {
            String[] r = post(url(), body);
            String why = PingRules.refused(Integer.parseInt(r[0]), r[1]);
            if (why == null) {
                res = PingRules.duplicate(r[1]) ? "принят (повтор за 12 часов)" : "принят";
                up.edit().putLong("ping.last", t0).remove("ping.err").apply();
            } else {
                res = why;
                up.edit().putString("ping.err", why).apply();
            }
        } catch (Exception e) {
            res = "не дошёл (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
            up.edit().putString("ping.err", res).apply();
        }
        up.edit().putLong("ping.tried", t0).apply();
        Log.i("guard", "Отчёт автору: " + res);
        return res;
    }

    PingRules.Info info() {
        PingRules.Info i = new PingRules.Info();
        PackageManager pm = c.getPackageManager();
        i.deviceId = deviceId();
        try {
            PackageInfo me = pm.getPackageInfo(c.getPackageName(), 0);
            i.release = me.versionName;
        } catch (Exception e) {
            i.release = "";
        }
        i.brand = Build.MANUFACTURER == null || Build.MANUFACTURER.trim().isEmpty() ? Build.BRAND : Build.MANUFACTURER;
        i.model = Build.MODEL;
        i.android = Build.VERSION.RELEASE;
        i.sdk = Build.VERSION.SDK_INT;
        i.profile = PingRules.profile(Build.MANUFACTURER, Build.BRAND, Caps.prop("ro.tcl.product_type"),
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK), Build.VERSION.SDK_INT);
        i.abi = String.join(",", Build.SUPPORTED_ABIS);
        i.autoUpdate = Hyde.on(c);
        String[] u = PingRules.lastUpdate(up.getString("hyde.need", null), up.getString("hyde.chain", null),
                up.getString("hyde.result", null), up.getString("hyde.resultRelease", null));
        i.lastUpdate = u[0];
        i.lastRelease = u[1];
        i.error = up.getString("hyde.err", "");
        i.lang = Locale.getDefault().getLanguage();
        // Присмотр (Health): кнопки пульта с учётом перезапусков после прошлого принятого отчёта, главный экран, сколько
        // отключённых установщиком пакетов снова включено
        i.keys = Health.keysField(c, up.getLong("ping.last", 0));
        i.home = Health.homeField(c);
        i.reenabled = Health.reenabledCount(c);
        return i;
    }

    // POST, переход Google на второй адрес (302) читается уже обычным GET. Ответ: {код, тело}
    private static String[] post(String url, String body) throws Exception {
        String method = "POST";
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:"))
                throw new Exception("адрес не https");
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            try {
                h.setConnectTimeout(20_000);
                h.setReadTimeout(30_000);   // приёмник Google просыпается несколько секунд
                h.setInstanceFollowRedirects(false);
                h.setRequestProperty("User-Agent", "tv-guard-ping");
                h.setRequestMethod(method);
                if ("POST".equals(method)) {
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    h.setFixedLengthStreamingMode(b.length);
                    try (OutputStream o = h.getOutputStream()) { o.write(b); }
                }
                int code = h.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = h.getHeaderField("Location");
                    if (loc == null) return new String[]{String.valueOf(code), ""};
                    url = new URL(new URL(url), loc).toString();
                    if (code != 307 && code != 308) method = "GET";
                    continue;
                }
                InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                if (in != null) {
                    try (InputStream s = in) {
                        byte[] buf = new byte[8 * 1024];
                        for (int k; (k = s.read(buf)) > 0 && bo.size() < 64 * 1024; ) bo.write(buf, 0, k);
                    }
                }
                return new String[]{String.valueOf(code), new String(bo.toByteArray(), StandardCharsets.UTF_8)};
            } finally {
                h.disconnect();
            }
        }
        throw new Exception("слишком много переходов");
    }

    // ===== Вопрос на ТВ (там, где HYDE обновился сам и выбора ещё не было) =====

    boolean askPending() {
        return Hyde.releaseBuild(c) && !decided(c) && !gp.getBoolean(P_ASKED, false) && !today().equals(up.getString("ping.askDay", null));
    }

    // Открыть вопрос, если человек у главного экрана и ничего не идёт. true: открыт
    private boolean askNow() {
        GuardService g = GuardService.running;
        if (g == null || g.upd == null || !GuardService.promptFree() || g.upd.hyde.adb.askPending() || g.upd.hyde.adb.pairingNow()
                || !g.upd.tapMoment(GuardService.topPackage())) return false;
        GuardService.prompted();
        up.edit().putString("ping.askDay", today()).apply();   // закрыли без ответа: спросим завтра
        try {
            c.startActivity(new Intent(c, Ask.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            out.log("Отчёты автору: спрашиваю на экране ТВ");
            return true;
        } catch (Exception e) {
            out.log("Отчёты автору: вопрос не открылся (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    // ===== Состояние =====

    String status() {
        if (!Hyde.releaseBuild(c)) return "сборка не из выпуска HYDE: отчётов автору нет";
        StringBuilder b = new StringBuilder(!decided(c) ? "не спрошено" : on(c) ? "включены" : "выключены");
        // номер устройства читает установщик перед обновлением, чтобы ТВ не получал новый номер с каждым прогоном
        b.append("; номер ").append(deviceId());
        long last = up.getLong("ping.last", 0), tried = up.getLong("ping.tried", 0);
        b.append("; последний принят: ").append(last == 0 ? "ещё не было" : fmt(last));
        if (tried > last) b.append("; попытка ").append(fmt(tried)).append(": ").append(up.getString("ping.err", "?"));
        String due = up.getString("ping.dueDay", null);
        if (on(c) && today().equals(due) && !today().equals(up.getString("ping.day", null)))
            b.append("; сегодня в ").append(new SimpleDateFormat("H:mm", Locale.US).format(new Date(up.getLong("ping.dueAt", 0))));
        if (up.getString("ping.url", null) != null && AdbSelf.debuggable(c)) b.append("; проверочный приёмник ").append(up.getString("ping.url", ""));
        return b.toString();
    }

    private static String fmt(long t) { return new SimpleDateFormat("d MMM, H:mm", Locale.forLanguageTag("ru")).format(new Date(t)); }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
}
