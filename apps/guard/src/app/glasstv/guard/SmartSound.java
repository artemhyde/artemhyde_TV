package app.glasstv.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

// «Умный звук»: выравниватель громкости на DynamicsProcessing для всего звука, который идёт через микшер Android
// (YouTube, Twitch, браузер, музыка, часть фильмов). Тихие голоса громче, громкие взрывы тише.
// Цепочка внутри эффекта (порядок задан прошивкой): входное усиление, предварительный эквалайзер (у нас выключен),
// компрессор на 3 полосы, эквалайзер после него, лимитер. Эффект вешается на сессию 0 (общий микшер) с правом
// MODIFY_AUDIO_SETTINGS. Сессия 0 в Android считается устаревшей, но работает; ею одновременно владеет одно приложение.
//
// Всё живёт в одном рабочем потоке «smartsound»: создание эффекта делает десятки обращений к audioserver,
// и обратные вызовы AudioEffect приходят в тот поток, где эффект создан.
// Настройки в SharedPreferences «guard»: smartOn (включено), smartLevel (0 мягко, 1 обычно, 2 ночь),
// smartMode (0 сессия 0, 1 запасной путь по сессиям плееров), smartTune (ручные поправки «ключ=значение,...»).
// Панель «Звук» (приложение app.glasstv.sound, протокол в Cmd.java, команда do=eq) задаёт звук пятью числами: eqOn (панель
// ведёт звук вместо уровней), eqDe, eqVl, eqBass, eqTreble. Пока eqOn выключен, всё работает по уровням, как раньше.
final class SmartSound {
    interface Say { void say(String s); }

    static final String P_ON = "smartOn", P_LEVEL = "smartLevel", P_MODE = "smartMode", P_TUNE = "smartTune";
    static final int MODE_GLOBAL = 0, MODE_SESSIONS = 1;
    static final int ST_OFF = 0, ST_OK = 1, ST_NO_CONTROL = 2, ST_FAIL = 3, ST_WAIT = 4;
    static final String[] LEVEL_NAMES = {"мягко", "обычно", "ночь"};

    // Выше обычных приложений (у них 0), но ниже Int.MAX_VALUE, которое берёт Equalizer314: так два приложения не воюют.
    // Правило AudioFlinger: новый владелец с равным приоритетом отбирает управление, с меньшим не отбирает.
    static final int PRIORITY = 1_000_000_000;
    private static final String TAG = "guard";
    private static final long WATCHDOG_MS = 30_000;
    private static final int MAX_SESSIONS = 8;
    private static final UUID TYPE_DP = UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e");

    // ===== Параметры звука =====
    // Три полосы компрессора: низ до x1, середина x1..x2, верх x2..20000 Гц. Для полосы:
    //   thr   порог, дБ от полной шкалы (уровень полосы по среднеквадратичному, не пик)
    //   ratio отношение N:1 выше порога, knee мягкое колено в дБ
    //   boost подъём полосы ПОСЛЕ компрессии, дБ (preGain в движке двигает только детектор, а не сам звук)
    //   attack, release в мс (движок считает их по шагам 5,3 мс при кадре 10 мс)
    //   gate, expand: ниже gate дБ сигнал ещё и «расширяется» (против подъёма шипения)
    // Нулевое усиление полосы там, где boost + (1/ratio - 1) * (уровень - thr) = 0.
    // Строки те же, что в sim_dp.py: правка там и здесь идёт вместе.
    static final String BASE =
            "x1=300,x2=3400,frame=10,gain=-2,eq=1,"
            + "low.thr=-34,low.boost=0,low.ratio=3,low.knee=8,low.attack=8,low.release=300,low.gate=-70,low.expand=1,"
            + "mid.thr=-30,mid.boost=6,mid.ratio=3,mid.knee=10,mid.attack=12,mid.release=250,mid.gate=-55,mid.expand=1.3,"
            + "high.thr=-36,high.boost=1,high.ratio=2.5,high.knee=8,high.attack=6,high.release=160,high.gate=-55,high.expand=1.3,"
            + "lim.thr=-6,lim.ratio=10,lim.attack=2,lim.release=100,lim.post=0";
    static final String[] LEVELS = {
            "low.thr=-30,low.ratio=2,mid.boost=4,mid.ratio=2,high.boost=0,high.ratio=2",
            "",
            "low.thr=-38,low.ratio=4,mid.thr=-34,mid.boost=8,mid.ratio=4,high.thr=-38,high.ratio=3,lim.thr=-8"};
    // Эквалайзер после компрессора: ступени (верхняя граница, Гц) и подъём, дБ. Движок держит каждую ступень плоской
    // и не сглаживает стыки, поэтому границы частые. На шаге 94 Гц (кадр 10 мс) ступени уже 140 Гц не различить.
    // Ступень 10000 (была 12000) нужна панели «Звук»: «детали» это ступени 4000-5500, 5500-8000 и 8000-10000 Гц, «бас» это ступень до 140 Гц.
    // Прежний звук от этого не меняется: у обеих ступеней подъём 0 дБ.
    static final float[] EQ_EDGES = {140, 280, 560, 1000, 1500, 2200, 3000, 4000, 5500, 8000, 10000, 20000};
    static final float[] EQ_GAINS = {-2, -1, 0, 0, 1.5f, 2.5f, 2.5f, 2.5f, 1, 0, 0, 0};

