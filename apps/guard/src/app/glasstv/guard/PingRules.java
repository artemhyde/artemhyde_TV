package app.glasstv.guard;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Технический отчёт автору раз в сутки (выпуск 1.2.0: без личных данных, только модель и техническое состояние устройства):
// правила без Android, проверка на ПК (test/PingRulesTest.java).
// Уходит только при включённых отчётах: модель и марка ТВ, Android, версия HYDE, включено ли автообновление, итог
// последнего обновления и его ошибка без адресов и номеров. Приём: тот же веб-скрипт Google при таблице автора, что
// у установщика (installer/report.go), лист «Устройства». Не дошло: следующая попытка завтра, ни на что это не влияет
final class PingRules {
    private PingRules() { }

    static final String URL = "https://script.google.com/macros/s/AKfycbw9tzN979i7HZs_cTHT2FEWZndkg0uUuCpHVtonE0YYQROWYlSyjm4gaRfa1V8FK8BG6Q/exec";
    // Когда в сутки: через случайные 2–45 минут после первого шага «Сторожа» в этот день. Телевизоры включают в разное
    // время, и сдвиг разводит их ещё дальше; привязка ко времени суток не годится: ТВ, выключенный в «своё» время, молчал бы
    static final long DELAY_MIN = 2 * 60_000, DELAY_MAX = 45 * 60_000;
    static final Pattern ID = Pattern.compile("[a-z0-9]{16,40}");
    static final String NONE = "none", OK = "ok", FAILED = "failed", INSTALLER = "needs_installer", WAITING = "waiting";

    static final class Info {
        String deviceId, release, brand, model, android, profile, abi, lastUpdate, lastRelease, error, lang;
        int sdk;
        boolean autoUpdate;
        // Присмотр (HealthRules): keys ok|repaired|broken|absent, home ok|lost (null: «Дома» нет), reenabled 0–200 (-1: списка нет)
        String keys, home;
        int reenabled = -1;
    }

    static boolean idOk(String id) { return id != null && ID.matcher(id).matches(); }

    // Номер устройства, если установщик его не передал: 32 строчных шестнадцатеричных знака, случайные
    static String newId(SecureRandom r) {
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }

    // Пора ли отправлять. day: сегодняшняя дата; sentDay: день последней попытки; dueDay и dueAt: на когда назначено
    // в этот день. Ответ: SEND (отправить сейчас), PLAN (назначить на now + сдвиг), WAIT (ждать dueAt), DONE (сегодня уже было)
    static final String SEND = "send", PLAN = "plan", WAIT = "wait", DONE = "done";

    static String decide(String day, String sentDay, String dueDay, long dueAt, long now) {
        if (day.equals(sentDay)) return DONE;
        if (!day.equals(dueDay) || dueAt <= 0) return PLAN;
        return now >= dueAt ? SEND : WAIT;
    }

    static long delay(double rnd01) {
        return DELAY_MIN + (long) ((DELAY_MAX - DELAY_MIN) * Math.max(0, Math.min(1, rnd01)));
    }

    // Итог последнего обновления для таблицы. need: выпуск, которому нужен установщик (или null); chain: выпуск, который
    // ставится или ждёт; result и resultRelease: чем кончился последний выпуск (ok, failed или null, если ещё не было)
    static String[] lastUpdate(String need, String chain, String result, String resultRelease) {
        if (need != null) return new String[]{INSTALLER, need};
        if (chain != null) return new String[]{WAITING, chain};
        if (OK.equals(result) || FAILED.equals(result)) return new String[]{result, resultRelease == null ? "" : resultRelease};
        return new String[]{NONE, ""};
    }

