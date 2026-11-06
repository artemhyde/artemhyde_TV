package app.glasstv.sound;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

// Модель панели и разговор со «Сторожем». Эффекты звука живут в «Стороже» (он работает всегда),
// это приложение только интерфейс: шлёт команды и читает состояние.
//
// ПРОТОКОЛ «Звук» и «Сторож» (получатель app.glasstv.guard/app.glasstv.guard.AppCmd, действие do=eq)
//
// Запрос: явный broadcast на компонент app.glasstv.guard/.AppCmd (вход для своих приложений, право app.glasstv.permission.GUARD;
// с ПК тот же разбор через .Cmd), extra do = eq, extra a = state | set.
//   a=state (по умолчанию)  только прочитать, ничего не менять.
//   a=set                   записать и применить. Необязательные extras (целые числа):
//                             preset  строка: kino | music | night | clean | custom
//                             de      0..12    чёткость речи (Dolby «deall», чёткость диалогов для всех форматов)
//                             vl      0..10    выравнивание взрывов и шёпота (Dolby vlstrength и сила компрессора DynamicsProcessing)
//                             bass    -5..+5   глубина баса, ступень до 140 Гц, 1,5 дБ на шаг
//                             treble  -5..+5   прозрачность, ступени 4-10 кГц, 1 дБ на шаг
//                           Что не прислано, остаётся как было. Если прислан только preset, значения берутся из таблицы
//                           пресетов «Сторожа». Значения вне диапазона обрезаются. preset=clean всегда даёт нули:
//                           эффект выключается, слой Dolby (чёткость и выравнивание) уходит в нейтраль.
//                           Все пять величин «Сторож» сам сохраняет в SharedPreferences «guard», переживают перезагрузку.
// Ответ: запрос шлётся УПОРЯДОЧЕННЫМ broadcast (sendOrderedBroadcast), «Сторож» кладёт ответ в resultData (resultCode = RESULT_OK).
//   Это плоский JSON, строка вида
//     {"v":1,"preset":"kino","de":7,"vl":6,"bass":0,"treble":0,"on":true,"src":"eq","svc":true,"fx":"ok"}
//   preset  какой пресет совпадает с числами, иначе custom (определяет «Сторож» по числам, метке из запроса он не верит)
//   de, vl, bass, treble  что реально играет сейчас (после «Чистого» или выключения «Умного звука» там нули)
//   on      слои звука включены (false = «Чистый»: эффект снят, Dolby в нейтрали)
//   src     eq: управляет эта панель; level: ещё работает прежний «Умный звук» по уровням, числа приближённые
//   svc     служба «Сторожа» жива (false: числа записаны, применятся при её запуске)
//   fx      состояние эффекта на сессии 0: ok | off | busy (держит другое приложение) | fail | wait | none (службы нет)
//   Ответ на a=set показывает уже ЗАПИСАННЫЕ числа, но состояние fx взято до применения: эффект перестраивается чуть позже,
//   в потоке «Сторожа» (150 мс после последнего нажатия мы уже подождали сами, поэтому лишнего не копится).
//   Если «Сторож» не установлен или не отвечает, resultData пуст: панель говорит «Сторож не отвечает».
// Проверка с ПК (так же, как делает панель):
//   adb shell am broadcast -n app.glasstv.guard/.Cmd --es do eq                                   прочитать (ответ в консоли: data=...)
//   adb shell am broadcast -n app.glasstv.guard/.Cmd --es do eq --es a set --es preset night       пресет целиком
//   adb shell am broadcast -n app.glasstv.guard/.Cmd --es do eq --es a set --ei de 9 --ei bass -2   отдельные величины
final class Eq {
    private Eq() { }

    static final String GUARD_PKG = "app.glasstv.guard", GUARD_CMD = "app.glasstv.guard.AppCmd";