    // ===== Панель «Звук» (приложение app.glasstv.sound; протокол: Cmd.java, do=eq) =====
    // Пять величин: чёткость речи 0..12 (Dolby deall), выравнивание 0..10 (Dolby vlstrength и сила компрессора), бас и детали -5..+5.
    // Порядок везде: {речь, выравнивание, бас, детали}. Таблица пресетов такая же в приложении (Eq.VALUES).
    static final String P_EQ = "eqOn", P_EQ_DE = "eqDe", P_EQ_VL = "eqVl", P_EQ_BASS = "eqBass", P_EQ_TREBLE = "eqTreble";
    static final String[] PRESET_IDS = {"kino", "music", "night", "clean"};
    static final int[][] PRESETS = {{7, 6, 0, 0}, {0, 0, 3, 2}, {9, 10, -2, 0}, {0, 0, 0, 0}};
    static final int[] EQ_MIN = {0, 0, -5, -5}, EQ_MAX = {12, 10, 5, 5};
    static final int NONE = Integer.MIN_VALUE;            // число в команде не прислано
    static final float BASS_DB = 1.5f, TREBLE_DB = 1.0f;  // дБ на один шаг ползунка баса и деталей

    // Сила компрессора по выравниванию vl (0..10): опорные точки и значения в них, между точками линейно.
    // Точки 3, 5 и 8 это прежние уровни «мягко», «обычно», «ночь» (LEVELS), им же соответствуют выравнивания 3, 5 и 8 у Dolby (DOLBY).
    // vl 0: компрессор прозрачен (отношение 1, подъёма нет), лимитер только страхует от перегруза. vl 10: сильнее «ночи».
    // На слух не подбиралось: подгонять поправками --es tune, они поверх этой таблицы.
    static final int[] VL_AT = {0, 3, 5, 8, 10};
    static final String[] VL_KEYS = {"low.thr", "low.ratio", "mid.thr", "mid.boost", "mid.ratio", "mid.expand",
            "high.thr", "high.boost", "high.ratio", "high.expand", "lim.thr"};
    static final float[][] VL_TAB = {
            {-34, -30, -34, -38, -42},          // low.thr
            {1, 2, 3, 4, 5},                    // low.ratio
            {-30, -30, -30, -34, -38},          // mid.thr
            {0, 4, 6, 8, 10},                   // mid.boost
            {1, 2, 3, 4, 5},                    // mid.ratio
            {1, 1.3f, 1.3f, 1.3f, 1.3f},        // mid.expand
            {-36, -36, -36, -38, -42},          // high.thr
            {0, 0, 1, 1, 1},                    // high.boost
            {1, 2, 2.5f, 3, 4},                 // high.ratio
            {1, 1.3f, 1.3f, 1.3f, 1.3f},        // high.expand
            {-1.5f, -6, -6, -8, -10}};          // lim.thr

    // Чистая часть без Android: разбор строки, пределы, сборка конфигурации движка
    static final class Curve {
        final Map<String, Float> v = new HashMap<>();
        final float[] extra;                     // добавка эквалайзера от панели «Звук» (бас, детали), дБ по ступеням; у прежних уровней null

        Curve(int level, String over) {
            this(LEVELS[Math.max(0, Math.min(LEVELS.length - 1, level))], null, over);
        }

        // Режим панели «Звук»: e = {речь, выравнивание, бас, детали}
        Curve(int[] e, String over) {
            this(eqCurve(e), eqExtra(e), over);
        }

        private Curve(String mid, float[] extra, String over) {
            parse(BASE, v);
            parse(mid, v);
            parse(over, v);
            this.extra = extra;
        }

