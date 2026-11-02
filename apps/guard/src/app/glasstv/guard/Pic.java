package app.glasstv.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Выбор пользователя из приложения «Картинка» (app.glasstv.picture): четыре режима и четыре ползунка поверх фильма.
// Всё идёт через белый список Pq.java (lightSensor, dbc, localDimming, peak, colorTemp, lowBlueRay, backlight),
// всегда EN_APPLY_CURRENT и EN_TCL_ACT_EXEC: без сохранения, после перезагрузки ТВ вернёт сохранённое, а мы повторим выбор.
//
// ПРОТОКОЛ (получатель app.glasstv.guard/app.glasstv.guard.Cmd, extra do = pic, extra a = state | set | reset)
//   a=state (по умолчанию)  только прочитать, ничего не менять.
//   a=set                   записать и применить. Необязательные extras (числа можно слать и как --es):
//        preset     kino | day | hdr | soft | custom   пресет ставит свои числа; подсветка берётся под сигнал (SDR или HDR отдельно)
//        backlight  5..100   подсветка панели; ниже 5 не пускаем (чёрный экран, полосу уже не видно)
//        black      0..2     глубина чёрного (локальное затемнение): 0 выкл, 1 низкое, 2 высокое (в ТВ это 0, 1, 3)
//        peak       0..3     пиковая яркость: 0 выкл, 1 стандарт, 2 высокая (High-light), 3 максимум (High-dynamic); в ТВ 0, 2, 3, 4
//        warm       -5..5    цвет: 0 «Стандарт», минус холоднее, плюс теплее, +5 «Тёплый» (в ТВ лесенка WARM_TCL ниже)
//      Что не прислано, остаётся как было: выбор пользователя, если он держится, иначе то, что сейчас стоит на панели.
//   a=reset                 снять выбор: вернуть то, что было до него; дальше картинку снова ведёт автоматика.
// Ответ: упорядоченный broadcast, «Сторож» кладёт плоский JSON в resultData (resultCode = RESULT_OK), как у do=eq:
//     {"v":1,"preset":"kino","backlight":18,"black":2,"peak":1,"warm":5,"held":true,"until":"07:00","hdr":0,"svc":true,"st":"ok"}
//   preset     какому пресету совпадают ЖИВЫЕ числа с панели (для сигнала hdr), иначе custom
//   backlight, black, peak, warm   что стоит на панели сейчас (-1: не читается на этом ТВ)
//   held       выбор пользователя держится; until: до какой границы (07:00 или 22:00)
//   hdr        тип сигнала: 0 SDR, больше нуля HDR, -1 не читается. Из видеоинфо ТВ; если там 0, а режим картинки уже HDR-вариант
//              (у HDR10, HLG, Dolby свои номера режимов), берётся по режиму: Pq.picSig. Приложения-плееры могут отдавать видеоинфо с нулём
//   svc        служба «Сторожа» жива (false: выбор записан, применится при её запуске)
//   st         ok | nm («Ночной режим» ведёт подсветку сам: её не пишем, остальное да) | fail (ничего не читается)
//   Ответ на a=set уже после записи: Pq-очередь одна, ответ идёт следом за записью.
//
// ПРАВИЛО ПРИОРИТЕТА (чтобы выбор не дрался с автоматикой «Вечер» 22:00 и «День» 7:00 и ночной подсветкой)
//   1. Выбор держится до ближайшей границы 22:00 или 7:00 (те же, что у Pq.tick и у ночной подсветки). После неё он снимается сам.
//   2. Пока он держится: Pq.tick («Вечер» и «День») молчит, ход подсветки в GuardService (moveBacklight) не идёт.
//   3. На границе первый же вызов expireDue() снимает выбор и возвращает то, что стояло ДО него (снимок, взят до первой записи, на диске).
//      Только после этого работает автоматика. Поэтому после снятия всё выглядит так, будто «Картинку» не трогали: вечером ночной ход
//      Сторожа опустит подсветку, утром «День» вернёт дневное. Подсветка возвращается сразу, без плавного хода.
//   4. Выбор повторяется после пробуждения ТВ (TCL сама возвращает сохранённую картинку: проходы через 3, 6,5 и 12 секунд) и после перезагрузки.
//   5. Если «Ночной режим» (do=nm) ведёт подсветку, подсветку выбора не пишем, остальное применяем; когда он выключился, догоняем.
//   6. Тип сигнала при смене (SDR на HDR) не отслеживаем: ТВ сам подставляет сохранённое для нового режима. Открыть «Картинку» и нажать
//      OK на режиме: подсветка возьмётся под новый сигнал.
final class Pic {
    private Pic() { }

