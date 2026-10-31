package app.glasstv.picture;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

// Модель полосы «Картинка» и разговор со «Сторожем». Настоящую картинку (подсветка, затемнение, пик, цвет) пишет «Сторож»
// (Pic.java в приложении «Сторож», там же полный протокол и правило приоритета над «Вечером» и «Днём»), это приложение
// только интерфейс: шлёт команды и читает состояние. Приложение не знает чисел ТВ: ступени и режимы оно называет своими номерами.
//
// Запрос: явный broadcast на app.glasstv.guard/.Cmd, extra do = pic, extra a = state | set.
//   a=state                   только прочитать
//   a=set + preset            режим: kino | day | hdr | soft; числа и подсветку под сигнал (SDR или HDR) подставляет «Сторож»
//   a=set + backlight, black, peak, warm   ползунки (целые), можно только изменённые: остальное остаётся как было
// Ответ приходит в resultData упорядоченной рассылки плоским JSON:
//   {"v":1,"preset":"kino","backlight":18,"black":2,"peak":1,"warm":5,"held":true,"until":"07:00","hdr":0,"svc":true,"st":"ok"}
//   Числа это то, что стоит на панели сейчас, preset совпадение живых чисел с режимом (иначе custom), held выбор держится до границы until
//   (22:00 или 07:00, потом снова автоматика), st: ok | nm (подсветку ведёт «Ночной режим») | fail (ТВ не отдаёт настройки)
// Проверка с ПК: adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pic --es a set --es preset kino
final class Pic {
    private Pic() { }

    static final String GUARD_PKG = "app.glasstv.guard", GUARD_CMD = "app.glasstv.guard.Cmd";

    // Индексы ползунков (порядок везде один)
    static final int BL = 0, BLACK = 1, PEAK = 2, WARM = 3;
    static final String[] KEYS = {"backlight", "black", "peak", "warm"};
    static final String[] NAMES = {"Подсветка панели", "Глубина чёрного", "Контраст / Пик HDR", "Тёплый оттенок"};
    static final int[] MIN = {5, 0, 0, -5};
    static final int[] MAX = {100, 2, 3, 5};

    // Последнее место «Авто»: снять выбор, картинку снова ведёт автоматика «Сторожа» (иначе выбор держится до 22:00 или 7:00)
    static final String[] IDS = {"kino", "day", "hdr", "soft", "auto"};
    static final String[] TITLES = {"Кино Вечер", "Дневной Чёткий", "Максимум HDR", "Мягкий для глаз", "Авто"};
    static final String[] BLACK_NAMES = {"Выкл", "Мягкая", "Глубокая"};
    static final String[] PEAK_NAMES = {"Выкл", "Стандарт", "Высокая", "Максимум"};

    // Пока «Сторож» не ответил: середина шкал (как у «Дня»)
    static final int[] START = {45, 2, 1, 0};

    static int clamp(int i, int x) { return Math.max(MIN[i], Math.min(MAX[i], x)); }

    // Подпись числа на ползунке: подсветка числом, затемнение и пик словами, тепло со знаком (настоящий минус)
    static String show(int i, int x) {
        switch (i) {
            case BLACK: return BLACK_NAMES[clamp(i, x)];
            case PEAK: return PEAK_NAMES[clamp(i, x)];
            case WARM: return x > 0 ? "+" + x : x < 0 ? "−" + (-x) : "0";
            default: return String.valueOf(x);
        }
    }

    static int presetIndex(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    // ===== Ответ «Сторожа» =====

    static final class State {
        final int[] v = START.clone();
        boolean has;                       // числа пришли (иначе -1: на этом ТВ не читаются)
        int preset = -1, hdr = -1;
        boolean held, svc = true;
        String until = "", st = "ok";
    }

    // Разбор плоского JSON без org.json (так его можно проверить обычной Java на ПК). null: ответа нет или он не наш
    static State parse(String j) {
        if (j == null || !j.startsWith("{") || !"1".equals(raw(j, "v"))) return null;
        try {
            State s = new State();
            String x;
            for (int i = 0; i < 4; i++) {
                String r = raw(j, KEYS[i]);
                int n = r == null ? Integer.MIN_VALUE : Integer.parseInt(r);
                if (n >= MIN[i]) { s.v[i] = clamp(i, n); s.has = true; }     // -1 у подсветки, чёрного и пика: на этом ТВ не читается, остаётся стартовое
            }
            if ((x = raw(j, "preset")) != null) s.preset = presetIndex(x);
            if ((x = raw(j, "held")) != null) s.held = "true".equals(x);
            if ((x = raw(j, "until")) != null) s.until = x;
            if ((x = raw(j, "hdr")) != null) s.hdr = Integer.parseInt(x);
            if ((x = raw(j, "svc")) != null) s.svc = "true".equals(x);
            if ((x = raw(j, "st")) != null) s.st = x;
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Значение ключа из плоского JSON: число, true/false или строка без кавычек (запятых в наших значениях нет)
    static String raw(String j, String k) {
        String key = "\"" + k + "\":";
        int i = j.indexOf(key);
        if (i < 0) return null;
        i += key.length();
        int e = i;
        while (e < j.length() && ",}".indexOf(j.charAt(e)) < 0) e++;
        String s = j.substring(i, e).trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') s = s.substring(1, s.length() - 1);
        return s;
    }

    // ===== Обмен =====

    interface Reply { void got(State s); }     // s == null: «Сторож» не ответил

    static void read(Context c, Handler h, Reply r) { ask(c, h, r, null, null, 0); }

    // Режим целиком: числа выбирает «Сторож»
    static void setPreset(Context c, Handler h, Reply r, String id) { ask(c, h, r, id, null, 0); }

    // Ползунки: mask это биты изменённых (1 << индекс), остальное «Сторож» оставляет как было
    static void setValues(Context c, Handler h, Reply r, int[] v, int mask) { ask(c, h, r, null, v, mask); }

    private static void ask(Context c, Handler h, final Reply r, String preset, int[] v, int mask) {
        try {
            Intent i = new Intent().setClassName(GUARD_PKG, GUARD_CMD).putExtra("do", "pic");
            if ("auto".equals(preset)) {
                i.putExtra("a", "reset");
            } else if (preset != null) {
                i.putExtra("a", "set").putExtra("preset", preset);
            } else if (v != null) {
                i.putExtra("a", "set");
                for (int k = 0; k < 4; k++) if ((mask & (1 << k)) != 0) i.putExtra(KEYS[k], v[k]);
            } else {
                i.putExtra("a", "state");
            }
            // Упорядоченная рассылка: ответ «Сторожа» (setResultData) придёт в onReceive. Контекст приложения, не окна:
            // полосу могут закрыть раньше, чем придёт ответ
            c.getApplicationContext().sendOrderedBroadcast(i, null, new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent in) {
                    r.got(getResultCode() == Activity.RESULT_OK ? parse(getResultData()) : null);
                }
            }, h, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException e) {
            r.got(null);
        }
    }
}