        // Строка поправок к BASE по панели. Компрессор: опорные точки VL_TAB по выравниванию. Входное усиление: те же -2 дБ запаса,
        // что у прежних уровней, но только пока компрессор работает (vl 5 и выше полностью), плюс запас под подъём баса и деталей.
        // Ключ eq масштабирует прежний «голосовой» изгиб эквалайзера (EQ_GAINS): речь 7 даёт его как был, речь 0 даёт ровный эквалайзер.
        static String eqCurve(int[] e) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < VL_KEYS.length; k++)
                b.append(k > 0 ? "," : "").append(VL_KEYS[k]).append('=').append(String.format(Locale.US, "%.2f", lerp(VL_TAB[k], e[1])));
            float boost = Math.max(0f, Math.max(e[2] * BASS_DB, e[3] * TREBLE_DB));
            b.append(String.format(Locale.US, ",gain=%.2f,eq=%.2f", 0f - (2f * Math.min(1f, e[1] / 5f) + 0.5f * boost), Math.min(1.7f, e[0] / 7f)));
            return b.toString();
        }

        static float lerp(float[] row, int vl) {
            int x = Math.max(0, Math.min(10, vl));
            for (int i = 0; i + 1 < VL_AT.length; i++)
                if (x <= VL_AT[i + 1]) return row[i] + (row[i + 1] - row[i]) * (x - VL_AT[i]) / (float) (VL_AT[i + 1] - VL_AT[i]);
            return row[row.length - 1];
        }

        // Бас: ступени до 140 Гц. Детали: ступени выше 4000 и до 10000 Гц
        static float[] eqExtra(int[] e) {
            float[] x = new float[EQ_EDGES.length];
            for (int i = 0; i < x.length; i++) {
                if (EQ_EDGES[i] <= 140) x[i] = e[2] * BASS_DB;
                else if (EQ_EDGES[i] > 4000 && EQ_EDGES[i] <= 10000) x[i] = e[3] * TREBLE_DB;
            }
            return x;
        }

        static void parse(String s, Map<String, Float> m) {
            if (s == null) return;
            for (String kv : s.split(",")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                String k = kv.substring(0, i).trim();
                try {
                    float x = Float.parseFloat(kv.substring(i + 1).trim());
                    if (Float.isNaN(x)) continue;       // «NaN» проходит Math.min/max насквозь и дошёл бы до движка (проверка verify, ошибка 3)
                    m.put(k, clamp(k, x));
                } catch (NumberFormatException ignored) { }
            }
        }

        static float c(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }

        // Пределы защищают от опечатки в ручной поправке: лишние +30 дБ дали бы хрип на весь дом
        static float clamp(String k, float x) {
            boolean lim = k.startsWith("lim.");
            switch (k.substring(k.lastIndexOf('.') + 1)) {
                case "thr": return lim ? c(x, -24, 0) : c(x, -90, 0);
                case "boost": return c(x, -12, 12);
                case "ratio": return c(x, 1, lim ? 50 : 20);
                case "knee": return c(x, 0, 24);
                case "attack": return lim ? c(x, 0.5f, 100) : c(x, 1, 200);
                case "release": return c(x, 10, 3000);
                case "gate": return c(x, -120, 0);
                case "expand": return c(x, 1, 10);
                case "post": return c(x, -6, 6);
                case "x1": return c(x, 60, 1000);
                case "x2": return c(x, 1200, 12000);
                case "gain": return c(x, -12, 6);
                case "eq": return c(x, 0, 2);
                case "frame": return c(x, 5, 40);       // кадр = задержка эффекта: 40 мс даёт блок 2048 (≈43 мс), больше уже рассинхрон с картинкой
                default: return x;
            }
        }

        float f(String k) {
            Float x = v.get(k);
            return x == null ? 0f : x;
        }

        DynamicsProcessing.Mbc mbc() {
            DynamicsProcessing.Mbc m = new DynamicsProcessing.Mbc(true, true, 3);
            String[] nm = {"low.", "mid.", "high."};
            float[] cut = {f("x1"), f("x2"), 20000f};
            for (int i = 0; i < 3; i++) {
                String n = nm[i];
                m.setBand(i, new DynamicsProcessing.MbcBand(true, cut[i], f(n + "attack"), f(n + "release"), f(n + "ratio"),
                        f(n + "thr"), f(n + "knee"), f(n + "gate"), f(n + "expand"), 0f, f(n + "boost")));
            }
            return m;
        }

        // Подъём ступени, дБ: прежний изгиб на eq плюс добавка панели. Предел защищает от опечатки в поправке
        float gain(int i) { return c(EQ_GAINS[i] * f("eq") + (extra == null ? 0f : extra[i]), -12f, 9f); }

        DynamicsProcessing.Eq postEq() {
            DynamicsProcessing.Eq e = new DynamicsProcessing.Eq(true, true, EQ_EDGES.length);
            for (int i = 0; i < EQ_EDGES.length; i++)
                e.setBand(i, new DynamicsProcessing.EqBand(true, EQ_EDGES[i], gain(i)));
            return e;
        }

        DynamicsProcessing.Limiter limiter() {
            return new DynamicsProcessing.Limiter(true, true, 0, f("lim.attack"), f("lim.release"), f("lim.ratio"), f("lim.thr"), f("lim.post"));
        }

        // Единственный вариант, который есть в прошивке: частотный (VARIANT_FAVOR_TIME_RESOLUTION в движке не реализован)
        DynamicsProcessing.Config config(int channels) {
            DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, channels,
                    false, 0,                 // предварительный эквалайзер не нужен
                    true, 3,                  // компрессор на 3 полосы
                    true, EQ_EDGES.length,    // эквалайзер после компрессора
                    true);                    // лимитер
            b.setPreferredFrameDuration(f("frame"));
            b.setInputGainAllChannelsTo(f("gain"));
            b.setMbcAllChannelsTo(mbc());
            b.setPostEqAllChannelsTo(postEq());
            b.setLimiterAllChannelsTo(limiter());
            return b.build();
        }
    }

    // ===== Состояние (трогать только из рабочего потока, кроме помеченного volatile) =====
    private final Context ctx;
    private final SharedPreferences p;
    private final Say say;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("smartsound");
    private Handler h;                                                   // не final: на него ссылаются лямбды в инициализаторах полей
    private DynamicsProcessing global;                                   // эффект на сессии 0
    private final Map<Integer, DynamicsProcessing> bySession = new HashMap<>();  // запасной путь
    private boolean started, devCbUp, rxUp, lostLogged;
    private volatile boolean dead;
    private int failures;
    private final AtomicBoolean eqQueued = new AtomicBoolean();     // в очереди уже есть применение настроек панели
    private int dDe = -1, dMode = -1, dStr = -1;                    // что последний раз записали в службу Dolby (-1: не знаем)
    private float appliedFrame = -1;
    private String sinks = "";
    private volatile int state = ST_OFF;
    private volatile String status = "выключен";

    SmartSound(Context c, SharedPreferences prefs, Say say) {
        this.ctx = c;
        this.p = prefs;
        this.say = say;
        this.am = c.getSystemService(AudioManager.class);
        thread.start();
        h = new Handler(thread.getLooper());
    }

    // ===== Что видят снаружи =====

    static boolean wanted(SharedPreferences p) { return p.getBoolean(P_ON, false); }

    boolean isWanted() { return wanted(p); }

    int state() { return state; }

    String status() { return status; }

    int level() { return Math.max(0, Math.min(LEVELS.length - 1, p.getInt(P_LEVEL, 1))); }

    int mode() { return p.getInt(P_MODE, MODE_GLOBAL) == MODE_SESSIONS ? MODE_SESSIONS : MODE_GLOBAL; }

    // Любое исключение в рабочем потоке уронило бы весь процесс Сторожа (отладка по сети, записки), поэтому всё идёт через safe()
    private Runnable safe(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.w(TAG, "умный звук: сбой в рабочем потоке", t);
                setState(ST_FAIL, "сбой: " + t);
            }
        };
    }

    private void post(Runnable r) { if (!dead) h.post(safe(r)); }

    // Вызвать при старте службы: если пользователь включал, эффект встанет сам
    void start() { post(this::applyWanted); }

    void setEnabled(boolean on) {
        // Панель «Звук» стоит на «Чистом»: простое включение ничего бы не изменило, поэтому возвращаем «Кино»
        if (on && eqActive(p) && allZero(eqVals(p))) eqSave(p, "kino", NONE, NONE, NONE, NONE);
        p.edit().putBoolean(P_ON, on).apply();
        post(() -> {
            applyWanted();
            tell(on ? "Умный звук включён: " + status : "Умный звук выключен");
        });
    }

    // Явная команда уровня возвращает прежний режим по уровням (панель «Звук» перестаёт вести звук)
    void setLevel(int level) {
        p.edit().putInt(P_LEVEL, Math.max(0, Math.min(LEVELS.length - 1, level))).putBoolean(P_EQ, false).apply();
        post(this::reapply);
        post(() -> { if (wanted(p)) dolby(true); });
    }

    void setMode(int mode) {
        p.edit().putInt(P_MODE, mode == MODE_SESSIONS ? MODE_SESSIONS : MODE_GLOBAL).apply();
        post(this::applyWanted);
    }

    // Ручные поправки для подбора на слух: "mid.boost=7,low.thr=-36". Пустая строка или слово reset сбрасывает все поправки
    void tune(String kv) {
        post(() -> {
            Map<String, Float> m = new HashMap<>();
            if (kv != null && !kv.trim().isEmpty() && !kv.trim().equals("reset")) {
                Curve.parse(p.getString(P_TUNE, ""), m);
                Curve.parse(kv, m);
            }
            List<String> keys = new ArrayList<>(m.keySet());
            Collections.sort(keys);
            StringBuilder b = new StringBuilder();
            for (String k : keys) b.append(b.length() > 0 ? "," : "").append(k).append('=').append(m.get(k));
            p.edit().putString(P_TUNE, b.toString()).apply();
            reapply();
        });
    }

    // Полная пересборка эффекта (после смены выхода это делается само)
    void rebuild() { post(() -> { if (started) attachAll("вручную"); }); }

    // Быстрая проверка через пару секунд (например, когда телевизор включили из ожидания)
    void verifySoon() {
        if (dead) return;
        h.removeCallbacks(verifyTask);
        h.postDelayed(verifyTask, 3_000);
        // После пробуждения TCL заново заливает свои настройки звука: слой Dolby ставим поверх
        h.postDelayed(() -> { if (!dead && wanted(p)) dolby(true); }, 6_000);
    }

    // Подробности для журнала: что реально стоит в движке (чтение обратно)
    void dump() {
        post(() -> {
            Log.i(TAG, "умный звук: " + status + " | уровень " + levelText() + ", режим " + (mode() == MODE_GLOBAL ? "сессия 0" : "сессии плееров")
                    + ", поправки «" + p.getString(P_TUNE, "") + "»");
            if (global != null) Log.i(TAG, "умный звук, сессия 0: " + readback(global));
            for (Map.Entry<Integer, DynamicsProcessing> e : bySession.entrySet())
                Log.i(TAG, "умный звук, сессия " + e.getKey() + ": " + readback(e.getValue()));
        });
    }

    // ===== Панель «Звук» =====

    static boolean eqActive(SharedPreferences p) { return p.getBoolean(P_EQ, false); }

    static int eqClamp(int i, int x) { return Math.max(EQ_MIN[i], Math.min(EQ_MAX[i], x)); }

    static boolean allZero(int[] v) {
        for (int x : v) if (x != 0) return false;
        return true;
    }

    // Номер пресета, чьи числа совпали, или -1 («Свой»)
    static int presetOf(int[] v) {
        for (int i = 0; i < PRESETS.length; i++) if (java.util.Arrays.equals(PRESETS[i], v)) return i;
        return -1;
    }

    // Сохранённые числа панели (если ещё не задавали, это «Кино»)
    static int[] eqVals(SharedPreferences p) {
        return new int[]{eqClamp(0, p.getInt(P_EQ_DE, PRESETS[0][0])), eqClamp(1, p.getInt(P_EQ_VL, PRESETS[0][1])),
                eqClamp(2, p.getInt(P_EQ_BASS, PRESETS[0][2])), eqClamp(3, p.getInt(P_EQ_TREBLE, PRESETS[0][3]))};
    }

    // Что играет на самом деле: нули, если «Умный звук» выключен; числа панели, если ведёт она; иначе приближение прежнего уровня
    static int[] eqEffective(SharedPreferences p) {
        if (!wanted(p)) return new int[4];
        if (eqActive(p)) return eqVals(p);
        int[] d = DOLBY[Math.max(0, Math.min(DOLBY.length - 1, p.getInt(P_LEVEL, 1)))];
        return new int[]{d[0], d[1], 0, 0};
    }

    // Целое из extra команды (--ei 5 или --es 5, «+3» и «−2» тоже); NONE, если не прислано или не число
    static int intExtra(Intent i, String k) {
        if (!i.hasExtra(k)) return NONE;
        Object o = i.getExtras().get(k);
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim().replace('−', '-'));
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    // Записать настройки панели (из главного потока, команда do=eq a=set) и вернуть записанные числа.
    // preset: kino | music | night | clean | custom | null. Известный пресет ставит свои числа, присланные числа поверх них
    // (NONE: не прислано, остаётся то, что играет сейчас). «Чистый» это всегда нули. Все нули выключают «Умный звук»
    // (эффект снят, Dolby в нейтрали), любые другие числа включают. Применяет eqChanged()
    static synchronized int[] eqSave(SharedPreferences p, String preset, int de, int vl, int bass, int treble) {
        int[] v = eqEffective(p);
        String id = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < PRESET_IDS.length; i++) if (PRESET_IDS[i].equals(id)) v = PRESETS[i].clone();
        int[] in = {de, vl, bass, treble};
        for (int i = 0; i < 4; i++) if (in[i] != NONE) v[i] = eqClamp(i, in[i]);
        if ("clean".equals(id)) v = new int[4];
        p.edit().putBoolean(P_EQ, true).putInt(P_EQ_DE, v[0]).putInt(P_EQ_VL, v[1]).putInt(P_EQ_BASS, v[2]).putInt(P_EQ_TREBLE, v[3])
                .putBoolean(P_ON, !allZero(v)).apply();
        return v;
    }

    // Ответ на do=eq: плоский JSON, поля описаны в Cmd.java. s может быть null (службы нет): тогда fx "none", svc false
    static String eqJson(SharedPreferences p, SmartSound s) {
        int[] v = eqEffective(p);
        int pi = presetOf(v);
        String fx = "none";
        if (s != null) {
            switch (s.state()) {
                case ST_OK: fx = "ok"; break;
                case ST_NO_CONTROL: fx = "busy"; break;
                case ST_FAIL: fx = "fail"; break;
                case ST_WAIT: fx = "wait"; break;
                default: fx = "off";
            }
        }
        return String.format(Locale.US, "{\"v\":1,\"preset\":\"%s\",\"de\":%d,\"vl\":%d,\"bass\":%d,\"treble\":%d,\"on\":%b,\"src\":\"%s\",\"svc\":%b,\"fx\":\"%s\"}",
                pi < 0 ? "custom" : PRESET_IDS[pi], v[0], v[1], v[2], v[3], wanted(p), eqActive(p) ? "eq" : "level", s != null, fx);
    }

    // Подпись текущих настроек для строк состояния и журнала
    String levelText() {
        if (!eqActive(p)) return "«" + LEVEL_NAMES[level()] + "»";
        int[] e = eqVals(p);
        return "панели «Звук»: речь " + e[0] + ", выравнивание " + e[1] + ", бас " + e[2] + ", детали " + e[3];
    }

    // Настройки панели сохранены (eqSave): применить в рабочем потоке. Много быстрых команд подряд сливаются в одну, она читает самое свежее
    void eqChanged() {
        if (eqQueued.compareAndSet(false, true)) post(this::applyEqNow);
    }

    private void applyEqNow() {
        eqQueued.set(false);
        if (dead) return;
        if (!wanted(p)) {
            // «Чистый» или все нули: эффект снять, слой Dolby в нейтраль (явно, даже если мы его раньше не включали)
            teardown();
            dolbyNeutral();
            p.edit().putBoolean("smartDolby", false).apply();
            setState(ST_OFF, "выключен (чистый звук)");
            return;
        }
        if (!started) { applyWanted(); return; }       // эффекта не было: полный запуск, создание эффекта и слой Dolby целиком
        reapply();                                     // новые числа в тот же эффект, без пересоздания
        dolbyWrite(dolbyTarget(), false);
        p.edit().putBoolean("smartDolby", true).apply();
    }

    // Освободить всё и закрыть поток. Выбор пользователя в настройках не трогает
    void shutdown() {
        dead = true;
        h.removeCallbacksAndMessages(null);
        h.post(safe(() -> teardown()));
        h.post(() -> thread.quitSafely());
    }

    // ===== Рабочий поток =====

    private Curve curve() {
        String tune = p.getString(P_TUNE, "");
        return eqActive(p) ? new Curve(eqVals(p), tune) : new Curve(level(), tune);
    }

    private void applyWanted() {
        if (dead) return;
        if (wanted(p)) {
            if (!started) {
                started = true;
                sinks = sinkKey();
                try {
                    am.registerAudioDeviceCallback(devCb, h);
                    devCbUp = true;
                } catch (Throwable t) { Log.w(TAG, "умный звук: слежение за выходами не включилось", t); }
                h.postDelayed(watchdog, WATCHDOG_MS);
            }
            attachAll("запуск");
            dolby(true);
        } else {
            teardown();
            dolby(false);
            setState(ST_OFF, "выключен");
        }
    }

    // Второй слой, в самом звуковом модуле ТВ: скрытая служба Dolby (DolbyProbe) пишет в MS12, в тот же Dap,
    // что обрабатывает звук динамиков. Чёткость диалогов для всех форматов и выравнивание громкости, проверено
    // по журналу HAL 26.10 (AQ_DolbyDialogEnhancer, DapUiLevelerAmount). Держится до перезагрузки или смены звука
    // в меню TCL, поэтому ставится заново при включении экрана. Выключение возвращает нейтральное (0 и выкл)
    static final int[][] DOLBY = {{4, 3}, {6, 5}, {8, 8}};   // по уровням: чёткость диалогов 0..12, выравнивание 0..10

    private void dolby(boolean on) {
        try {
            if (on) {
                dolbyWrite(dolbyTarget(), true);
            } else if (p.getBoolean("smartDolby", false)) {
                dolbyNeutral();
            }
            p.edit().putBoolean("smartDolby", on).apply();
        } catch (Throwable e) {
            Log.w(TAG, "умный звук: служба Dolby не ответила", e);
        }
    }

    // Что писать в службу Dolby: {чёткость диалогов, режим выравнивания 0/1, сила выравнивания или -1 «не писать»}.
    // Панель «Звук»: речь = deall, выравнивание = vlstrength (при 0 выравнивание выключено). Иначе по уровню из DOLBY
    private int[] dolbyTarget() {
        if (eqActive(p)) {
            int[] e = eqVals(p);
            return new int[]{e[0], e[1] > 0 ? 1 : 0, e[1] > 0 ? e[1] : -1};
        }
        int[] v = DOLBY[Math.min(DOLBY.length - 1, level())];
        return new int[]{v[0], 1, v[1]};
    }

    // Записать в Dolby. force: всё заново (после пробуждения ТВ TCL сам перезаливает свои настройки). Без force пишем только то,
    // что изменилось с прошлой записи: ползунок панели двигают часто, а каждая запись это обращение к службе
    private void dolbyWrite(int[] t, boolean force) {
        if (force || t[0] != dDe) dDe = dolbySet("deall", t[0]) ? t[0] : -1;
        if (t[2] >= 0 && (force || t[2] != dStr)) dStr = dolbySet("vlstrength", t[2]) ? t[2] : -1;
        if (force || t[1] != dMode) dMode = dolbySet("vlmode", t[1]) ? t[1] : -1;
    }

    // Нейтраль (то же, что делает выключение «Умного звука»): чёткость 0, выравнивание выкл. DRC и downmix не трогаем
    private void dolbyNeutral() {
        dDe = dolbySet("deall", 0) ? 0 : -1;
        dMode = dolbySet("vlmode", 0) ? 0 : -1;
        dStr = -1;
    }

    private boolean dolbySet(String key, int val) {
        String r = DolbyProbe.run(ctx, "set", key, String.valueOf(val), null);
        return r != null && r.contains("OK (0)");
    }

    // Выключить слежение, освободить все эффекты
    private void teardown() {
        started = false;
        h.removeCallbacks(watchdog);
        h.removeCallbacks(retry);
        h.removeCallbacks(verifyTask);
        h.removeCallbacks(devChanged);
        if (devCbUp) {
            try { am.unregisterAudioDeviceCallback(devCb); } catch (Throwable ignored) { }
            devCbUp = false;
        }
        dropReceiver();
        releaseAll();
        failures = 0;
    }

    private void releaseAll() {
        release(global);
        global = null;
        for (DynamicsProcessing d : bySession.values()) release(d);
        bySession.clear();
        lostLogged = false;
    }

    private static void release(DynamicsProcessing d) {
        if (d == null) return;
        try { d.setEnabled(false); } catch (Throwable ignored) { }
        try { d.release(); } catch (Throwable ignored) { }
    }

    // Пересобрать всё по текущему режиму
    private void attachAll(String why) {
        if (dead || !started) return;
        h.removeCallbacks(retry);
        releaseAll();
        if (mode() == MODE_GLOBAL) {
            dropReceiver();
            attachGlobal(why);
        } else {
            raiseReceiver();
            setState(ST_WAIT, "режим сессий: жду, пока плеер объявит сессию (Just Player делает это сам)");
        }
    }

    // Мягкое применение новых настроек: без пересоздания, если кадр не менялся
    private void reapply() {
        if (dead || !started) return;
        float frame = curve().f("frame");
        if (frame != appliedFrame) { attachAll("смена настроек"); return; }
        if (global != null) pushOrRebuild(global);
        for (DynamicsProcessing d : new ArrayList<>(bySession.values())) pushOrRebuild(d);
    }

    private void pushOrRebuild(DynamicsProcessing d) {
        try {
            if (!d.hasControl()) return;      // чужое управление: вернётся само, и controlCb запишет настройки заново
            push(d);
        } catch (Throwable t) {
            Log.w(TAG, "умный звук: запись настроек не удалась, пересобираю", t);
            attachAll("запись не удалась");
        }
    }

    private DynamicsProcessing create(int session) {
        Curve c = curve();
        int ch = 2;                                   // микшер ТВ стерео (маска 0x3 в dumpsys)
        DynamicsProcessing d = new DynamicsProcessing(PRIORITY, session, c.config(ch));
        try {
            int real = d.getChannelCount();
            if (real != ch) {                         // если микшер не стерео, собрать конфигурацию под его число каналов
                d.release();
                d = null;
                d = new DynamicsProcessing(PRIORITY, session, c.config(real));
            }
            appliedFrame = c.f("frame");
            d.setControlStatusListener(controlCb);
            d.setEnableStatusListener(enableCb);
            return d;
        } catch (RuntimeException e) {
            release(d);                               // null пропускается
            throw e;
        }
    }

    // Записать все ступени заново (после возврата управления: пока оно было чужим, в движке остались чужие настройки)
    private void push(DynamicsProcessing d) {
        Curve c = curve();
        DynamicsProcessing.Mbc mbc = c.mbc();
        DynamicsProcessing.Eq eq = c.postEq();
        DynamicsProcessing.Limiter lim = c.limiter();
        int ch = d.getChannelCount();
        for (int i = 0; i < ch; i++) {
            d.setInputGainbyChannel(i, c.f("gain"));
            d.setMbcByChannelIndex(i, mbc);
            d.setPostEqByChannelIndex(i, eq);
            d.setLimiterByChannelIndex(i, lim);
        }
        d.setEnabled(true);
    }

    private void attachGlobal(String why) {
        DynamicsProcessing d = null;
        try {
            d = create(0);
            int r = d.setEnabled(true);
            global = d;
            failures = 0;
            if (!d.hasControl()) {
                setState(ST_NO_CONTROL, "эффект на сессии 0 есть, но управление у другого приложения (Equalizer314, Wavelet или похожее)");
            } else if (r != AudioEffect.SUCCESS) {
                setState(ST_FAIL, "эффект создан, но не включился (код " + r + ")");
            } else {
                setState(ST_OK, "работает на сессии 0, уровень " + levelText() + ", id " + d.getId());
            }
            Log.i(TAG, "умный звук: " + status + " (" + why + ")");
        } catch (Throwable t) {
            release(d);
            global = null;
            failures++;
            long delay = Math.min(60_000L, 5_000L << Math.min(failures, 4));
            String why2 = t.getClass().getSimpleName() + ": " + t.getMessage();
            boolean dpOk = hasDpInFirmware();
            // Если сессию 0 держит приложение с большим приоритетом, эффект «создаётся» без управления, и первая же запись
            // параметров в конструкторе DynamicsProcessing отвергается (INVALID_OPERATION → UnsupportedOperationException).
            // Ветка «нет управления» выше до этого не доходит, поэтому узнаём её по исключению (проверка verify, ошибка 2)
            if (t instanceof UnsupportedOperationException && dpOk)
                setState(ST_NO_CONTROL, "не удалось взять управление: скорее всего сессию 0 держит приложение с большим приоритетом (Equalizer314, Wavelet), повтор через " + delay / 1000 + " с");
            else
                setState(ST_FAIL, "не создался (" + why2 + "), следующая попытка через " + delay / 1000 + " с" + (dpOk ? "" : "; в прошивке нет dynamics_processing"));
            Log.w(TAG, "умный звук: не создался (" + why + ")", t);
            if (failures == 3) tell("Умный звук не встаёт на сессию 0: " + why2 + ". Запасной путь: --es do sound --ei mode 1");
            h.removeCallbacks(retry);
            h.postDelayed(retry, delay);
        }
    }

    private boolean hasDpInFirmware() {
        try {
            AudioEffect.Descriptor[] all = AudioEffect.queryEffects();
            if (all != null) for (AudioEffect.Descriptor d : all) if (TYPE_DP.equals(d.type)) return true;
            return false;
        } catch (Throwable t) { return true; }       // не смогли спросить: не считаем, что эффекта нет
    }

    private final Runnable retry = safe(() -> {
        if (started && mode() == MODE_GLOBAL && global == null) attachGlobal("повтор");
    });

    // Раз в 30 с: эффект жив, управление у нас, включён
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (dead || !started) return;
            try { verify(); } catch (Throwable t) { Log.w(TAG, "умный звук: проверка упала", t); }
            h.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable verifyTask = safe(() -> {
        if (started) verify();
    });

    private void verify() {
        if (mode() != MODE_GLOBAL) {
            for (Iterator<Map.Entry<Integer, DynamicsProcessing>> it = bySession.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, DynamicsProcessing> e = it.next();
                if (!alive(e.getValue())) { release(e.getValue()); it.remove(); }
            }
            return;
        }
        if (global == null) return;                                // ждёт свой повтор
        if (!alive(global)) {                                      // audioserver перезапускался или эффект отвалился
            attachGlobal("эффект пропал");
            return;
        }
        if (!global.hasControl()) {
            setState(ST_NO_CONTROL, "управление у другого приложения");     // не воюем: вернётся само, когда оно уйдёт
            return;
        }
        if (!global.getEnabled()) pushOrRebuild(global);
        else if (state != ST_OK) setState(ST_OK, "работает на сессии 0, уровень " + levelText() + ", id " + global.getId());
    }

    // Обращение к audioserver: у умершего эффекта оно бросает исключение
    private static boolean alive(DynamicsProcessing d) {
        try {
            d.getInputGainByChannelIndex(0);
            return true;
        } catch (Throwable t) { return false; }
    }

    // ===== Потеря и возврат управления =====

    private final AudioEffect.OnControlStatusChangeListener controlCb = (effect, granted) -> post(() -> {
        if (dead || !started || !(effect instanceof DynamicsProcessing)) return;
        DynamicsProcessing d = (DynamicsProcessing) effect;
        if (d != global && !bySession.containsValue(d)) return;         // сигнал от уже освобождённого эффекта
        if (granted) {
            lostLogged = false;
            try { push(d); } catch (Throwable t) { Log.w(TAG, "умный звук: после возврата управления запись не удалась", t); return; }
            if (d == global) setState(ST_OK, "управление вернулось, уровень " + levelText());
            tell("Умный звук: управление звуком вернулось");
        } else {
            if (d == global) setState(ST_NO_CONTROL, "управление перехватило другое приложение");
            if (!lostLogged) { lostLogged = true; tell("Умный звук: сессию 0 перехватило другое приложение"); }
        }
    });

    // Если кто-то выключил нашу копию (при наличии управления), включаем обратно
    private final AudioEffect.OnEnableStatusChangeListener enableCb = (effect, enabled) -> post(() -> {
        if (dead || !started || enabled || !(effect instanceof DynamicsProcessing)) return;
        DynamicsProcessing d = (DynamicsProcessing) effect;
        if (d != global && !bySession.containsValue(d)) return;
        try {
            if (d.hasControl()) d.setEnabled(true);
        } catch (Throwable ignored) { }
    });

    // ===== Смена аудиовыхода =====

    private final AudioDeviceCallback devCb = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { devicesMoved(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { devicesMoved(); }
    };

    private void devicesMoved() {
        h.removeCallbacks(devChanged);
        h.postDelayed(devChanged, 1_500);         // HDMI и Bluetooth дребезжат подключением
    }

    private final Runnable devChanged = safe(() -> {
        String now = sinkKey();
        if (!started || now.equals(sinks)) return;
        sinks = now;
        if (mode() == MODE_GLOBAL) attachAll("смена выхода: " + now);
        else verify();                                     // плеер заново сессию не объявит, поэтому не сносим
    });

    private String sinkKey() {
        List<String> l = new ArrayList<>();
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) l.add(d.getType() + ":" + d.getAddress());
        } catch (Throwable ignored) { }
        Collections.sort(l);
        return l.toString();
    }

    // ===== Запасной путь: плеер сам объявляет сессию (Just Player шлёт ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) =====

    private void raiseReceiver() {
        if (rxUp) return;
        IntentFilter f = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        try {
            // отправляют чужие приложения, поэтому RECEIVER_EXPORTED; события идут в рабочий поток
            ctx.registerReceiver(sessionRx, f, null, h, Context.RECEIVER_EXPORTED);
            rxUp = true;
        } catch (Throwable t) { Log.w(TAG, "умный звук: приёмник сессий не встал", t); }
    }

    private void dropReceiver() {
        if (!rxUp) return;
        try { ctx.unregisterReceiver(sessionRx); } catch (Throwable ignored) { }
        rxUp = false;
    }

    private final BroadcastReceiver sessionRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                handle(c, i);
            } catch (Throwable t) { Log.w(TAG, "умный звук: сбой приёмника сессий", t); }
        }

        private void handle(Context c, Intent i) {
            if (dead || !started || mode() != MODE_SESSIONS) return;
            int sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE);
            String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (sid <= 0 || c.getPackageName().equals(pkg)) return;
            boolean open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction());
            DynamicsProcessing old = bySession.remove(sid);
            release(old);
            if (!open) { refreshSessionState(); return; }
            if (bySession.size() >= MAX_SESSIONS) return;
            DynamicsProcessing d = null;
            try {
                d = create(sid);
                d.setEnabled(true);
                bySession.put(sid, d);
                Log.i(TAG, "умный звук: сессия " + sid + " от " + pkg + ", управление " + d.hasControl());
            } catch (Throwable t) {
                release(d);
                Log.w(TAG, "умный звук: на сессию " + sid + " не встал", t);
            }
            refreshSessionState();
        }
    };

    private void refreshSessionState() {
        if (bySession.isEmpty()) setState(ST_WAIT, "режим сессий: жду, пока плеер объявит сессию");
        else setState(ST_OK, "работает на сессиях плееров: " + bySession.keySet() + ", уровень " + levelText());
    }

    // ===== Мелочи =====

    private void setState(int st, String text) {
        state = st;
        status = text;
    }

    // Строка в журнал экрана «Сторож» (из главного потока: журнал пишет префы не атомарно)
    private void tell(String s) { main.post(() -> say.say(s)); }

    private String readback(DynamicsProcessing d) {
        try {
            DynamicsProcessing.Config c = d.getConfig();
            StringBuilder b = new StringBuilder(String.format(Locale.US, "id=%d управление=%b включён=%b каналов=%d кадр=%.0f мс",
                    d.getId(), d.hasControl(), d.getEnabled(), d.getChannelCount(), c.getPreferredFrameDuration()));
            DynamicsProcessing.Mbc m = c.getMbcByChannelIndex(0);
            for (int i = 0; i < m.getBandCount(); i++) {
                DynamicsProcessing.MbcBand mb = m.getBand(i);
                b.append(String.format(Locale.US, "\n  полоса %d: до %.0f Гц, порог %.1f, отношение %.1f, подъём %.1f, атака %.0f, спад %.0f",
                        i, mb.getCutoffFrequency(), mb.getThreshold(), mb.getRatio(), mb.getPostGain(), mb.getAttackTime(), mb.getReleaseTime()));
            }
            DynamicsProcessing.Limiter l = c.getLimiterByChannelIndex(0);
            b.append(String.format(Locale.US, "\n  лимитер: порог %.1f, отношение %.1f; вход %.1f дБ", l.getThreshold(), l.getRatio(), c.getInputGainByChannelIndex(0)));
            return b.toString();
        } catch (Throwable t) { return "чтение не удалось: " + t; }
    }
}