    static final String TAG = "guard";
    static final String[] IDS = {"kino", "day", "hdr", "soft"};
    static final String[] TITLES = {"Кино Вечер", "Дневной Чёткий", "Максимум HDR", "Мягкий для глаз"};

    // Значения режимов. Колонки: подсветка SDR, подсветка HDR, глубина чёрного 0..2, пик 0..3, тепло -5..5, синий фильтр (1 включить, -1 как было).
    // Подбор по источникам: RTINGS (калибровка TCL QM6K и QM7K: режим Movie, локальное затемнение High, пик High-Dynamic, цвет Warm),
    // заводской SDR Movie этого ТВ (локальное затемнение High, подсветка 45), гайд TrueLuminance для C7K (адаптивную яркость и энергосбережение
    // выключить). HDTVTest и AVForums для C6K, C655 и C7K в сети не нашлись. Пик: Standard вечером, High-light днём, High-dynamic в «Максимуме».
    // Тепло: +5 это «Тёплый» TCL (ближе всего к D65, как у RTINGS), 0 «Стандарт». Подсветка «Кино Вечер» в SDR: днём 18 (потолок «Вечера» Сторожа),
    // а ночью ночной уровень Сторожа (сейчас 11, с 27.10 темнее прежних 18), иначе выбор ночью был бы ярче, чем без «Картинки» (см. blOf).
    static final int[][] PRESETS = {
            {18, 40, 2, 1, 5, -1},      // Кино Вечер: тёплый, глубокий чёрный, мягкий пик (подсветка SDR ночью не 18, а ночной уровень: blOf)
            {75, 100, 2, 2, 0, -1},     // Дневной Чёткий: яркий, контрастный, нейтральная температура (0 «Стандарт»)
            {100, 100, 2, 3, 4, -1},    // Максимум HDR: подсветка и пик на максимум
            {11, 25, 1, 0, 5, 1}};      // Мягкий для глаз: темно, без пика, мягкое затемнение, синий фильтр

    static final int BL_MIN = 5, BL_MAX = 100;
    static final int[] FALLBACK = {45, 2, 1, 0};   // если живое значение не читается и в команде его нет: подсветка 45 (как днём), затемнение высокое, пик стандарт
    // Что на самом деле пишется в ТВ. localDimming: 2 «Настройки» TCL не пишут, у них 0, 1 и 3.
    // peak (EnPicture.PeakGear в «Настройках»): 0 OFF, 1 ON, 2 STANDARD, 3 HIGH_LIGHT, 4 HIGH_DYNAMIC; 1 не используем
    static final int[] BLACK_TCL = {0, 1, 3};
    static final int[] PEAK_TCL = {0, 2, 3, 4};
    // colorTemp = порядковый номер EnColorTmpType: 0 холодный, 1 стандарт, 2 тёплый, 5..8 «стандарт к холодному 1..4», 9..12 «к тёплому 1..4».
    // Лесенка от холодного к тёплому: -5 это 0, 0 это 1 (стандарт), +5 это 2. Порядок промежуточных ступеней выведен из имён, на глаз проверить
    static final int[] WARM_TCL = {0, 8, 7, 6, 5, 1, 9, 10, 11, 12, 2};