    // Индексы величин: речь, выравнивание, бас, детали (порядок везде один и тот же)
    static final int DE = 0, VL = 1, BASS = 2, TREBLE = 3;
    // С 27.10 подписи короткие (полоса стала тоньше): что именно делает величина, описано в протоколе выше
    static final String[] NAMES = {"Чёткость речи", "Выравнивание", "Глубина баса", "Прозрачность"};
    static final int[] MIN = {0, 0, -5, -5};
    static final int[] MAX = {12, 10, 5, 5};

    // Пресеты. Таблица такая же в «Стороже» (SmartSound.PRESETS): так работает и команда с одним preset
    static final String[] IDS = {"kino", "music", "night", "clean"};
    static final String[] TITLES = {"Кино", "Музыка", "Ночной", "Чистый"};
    static final String[] HINTS = {"чёткие диалоги и без громких взрывов", "глубокий бас и сцена",
            "максимальное сжатие громких звуков", "как задумано (всё выключено)"};
    static final int[][] VALUES = {{7, 6, 0, 0}, {0, 0, 3, 2}, {9, 10, -2, 0}, {0, 0, 0, 0}};

    static final int CUSTOM = -1;

    // Какой пресет совпадает с числами: его номер или CUSTOM («Свой»)
    static int presetOf(int[] v) {
        for (int i = 0; i < VALUES.length; i++) if (java.util.Arrays.equals(VALUES[i], v)) return i;
        return CUSTOM;
    }

    static int clamp(int i, int x) { return Math.max(MIN[i], Math.min(MAX[i], x)); }

    // Подпись числа на ползунке: 7, +3, −2 (настоящий минус)
    static String show(int i, int x) {
        if (MIN[i] >= 0) return String.valueOf(x);
        return x > 0 ? "+" + x : x < 0 ? "−" + (-x) : "0";
    }

    // ===== Ответ «Сторожа» =====

    static final class State {
        final int[] v = {7, 6, 0, 0};
        String preset = "kino", src = "eq", fx = "none";
        boolean on = true, svc = true;
    }

    // Разбор плоского JSON без org.json (так его можно проверить обычной Java на ПК). null: ответа нет или он не наш
    static State parse(String j) {
        if (j == null || !j.startsWith("{") || !"1".equals(raw(j, "v"))) return null;
        try {
            State s = new State();
            String x;
            s.v[DE] = clamp(DE, Integer.parseInt(raw(j, "de")));
            s.v[VL] = clamp(VL, Integer.parseInt(raw(j, "vl")));
            s.v[BASS] = clamp(BASS, Integer.parseInt(raw(j, "bass")));
            s.v[TREBLE] = clamp(TREBLE, Integer.parseInt(raw(j, "treble")));
            if ((x = raw(j, "preset")) != null) s.preset = x;
            if ((x = raw(j, "src")) != null) s.src = x;
            if ((x = raw(j, "fx")) != null) s.fx = x;
            if ((x = raw(j, "on")) != null) s.on = "true".equals(x);
            if ((x = raw(j, "svc")) != null) s.svc = "true".equals(x);
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

    // Прочитать состояние
    static void read(Context c, Handler h, Reply r) { ask(c, h, r, null, null); }

    // Записать все пять величин; preset = id пресета или "custom"
    static void set(Context c, Handler h, Reply r, String preset, int[] v) { ask(c, h, r, preset, v); }

    private static void ask(Context c, Handler h, final Reply r, String preset, int[] v) {
        try {
            Intent i = new Intent().setClassName(GUARD_PKG, GUARD_CMD).putExtra("do", "eq");
            if (v != null) {
                i.putExtra("a", "set").putExtra("preset", preset)
                        .putExtra("de", v[DE]).putExtra("vl", v[VL]).putExtra("bass", v[BASS]).putExtra("treble", v[TREBLE]);
            } else {
                i.putExtra("a", "state");
            }
            // Упорядоченная рассылка: ответ «Сторожа» (setResultData) придёт в onReceive. Контекст приложения, не окна:
            // панель могут закрыть раньше, чем придёт ответ
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