    // Профиль ТВ, как у установщика (installer/caps.go): TCL с Google TV, другой Android TV или Google TV, иначе unsupported
    static String profile(String manufacturer, String brand, String tclType, boolean leanback, int sdk) {
        if (sdk > 0 && sdk < 28) return "unsupported";
        if (CapsRules.isTcl(manufacturer, brand) && "GTV".equalsIgnoreCase(trim(tclType))) return "tcl-gtv";
        return leanback ? "generic-gtv" : "unsupported";
    }

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?\\b");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{6,}");
    private static final Pattern MDNS = Pattern.compile("adb-[A-Za-z0-9]+-[A-Za-z0-9]+(\\._adb-tls-[a-z]+\\._tcp)?\\.?");
    private static final Pattern MAC = Pattern.compile("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern URLS = Pattern.compile("\\b(https?://[^/\\s]+)[^\\s]*");
    private static final Pattern SERIAL = Pattern.compile("\\b[A-Za-z0-9]*\\d[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*\\b|\\b[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*\\d[A-Za-z0-9]*\\b");

    // Текст ошибки без адресов, серийных номеров, почты и путей ссылок, не длиннее 500 знаков (как cleanError установщика)
    static String cleanError(String s) {
        if (s == null) return "";
        s = MDNS.matcher(s).replaceAll("<ТВ>");
        s = MAC.matcher(s).replaceAll("<адрес>");
        s = EMAIL.matcher(s).replaceAll("<почта>");
        s = URLS.matcher(s).replaceAll("$1/…");
        s = IPV4.matcher(s).replaceAll("<адрес>");
        Matcher m = IPV6.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String w = m.group();
            int n = w.length() - w.replace(":", "").length();
            boolean addr = n >= 2 && (w.contains("::") || n >= 4 || w.toLowerCase(Locale.ROOT).matches(".*[a-f].*"));
            m.appendReplacement(b, Matcher.quoteReplacement(addr ? "<адрес>" : w));
        }
        m.appendTail(b);
        s = b.toString();
        m = SERIAL.matcher(s);
        b = new StringBuffer();
        while (m.find()) {
            String w = m.group();
            m.appendReplacement(b, Matcher.quoteReplacement(w.length() >= 10 && !w.contains(".") ? "<номер>" : w));
        }
        m.appendTail(b);
        s = b.toString().trim().replaceAll("\\s+", " ");
        return cut(s, 500);
    }

    // Тело запроса. Поля в пределах таблицы автора: длиннее она не обрезает, а отклоняет весь отчёт
    static String json(Info i) {
        StringBuilder b = new StringBuilder("{");
        str(b, "kind", "ping");
        str(b, "device_id", i.deviceId);
        str(b, "release", cut(i.release, 20));
        str(b, "tv_brand", cut(i.brand, 60));
        str(b, "tv_model", cut(i.model, 80));
        str(b, "android", cut(i.android, 10));
        if (i.sdk >= 1 && i.sdk <= 100) b.append(",\"sdk\":").append(i.sdk);
        str(b, "profile", cut(i.profile, 30));
        str(b, "abi", cut(i.abi, 30));
        b.append(",\"auto_update\":").append(i.autoUpdate);
        str(b, "last_update", i.lastUpdate);
        str(b, "last_update_release", cut(i.lastRelease, 20));
        str(b, "error", cleanError(i.error));
        str(b, "lang", cut(i.lang, 10));
        // Поля присмотра (10.11): только известные значения; чего нет, того и в отчёте нет
        if (KEYS.contains(i.keys)) str(b, "keys", i.keys);
        if (HOME.contains(i.home)) str(b, "home", i.home);
        if (i.reenabled >= 0) b.append(",\"reenabled\":").append(Math.min(i.reenabled, HealthRules.MAX_LIST));
        return b.append('}').toString();
    }

    static final java.util.List<String> KEYS = java.util.Arrays.asList(HealthRules.OK, HealthRules.REPAIRED, HealthRules.BROKEN, HealthRules.ABSENT);
    static final java.util.List<String> HOME = java.util.Arrays.asList(HealthRules.OK, HealthRules.LOST);

    // Ответ таблицы: null, если принят ({"ok":true}, в том числе повтор за 12 часов), иначе почему нет
    static String refused(int code, String body) {
        if (code != 200) return "таблица ответила " + code;
        String t = body == null ? "" : body.replace(" ", "");
        if (t.contains("\"ok\":true")) return null;
        Matcher m = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]{0,200})\"").matcher(body == null ? "" : body);
        return m.find() ? "таблица не приняла: " + m.group(1) : "таблица ответила не так, как ждали";
    }

    static boolean duplicate(String body) { return body != null && body.replace(" ", "").contains("\"duplicate\":true"); }

    private static void str(StringBuilder b, String k, String v) {
        if (b.length() > 1) b.append(',');
        b.append('"').append(k).append("\":\"");
        String s = v == null ? "" : v;
        for (int n = 0; n < s.length(); n++) {
            char c = s.charAt(n);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