    // Порядок записи: всё, что перебивает подсветку, первым, подсветка последней. Возврат: подсветка уже вернулась, датчик света последним
    private static final String[] ORDER_APPLY = {"lightSensor", "dbc", "localDimming", "peak", "colorTemp", "lowBlueRay", "backlight"};
    private static final String[] ORDER_RESTORE = {"lowBlueRay", "colorTemp", "peak", "localDimming", "dbc", "lightSensor"};
    private static final String[] SNAP_KEYS = {"lightSensor", "dbc", "localDimming", "peak", "colorTemp", "lowBlueRay", "backlight"};

    private static final Object STATE = new Object();   // только для правки prefs, внутри нет вызовов службы TCL

    // ===== Граница периода =====

    // Начало текущего периода: последние 22:00 или 7:00 (в миллисекундах). Пока оно то же, выбор держится
    static long boundary() {
        Calendar c = Calendar.getInstance();
        int hr = c.get(Calendar.HOUR_OF_DAY);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (hr >= GuardService.NIGHT_FROM) c.set(Calendar.HOUR_OF_DAY, GuardService.NIGHT_FROM);
        else if (hr >= GuardService.NIGHT_TO) c.set(Calendar.HOUR_OF_DAY, GuardService.NIGHT_TO);
        else { c.add(Calendar.DAY_OF_MONTH, -1); c.set(Calendar.HOUR_OF_DAY, GuardService.NIGHT_FROM); }
        return c.getTimeInMillis();
    }

    // До какой границы держится выбор: ближайшая впереди
    static String until() {
        return String.format(Locale.US, "%02d:00", GuardService.isNight() ? GuardService.NIGHT_TO : GuardService.NIGHT_FROM);
    }

    static boolean held(SharedPreferences p) {
        return p.getBoolean("pic.on", false) && p.getLong("pic.bound", 0) == boundary();
    }

    // Выбранная подсветка, если выбор держится, иначе -1 (для мягкого включения)
    static int wantBacklight(SharedPreferences p) {
        return held(p) ? p.getInt("pic.bl", -1) : -1;
    }

    // ===== Снять выбор =====

    // Для главного потока: вызывать в начале каждой проверки по времени (ночь, «Вечер», пробуждение). Если граница прошла,
    // выбор снимается и возвращается то, что стояло до него. Подсветка здесь же и сразу, остальное в очереди Pq
    static void expireDue(Context c, SharedPreferences p) {
        if (!p.getBoolean("pic.on", false) || p.getLong("pic.bound", 0) == boundary()) return;
        end(c, p, "прошла граница " + GuardService.NIGHT_FROM + ":00 или " + GuardService.NIGHT_TO + ":00");
    }

    static void end(Context c, SharedPreferences p, String why) { end(c, p, why, false); }

    // inline=true: вернуть значения прямо здесь (для команды reset, она уже в очереди Pq и должна ответить после возврата).
    // Возвращаем только то, что мы писали и что до сих пор стоит нашим: если после нас параметр сдвинули (пультом, системой), он уже не наш
    private static void end(Context c, final SharedPreferences p, final String why, boolean inline) {
        final Map<String, Integer> snap = new LinkedHashMap<String, Integer>(), got = new LinkedHashMap<String, Integer>();
        final Set<String> wrote = new LinkedHashSet<String>();
        synchronized (STATE) {
            if (!p.getBoolean("pic.on", false)) return;
            for (String k : SNAP_KEYS) {
                if (p.contains("pic.snap." + k)) snap.put(k, p.getInt("pic.snap." + k, 0));
                if (p.contains("pic.got." + k)) got.put(k, p.getInt("pic.got." + k, 0));
            }
            for (String k : p.getString("pic.wrote", "").split(",")) if (!k.isEmpty()) wrote.add(k);
            clear(p);
        }
        GuardService g = GuardService.running;
        Integer bl = snap.get("backlight"), blGot = got.get("backlight");
        if (bl != null && wrote.contains("backlight") && (g == null || !g.nmActive()) && (blGot == null || blGot == Backlight.get()))
            Backlight.set(bl, false);   // сразу: дальше ночной ход Сторожа решает сам
        Runnable restore = () -> {
            StringBuilder sb = new StringBuilder();
            for (String k : ORDER_RESTORE) {
                Integer want = snap.get(k), gv = got.get(k);
                if (want == null || !wrote.contains(k)) continue;
                Integer live = Pq.picGet(k);
                if (live == null || live.equals(want) || (gv != null && !live.equals(gv))) continue;
                Integer after = Pq.picPut(k, want);
                sb.append(sb.length() > 0 ? ", " : "").append(k).append(' ').append(live).append("→").append(after == null ? "?" : after.toString());
            }
            final String line = "Картинка: выбор снят (" + why + (sb.length() > 0 ? "), возвращено: " + sb : "), на панели уже как было");
            Log.i(TAG, line);
            final GuardService gs = GuardService.running;
            if (gs != null) gs.h.post(() -> gs.log(line));
        };
        if (inline) restore.run(); else Pq.async(restore);
    }

    private static void clear(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("pic.")) e.remove(k);
        e.commit();
    }

    // ===== Запись =====

    // Записать выбор и применить. Выполняется в очереди Pq (не в главном потоке). Возвращает ответ JSON
    static String set(Context c, SharedPreferences p, String preset, int bl, int black, int peak, int warm) {
        int[] sg = Pq.picSig();
        boolean hdr = sg[1] > 0;
        String id = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        int[] in = {bl, black, peak, warm};
        boolean any = id.length() > 0 && !id.equals("custom");
        for (int x : in) if (x != SmartSound.NONE) any = true;
        if (any) {
            boolean holding = held(p);
            int[] v = holding ? new int[]{p.getInt("pic.bl", 0), p.getInt("pic.black", 0), p.getInt("pic.peak", 0), p.getInt("pic.warm", 0)} : live();
            for (int i = 0; i < 4; i++) if (v[i] < -5 || (v[i] < 0 && i < 3)) v[i] = FALLBACK[i];   // не читается: берём середину, а не нижний край
            int lbr = holding ? p.getInt("pic.lbr", -1) : -1;
            for (int i = 0; i < IDS.length; i++) {
                if (!IDS[i].equals(id)) continue;
                v = new int[]{blOf(i, hdr), PRESETS[i][2], PRESETS[i][3], PRESETS[i][4]};
                lbr = PRESETS[i][5];
            }
            for (int i = 0; i < 4; i++) if (in[i] != SmartSound.NONE) v[i] = clamp(i, in[i]);
            for (int i = 0; i < 4; i++) v[i] = clamp(i, v[i]);
            synchronized (STATE) {
                p.edit().putBoolean("pic.on", true).putLong("pic.bound", boundary()).putLong("pic.t", System.currentTimeMillis())
                        .putInt("pic.bl", v[0]).putInt("pic.black", v[1]).putInt("pic.peak", v[2]).putInt("pic.warm", v[3]).putInt("pic.lbr", lbr)
                        .commit();
            }
            GuardService g = GuardService.running;
            if (g != null) g.h.post(g::stopRamp);   // ночной ход подсветки не должен принять нашу запись за пульт
            String r = applyNow(c, p, "выбор в «Картинке»");
            if (r != null && g != null) { final String line = "Картинка: " + r; g.h.post(() -> g.log(line)); }
        } else if (held(p)) {
            applyNow(c, p, "повтор по запросу");
        }
        return json(c, p);
    }

    // Снять выбор по команде: значения возвращаются здесь же, ответ показывает уже вернувшуюся картинку
    static String reset(Context c, SharedPreferences p) {
        end(c, p, "команда reset", true);
        return json(c, p);
    }

    // Подсветка режима i под сигнал. «Кино Вечер» в SDR ночью идёт на ночном уровне Сторожа (его же можно поменять в настройках Сторожа),
    // днём остаётся 18. Остальные берутся из таблицы как есть
    static int blOf(int i, boolean hdr) {
        if (i == 0 && !hdr && GuardService.isNight()) {
            GuardService g = GuardService.running;
            return clamp(0, g != null ? g.nightLevel() : GuardService.NIGHT_LEVEL);
        }
        return PRESETS[i][hdr ? 1 : 0];
    }

    static int clamp(int i, int x) {
        switch (i) {
            case 0: return Math.max(BL_MIN, Math.min(BL_MAX, x));
            case 1: return Math.max(0, Math.min(2, x));
            case 2: return Math.max(0, Math.min(3, x));
            default: return Math.max(-5, Math.min(5, x));
        }
    }

    // Применить выбор к панели: пишем только то, что отличается от живого значения. Возвращает строку для журнала или null, если менять нечего.
    static String applyNow(Context c, SharedPreferences p, String why) {
        if (!held(p)) return null;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return null;   // экран спит: дождёмся включения
        GuardService g = GuardService.running;
        boolean nm = g != null && g.nmActive();
        Map<String, Integer> snap = snapshot(c, p);
        Map<String, Integer> want = new LinkedHashMap<String, Integer>();
        want.put("lightSensor", 0);   // иначе датчик света перебивает ползунок подсветки
        want.put("dbc", 0);           // «Энергосбережение» тоже
        want.put("localDimming", BLACK_TCL[clamp(1, p.getInt("pic.black", 0))]);
        want.put("peak", PEAK_TCL[clamp(2, p.getInt("pic.peak", 0))]);
        want.put("colorTemp", WARM_TCL[clamp(3, p.getInt("pic.warm", 0)) + 5]);
        int lbr = p.getInt("pic.lbr", -1);
        // Синий фильтр: свой только у «Мягкого». У остальных режимов он как был до выбора, но возвращаем его только пока он стоит нашим
        // (мы включили его в «Мягком» и после нас его не сдвигали). Если пользователь включил или выключил фильтр руками, это уже его (см. ours)
        Integer lbrWant = lbr == 1 ? Integer.valueOf(1) : snap.get("lowBlueRay");
        if (lbrWant != null) want.put("lowBlueRay", lbrWant);
        if (!nm) want.put("backlight", clamp(0, p.getInt("pic.bl", BL_MIN)));
        StringBuilder sb = new StringBuilder();
        for (String k : ORDER_APPLY) {
            Integer w = want.get(k);
            if (w == null) continue;
            Integer live = Pq.picGet(k);
            if (live == null) { Log.i(TAG, "pic: " + k + " не читается на этом ТВ, не пишу"); continue; }
            if (live.equals(w)) continue;
            if (k.equals("lowBlueRay") && lbr != 1 && !ours(p, k, live)) continue;   // фильтр не наш (руками): не трогаем
            markWrote(p, k);   // на диск ДО записи: если процесс упадёт, снятие выбора всё равно вернёт этот параметр
            Integer after = Pq.picPut(k, w);
            p.edit().putInt("pic.got." + k, after != null ? after : w).apply();   // что встало: по снятию возвращаем, только если оно так и осталось
            sb.append(sb.length() > 0 ? ", " : "").append(k).append(' ').append(live).append("→").append(after == null ? "?" : after.toString());
        }
        if (nm) Log.i(TAG, "pic: «Ночной режим» ведёт подсветку, её не пишу");
        return sb.length() == 0 ? null : why + ": " + sb;
    }

    // Параметр до сих пор стоит нашим: мы его писали (pic.wrote) и живое равно тому, что встало после нашей записи (pic.got)
    private static boolean ours(SharedPreferences p, String k, int live) {
        synchronized (STATE) {
            if (!(',' + p.getString("pic.wrote", "") + ',').contains("," + k + ",")) return false;
            return !p.contains("pic.got." + k) || p.getInt("pic.got." + k, 0) == live;
        }
    }

    private static void markWrote(SharedPreferences p, String k) {
        synchronized (STATE) {
            Set<String> w = new LinkedHashSet<String>();
            for (String x : p.getString("pic.wrote", "").split(",")) if (!x.isEmpty()) w.add(x);
            if (w.add(k)) p.edit().putString("pic.wrote", String.join(",", w)).commit();
        }
    }

    // Снимок значений ДО первой записи выбора (на диске). После перезагрузки ТВ живое равно сохранённому, поэтому снимок берётся заново:
    // иначе по снятию мы вернули бы то, чего после перезагрузки уже нет
    private static Map<String, Integer> snapshot(Context c, SharedPreferences p) {
        int boot = Pq.bootId(c);
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        synchronized (STATE) {
            if (p.getBoolean("pic.snapOk", false) && p.getInt("pic.snapBoot", -2) == boot) {
                for (String k : SNAP_KEYS) if (p.contains("pic.snap." + k)) m.put(k, p.getInt("pic.snap." + k, 0));
                return m;
            }
        }
        SharedPreferences.Editor e = p.edit();
        for (String k : SNAP_KEYS) {
            Integer v = Pq.picGet(k);
            e.remove("pic.got." + k);
            if (v == null) { e.remove("pic.snap." + k); continue; }
            m.put(k, v);
            e.putInt("pic.snap." + k, v);
        }
        e.putBoolean("pic.snapOk", true).putInt("pic.snapBoot", boot).putString("pic.wrote", "");
        synchronized (STATE) { e.commit(); }
        Log.i(TAG, "pic: снимок до выбора " + m);
        return m;
    }

    // Повтор выбора (пробуждение, запуск службы, конец «Ночного режима»): в очереди Pq, в журнал только если что-то записалось
    static void reapply(final Context c, final SharedPreferences p, final String why) {
        if (!held(p)) return;
        Pq.async(() -> {
            String r = applyNow(c, p, why);
            GuardService g = GuardService.running;
            if (r != null && g != null) { final String line = "Картинка: " + r; g.h.post(() -> g.log(line)); }
        });
    }

    // ===== Ответ =====

    // Живые числа в шкале «Картинки»: {подсветка, чёрный, пик, тепло}, -1 там, где не читается
    static int[] live() {
        Integer bl = Pq.picGet("backlight"), ld = Pq.picGet("localDimming"), pk = Pq.picGet("peak"), ct = Pq.picGet("colorTemp");
        int[] v = {bl == null ? -1 : bl, ld == null ? -1 : ld <= 0 ? 0 : ld >= 3 ? 2 : 1, pk == null ? -1 : pk <= 0 ? 0 : pk >= 4 ? 3 : pk == 3 ? 2 : 1, 0};
        if (ct != null) for (int i = 0; i < WARM_TCL.length; i++) if (WARM_TCL[i] == ct) v[3] = i - 5;
        return v;
    }

    static int presetOf(int[] v, boolean hdr) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (v[0] == blOf(i, hdr) && v[1] == PRESETS[i][2] && v[2] == PRESETS[i][3] && v[3] == PRESETS[i][4]) return i;
        }
        return -1;
    }

    static String json(Context c, SharedPreferences p) {
        int[] v = live();
        int hdr = Pq.picSig()[1];
        int pi = presetOf(v, hdr > 0);
        GuardService g = GuardService.running;
        boolean held = held(p), nm = g != null && g.nmActive();
        String st = v[0] < 0 && v[1] < 0 && v[2] < 0 ? "fail" : nm ? "nm" : "ok";
        return String.format(Locale.US, "{\"v\":1,\"preset\":\"%s\",\"backlight\":%d,\"black\":%d,\"peak\":%d,\"warm\":%d,\"held\":%b,\"until\":\"%s\",\"hdr\":%d,\"svc\":%b,\"st\":\"%s\"}",
                pi < 0 ? "custom" : IDS[pi], v[0], v[1], v[2], v[3], held, until(), hdr, g != null, st);
    }
}
