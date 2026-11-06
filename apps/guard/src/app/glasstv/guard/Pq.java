package app.glasstv.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Картинка телевизора TCL через библиотеку прошивки com.tcl.tv.pq (всё отражением, как в Backlight.java).
// ПРАВКИ ПО ИТОГАМ ПРОВЕРКИ: (1) «День» возвращает только то, что вечер собирался писать (planned), а не все 8 параметров;
// (2) контекст картинки = режим + тип сигнала + номер входа (option 14); (3) ореолы (option 20) убраны из профиля по умолчанию.
//  1) dump():  снимок настроек картинки, только геттеры. JSON пишется в files/pq-<время>.json и в logcat (тег guard).
//  2) apply("evening"): профиль «Вечер» через белый список из 8 сеттеров, всегда EN_APPLY_CURRENT и EN_TCL_ACT_EXEC
//     (без сохранения: после перезагрузки вернётся сохранённое). Перед первой записью сам делает снимок «Дня».
//  3) restore() / apply("day"): вернуть снимок.
//  4) tick(): то же по времени для GuardService, с правилом «не спорить с пультом».
//  5) picGet/picPut/picSig (27.10): тот же белый список для приложения «Картинка» (Pic.java), пока выбор пользователя держится, tick() молчит.
// Окно (первый int) везде 0. Ответ -2 значит «не поддерживается», -1000 ошибка: такие параметры не пишем.
// ЗАПРЕЩЕНО звать (в этом файле их нет и быть не должно): setFacPMode, setFacWarmMode, setBypassStatus, dumpThalParam,
// setHotLoad, setOptionStream, setStrOnoff, setCountry, setDolbyCfgPath, get/setGammaData, setCustGammaCurveData,
// setPairing, importCalFile, exportCalFile, deleteCalFile, setWindowMute, setDisplayWindow, setOverScan*,
// setWhiteBalancePattern, setBacklightConstant, setMuteConstant, resetPQFunction, setPQDemoMode, TvPqDebugManager, TvDDCManager.
final class Pq {
    private Pq() { }

    static final String TAG = "guard";
    private static final String PQ = "com.tcl.tv.pq.";
    private static final int WIN = 0;                          // главное окно
    private static final int OPT_SUPPORT = 9, SUP_PEAK = 24;   // getOption(9, 24, ""): "1" значит пиковая яркость поддерживается
    private static final int OPT_ENERGY = 12, OPT_DEVICE = 14, OPT_PEAK = 19, OPT_HALO = 20;
    private static final String HALO_TAG = "com.android.tv.settings";

    // ===== Настройки модуля =====
    static final boolean SDR_ONLY = false;                      // «Вечер» и для HDR: вечером пики HDR не должны слепить (подсветку HDR не трогаем, её ведёт ночь Сторожа)
    static final boolean RESPECT_REMOTE_AT_TRANSITION = false;  // true: на переходе в «День» не трогать то, что менял пульт
    static final int MIN_BACKLIGHT = 5;                         // профиль не опускает подсветку ниже (защита от чёрного экрана)
    static int RAMP_MS = 45_000;                                // подсветка меняется плавно за это время; 0 = сразу
    private static final int RAMP_STEPS = 24;

    // Профиль «Вечер»: имя параметра -> значение. Раскомментировать по желанию:
    //   localDimming 1 (Low: меньше «облаков» вокруг ярких предметов в тёмной комнате), lowBlueRay 1 (синий фильтр)
    static final Map<String, Integer> EVENING = new LinkedHashMap<String, Integer>();
    static {
        EVENING.put("lightSensor", 0);   // «Адаптивная яркость» выкл: иначе перебивает ползунок подсветки
        EVENING.put("dbc", 0);           // «Энергосбережение» выкл: иначе режет яркость и «дышит» подсветкой
        EVENING.put("backlight", 18);    // потолок «Вечера» (он подсветку только опускает); днём 45. Ночью её ведёт Сторож на NIGHT_LEVEL (с 27.10 это 11), см. skip
        EVENING.put("peak", 2);          // «Пиковая яркость»: ступень 2 «стандарт» вместо High-Dynamic
        // ПРАВКА ПРОВЕРКИ: ореолы (option 20) не в профиле по умолчанию. В «Настройках» этой прошивки у setLDMHaloFunction/isLDMHaloOpen
        // нет ни одного вызова (ни в TvSettings, ни в шести других системных APK (cfg, FactorySettings, gb, live, Quick_Panel, tss)), «не сохранять» для option 20 в API нет, а после
        // перезагрузки посреди вечера «День» запомнил бы включённые ореолы как исходные. Сначала проба: pq apply --es p only --es set "halo=1".
        // EVENING.put("halo", 1);          // подавление ореолов вкл
        EVENING.put("colorTemp", 2);     // «Тёплый» (EN_COLOR_TMPE_WARM)
        // EVENING.put("localDimming", 1);
        // EVENING.put("lowBlueRay", 1);
    }
    // Эти параметры «Вечер» только опускает, никогда не поднимает выше дневного значения
    private static final Set<String> ONLY_LOWER = new LinkedHashSet<String>(Arrays.asList("backlight", "peak"));

    // ===== Белый список сеттеров: ровно 8 параметров =====
    private interface Rd { Integer get() throws Exception; }
    private interface Wr { boolean put(int v) throws Exception; }

    private static final class Param {
        final String key; final int min, max; final Rd rd; final Wr wr;
        Param(String key, int min, int max, Rd rd, Wr wr) { this.key = key; this.min = min; this.max = max; this.rd = rd; this.wr = wr; }
    }

    private static final Class<?>[] NONE = new Class<?>[0];
    private static final Class<?>[] I1 = {int.class};
    private static final Class<?>[] OPT3 = {int.class, int.class, String.class};

    // Порядок записи «Вечера»: сначала то, что перебивает подсветку, подсветка последней (она идёт плавно)
    private static final String[] ORDER_APPLY = {"lightSensor", "dbc", "localDimming", "peak", "halo", "colorTemp", "lowBlueRay", "backlight"};
    // Порядок возврата: подсветка первой, датчик света последним (иначе он сразу перебьёт подсветку)
    private static final String[] ORDER_RESTORE = {"backlight", "lowBlueRay", "colorTemp", "halo", "peak", "localDimming", "dbc", "lightSensor"};
    private static final Map<String, Param> PARAMS = new LinkedHashMap<String, Param>();
    static {
        reg(new Param("lightSensor", 0, 1,
                () -> nn(call(bl(), "getLightSensorOnoff", NONE)),
                v -> (Boolean) call(bl(), "setLightSensorOnoff", new Class<?>[]{int.class, act()}, v, actExec())));
        reg(new Param("dbc", 0, 6,
                () -> nn(call(bl(), "getDBCLevel", NONE)),
                v -> setApplyInt(bl(), "setDBCLevel", v)));
        reg(new Param("localDimming", 0, 3,
                () -> nn(call(panel(), "getLocalDimming", NONE)),
                v -> setApplyInt(panel(), "setLocalDimming", v)));
        reg(new Param("peak", 0, 4,
                () -> {
                    if (!"1".equals(str(call(pq(), "getOption", OPT3, OPT_SUPPORT, SUP_PEAK, "")))) return null;
                    return parseNonNeg(str(call(pq(), "getOption", OPT3, OPT_PEAK, 0, "")));
                },
                // строка: «тип применения, ступень, действие»; у «Настроек» последнее число 2 = EXEC_SAVE, у нас 0 = EXEC
                v -> (Boolean) call(pq(), "setOption", OPT3, OPT_PEAK, 0, "0," + v + ",0")));
        reg(new Param("halo", 0, 1,
                () -> {
                    String s = str(call(pq(), "getOption", OPT3, OPT_HALO, 0, HALO_TAG));
                    return s == null ? null : Integer.valueOf("1".equals(s.trim()) ? 1 : 0);
                },
                v -> (Boolean) call(pq(), "setOption", OPT3, OPT_HALO, v, HALO_TAG)));
        reg(new Param("colorTemp", 0, 12,
                () -> nn(call(pq(), "getColorTemperature", I1, WIN)),
                // номер в библиотеке = порядковый номер EnColorTmpType (так делают и «Настройки»)
                v -> (Boolean) call(pq(), "setColorTemperature", new Class<?>[]{int.class, ap(), Class.forName(PQ + "EnColorTmpType"), act()},
                        WIN, apCur(), enAt(PQ + "EnColorTmpType", v), actExec())));
        reg(new Param("lowBlueRay", 0, 1,
                () -> nn(call(pq(), "getLowBlueRay", I1, WIN)),
                v -> (Boolean) call(pq(), "setLowBlueRay", new Class<?>[]{int.class, int.class, act()}, WIN, v, actExec())));
        reg(new Param("backlight", 0, 100,
                () -> nn(call(bl(), "getBacklight", NONE)),
                v -> setApplyInt(bl(), "setBacklight", v)));
    }

    private static void reg(Param p) { PARAMS.put(p.key, p); }

    // ===== Отражение =====

    // Все менеджеры картинки идут через этот вызов: на другом бренде (план Codex, блок 5) ни одного вызова TCL
    private static Object invokeStatic(String cls, String m) throws Exception {
        if (!Caps.tcl()) throw new IllegalStateException("не TCL: картинка TCL не вызывается");
        try { return Class.forName(cls).getMethod(m).invoke(null); }
        catch (InvocationTargetException e) { throw unwrap(e); }
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable c = e.getCause() != null ? e.getCause() : e;
        return c instanceof Exception ? (Exception) c : new RuntimeException(c);
    }

    private static Object bl() throws Exception { return invokeStatic(PQ + "TvBacklightManager", "getInstance"); }
    private static Object panel() throws Exception { return invokeStatic(PQ + "TvPanelManager", "getInstance"); }
    private static Object pq() throws Exception { return invokeStatic(PQ + "TvPqManager", "getInstance"); }
    private static Object disp() throws Exception { return invokeStatic("com.tcl.tv.display.TvDisplayManager", "getInstance"); }

    private static Object call(Object o, String m, Class<?>[] t, Object... a) throws Exception {
        try { return o.getClass().getMethod(m, t).invoke(o, a); }
        catch (InvocationTargetException e) { throw unwrap(e); }
    }

    private static Class<?> ap() throws Exception { return Class.forName(PQ + "EnApplyType"); }
    private static Class<?> act() throws Exception { return Class.forName(PQ + "EnTclActType"); }
    private static Object apCur() throws Exception { return en(PQ + "EnApplyType", "EN_APPLY_CURRENT"); }
    private static Object actExec() throws Exception { return en(PQ + "EnTclActType", "EN_TCL_ACT_EXEC"); }   // применить БЕЗ сохранения

    private static Object en(String cn, String name) throws Exception {
        for (Object o : Class.forName(cn).getEnumConstants()) if (((Enum<?>) o).name().equals(name)) return o;
        throw new IllegalArgumentException(cn + "." + name);
    }

    private static Object enAt(String cn, int ord) throws Exception {
        Object[] all = Class.forName(cn).getEnumConstants();
        if (ord < 0 || ord >= all.length) throw new IllegalArgumentException(cn + "[" + ord + "]");
        return all[ord];
    }

    // Сеттер вида (EnApplyType, int, EnTclActType): setBacklight, setDBCLevel, setLocalDimming
    private static boolean setApplyInt(Object m, String name, int v) throws Exception {
        return (Boolean) call(m, name, new Class<?>[]{ap(), int.class, act()}, apCur(), v, actExec());
    }

    // Отрицательное в ответе: -1 служба недоступна, -2 не поддерживается, -1000 ошибка. Такое значение «нет данных»
    private static Integer nn(Object r) {
        int v = ((Number) r).intValue();
        return v < 0 ? null : Integer.valueOf(v);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Integer parseNonNeg(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try { int v = Integer.parseInt(s); return v < 0 ? null : Integer.valueOf(v); } catch (NumberFormatException e) { return null; }
    }

    // ===== Контекст: режим картинки и тип сигнала =====

    private static final String[] HDR_NAMES = {"SDR", "HDR10", "HDR10+", "HLG", "HLG18", "Dolby", "Premium", "?"};

    static String hdrName(int h) { return h >= 0 && h < HDR_NAMES.length ? HDR_NAMES[h] : "?"; }

    // Номера режимов из pq_picture.ini на ТВ
    static String modeName(int m) {
        String[] base = {"Standard", "Vivid", "Mild", "Movie", "Sport", "Game", "Game", "Game", "PC", "Pro", "AI"};
        if (m >= 0 && m <= 10) return "SDR " + base[m];
        if (m == 60) return "SDR Filmmaker";
        if (m >= 12 && m <= 15) return "SDR Photo";
        String[] g = {"Standard", "Vivid", "Mild", "Movie", "Sport", "Game", "Game", "Game", "PC", "Pro", "AI", "IMAX", "Work", "Filmmaker"};
        if (m >= 16 && m <= 29) return "HDR10 " + g[m - 16];
        if (m >= 31 && m <= 43) return "HLG " + new String[]{"Standard", "Vivid", "Mild", "Movie", "Sport", "Game", "Game", "Game", "PC", "Pro", "AI", "Work", "Filmmaker"}[m - 31];
        if (m >= 46 && m <= 53) return "Dolby " + new String[]{"Vivid", "Movie", "Game", "Game", "Game", "IQ", "AI", "Filmmaker"}[m - 46];
        if (m >= 61 && m <= 73) return "HDR10+ " + new String[]{"Standard", "Vivid", "Mild", "Movie", "Sport", "Game", "Game", "Game", "PC", "Pro", "AI", "Work", "Filmmaker"}[m - 61];
        return "режим " + m;
    }

    // {номер режима картинки, hdr_type, номер входа}; -1 если не прочиталось
    private static int[] sig() {
        int mode = -1, hdr = -1, dev = -1;
        try { mode = ((Number) call(pq(), "getPictureMode", NONE)).intValue(); }
        catch (Throwable e) { Log.w(TAG, "pq: режим картинки не читается: " + e); }
        try {
            Object vi = call(pq(), "getTvVideoInfo", I1, WIN);
            if (vi != null) hdr = vi.getClass().getField("hdr_type").getInt(vi);
        } catch (Throwable e) { Log.w(TAG, "pq: getTvVideoInfo не читается: " + e); }
        if (hdr < 0) {
            try {
                Object vi = call(disp(), "getTvInputVideoInfo", I1, WIN);
                if (vi != null) hdr = vi.getClass().getField("hdr_type").getInt(vi);
            } catch (Throwable e) { Log.w(TAG, "pq: getTvInputVideoInfo не читается: " + e); }
        }
        try {
            Integer d = parseNonNeg(str(call(pq(), "getOption", OPT3, OPT_DEVICE, 0, "")));   // тот же вызов, что у «Настроек» (PictureManager2.getDBCLevelByPicMode)
            if (d != null) dev = d.intValue();
        } catch (Throwable e) { Log.w(TAG, "pq: номер входа не читается: " + e); }
        return new int[]{mode, hdr, dev};
    }

    // Тот же контекст картинки: режим и сигнал совпадают, а вход совпадает или не читается ни там, ни там
    private static boolean sameCtx(St st, int[] sg) {
        return sg[0] == st.mode && sg[1] == st.hdr && (st.dev < 0 || sg[2] < 0 || st.dev == sg[2]);
    }

    private static String ctxName(int mode, int hdr, int dev) {
        return modeName(mode) + ", сигнал " + hdrName(hdr) + (dev >= 0 ? ", вход " + dev : "");
    }

    // Номер загрузки ТВ: после перезагрузки «живые» значения сбрасываются на сохранённые
    static int bootId(Context c) {
        try { return Settings.Global.getInt(c.getContentResolver(), "boot_count", -1); } catch (Throwable e) { return -1; }
    }

    // ===== Снимок (dump): только чтение =====

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v == null ? JSONObject.NULL : v); } catch (Throwable ignored) { }
    }

    private static final class Rec {
        final JSONObject v = new JSONObject(), raw = new JSONObject(), errs = new JSONObject();
        final List<String> unsup = new ArrayList<String>();
        int calls;

        // Число: -2 уходит в unsupported, -1000 и исключения в errs. Каждый вызов отдельно
        void gi(String key, Object m, String name, Class<?>[] t, Object... a) {
            if (m == null) return;
            calls++;
            try {
                Object r = call(m, name, t, a);
                if (r instanceof Float || r instanceof Double) { put(v, key, ((Number) r).doubleValue()); return; }
                if (r instanceof Number) {
                    int n = ((Number) r).intValue();
                    put(v, key, n);
                    if (n == -2) unsup.add(key);
                    if (n == -1000) put(errs, key, "-1000");
                } else if (r instanceof Boolean) put(v, key, r);
                else put(raw, key, str(r));
            } catch (Throwable e) { put(errs, key, e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }

        void gs(String key, Object m, int id, int arg, String s) {
            if (m == null) return;
            calls++;
            try { put(raw, key, str(call(m, "getOption", OPT3, id, arg, s))); }
            catch (Throwable e) { put(errs, key, e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }

        // Публичные поля объекта (TvVideoInfo и подобные) -> JSON
        JSONObject fields(String key, Object m, String name, int win) {
            JSONObject o = new JSONObject();
            if (m == null) return o;
            calls++;
            try {
                Object obj = call(m, name, I1, win);
                if (obj == null) { put(errs, key, "null"); return o; }
                for (Field f : obj.getClass().getFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ty = f.getType();
                    if (ty == int.class || ty == boolean.class || ty == long.class) put(o, f.getName(), f.get(obj));
                }
            } catch (Throwable e) { put(errs, key, e.getClass().getSimpleName() + ": " + e.getMessage()); }
            return o;
        }

        // VideoColorInfo -> "R,G,B"
        void rgb(String key, Object m, String name) {
            if (m == null) return;
            calls++;
            try {
                Object o = call(m, name, I1, WIN);
                if (o == null) return;
                put(raw, key, o.getClass().getField("R").getInt(o) + "," + o.getClass().getField("G").getInt(o) + "," + o.getClass().getField("B").getInt(o));
            } catch (Throwable e) { put(errs, key, e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }
    }

    private static Object mgrOrNull(Rec r, String name, boolean display) {
        try { return display ? disp() : invokeStatic(PQ + name, "getInstance"); }
        catch (Throwable e) { put(r.errs, name, e.getClass().getSimpleName() + ": " + e.getMessage()); return null; }
    }

    // Геттеры TvPqManager(окно): {ключ в JSON, метод}. Первые используют сами «Настройки» TCL, остальные в блоке EXTRA
    private static final String[][] PQ_WIN = {
            {"blackLevel", "getBrightness"}, {"contrast", "getContrast"}, {"saturation", "getSaturation"}, {"hue", "getHue"},
            {"sharpness", "getSharpness"}, {"nr", "getNR"}, {"mpegNr", "getMpegNR"}, {"deContour", "getDeContour"},
            {"blackStretch", "getBlackStretch"}, {"blueStretch", "getBlueStretch"}, {"colorEnhance", "getColorEnhance"},
            {"dynamicContrast", "getDynamicContrast"}, {"dynamicToneMapping", "getDynamicToneMapping"},
            {"hdrToneMapAdjust", "getHDRToneMappingAdjust"}, {"sdr2Hdr", "getSdr2Hdr"}, {"dolbyApo", "getDolbyApoEnable"},
            {"aipq", "getAipqEnable"}, {"aisr", "getAisrEnable"}, {"rgbContentAdpt", "getRgbContentAdpt"}, {"rgbSensor", "getRgbSensor"},
            {"memc", "getMemcConfig"}, {"memcBlur", "getMemcBlur"}, {"memcJudder", "getMemcJudder"}, {"motionClarity", "getMotionClarity"},
            {"ledMotionClarity", "getLedMotionClarity"}, {"p24", "get24p"}, {"allm", "getALLMMode"}, {"vrr", "getVRRMode"},
            {"ddm", "getDDMMode"}, {"dlg", "getDLGMode"}, {"colorTemp", "getColorTemperature"}, {"custWb", "getCustWhiteBalanceEnable"},
            {"colorSpaceMode", "getColorSpaceMode"}, {"lowBlueRay", "getLowBlueRay"}, {"gamePictureType", "getGamePicutreModeType"},
            {"rgbMode", "getRGBMode"}};
    private static final String[][] PQ_WIN_EXTRA = {
            {"darkEnhance", "getDarkEnhance"}, {"eotf", "getVideoEOTFMode"}, {"refStandard", "getVideoReferenceStandard"},
            {"dolbyGame", "getDolbyGameStatus"}, {"lowLatency", "getLowlantency"}, {"pictureType", "getPictureType"},
            {"colorFormatIn", "getInputColorFormat"}, {"colorRangeIn", "getInputColorRange"}};
    private static final String[][] PQ_NOARG = {
            {"pictureMode", "getPictureMode"}, {"gammaLevel", "getGammaLevel"}, {"custGammaEnable", "getCustGammaCurveEnable"}};
    // Заводские значения выбранных функций для текущего режима (getDefaultData), для сверки «что сейчас» и «как с завода»
    private static final String[][] FACTORY = {
            {"backlight", "BACKLIGHT"}, {"dbc", "DBC_LEVEL"}, {"localDimming", "LOCAL_DIMMING"}, {"colorTemp", "COLOR_TEMPERATURE"},
            {"peak", "PEAK_LUMINANCE"}, {"blackLevel", "BRIGHTNESS"}, {"contrast", "CONTRAST"}, {"saturation", "SATURATION"},
            {"sharpness", "SHARPNESS"}, {"dynamicContrast", "DYNAMIC_CONTRAST"}, {"dynamicToneMapping", "DYNAMIC_TONE_MAPPING"}};

    // full=false: только то, что читают и сами «Настройки» TCL; full=true: плюс остальные безопасные геттеры
    static synchronized JSONObject dump(Context c, boolean full) {
        long t0 = System.currentTimeMillis();
        JSONObject out = new JSONObject();
        Rec r = new Rec();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(t0));
        try {
            put(out, "t", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(t0)));
            put(out, "bootCount", bootId(c));
            Object mBl = mgrOrNull(r, "TvBacklightManager", false);
            Object mPanel = mgrOrNull(r, "TvPanelManager", false);
            Object mPq = mgrOrNull(r, "TvPqManager", false);

            // Подсветка, датчик света, DBC (энергосбережение), mixDimming
            r.gi("backlight", mBl, "getBacklight", NONE);
            r.gi("lightSensor", mBl, "getLightSensorOnoff", NONE);
            r.gi("dbc", mBl, "getDBCLevel", NONE);
            r.gi("mixDimming", mBl, "getMixDimming", NONE);
            r.gi("localDimming", mPanel, "getLocalDimming", NONE);
            r.gi("microDimming", mPanel, "getMicroDimming", NONE);
            for (String[] g : PQ_NOARG) r.gi(g[0], mPq, g[1], NONE);
            for (String[] g : PQ_WIN) r.gi(g[0], mPq, g[1], I1, WIN);
            // Пик (option 19), ореолы (option 20), поддержка пика, номер входа, переключатель энергосбережения (option 12)
            r.gs("peakSupport", mPq, OPT_SUPPORT, SUP_PEAK, "");
            r.gs("peak", mPq, OPT_PEAK, 0, "");
            r.gs("halo", mPq, OPT_HALO, 0, HALO_TAG);
            r.gs("deviceId", mPq, OPT_DEVICE, 0, "");
            r.gs("energyOption", mPq, OPT_ENERGY, 0, "");
            Integer pk = parseNonNeg(str(r.raw.opt("peak")));
            if (pk != null) put(r.v, "peak", pk);
            Object hs = r.raw.opt("halo");
            if (hs instanceof String) put(r.v, "halo", "1".equals(((String) hs).trim()) ? 1 : 0);
            r.rgb("whiteBalanceGain", mPq, "getWhiteBalanceGain");
            r.rgb("whiteBalanceOffset", mPq, "getWhiteBalanceOffset");

            // Режим картинки и тип сигнала
            int[] sg = sig();
            put(out, "pictureMode", sg[0]);
            put(out, "pictureModeName", modeName(sg[0]));
            put(out, "hdr_type", sg[1]);
            put(out, "hdrName", hdrName(sg[1]));
            put(out, "v", r.v);
            put(out, "raw", r.raw);
            // Заводские значения для текущего режима
            JSONObject fac = new JSONObject();
            if (mPq != null && sg[0] >= 0) {
                for (String[] f : FACTORY) {
                    r.calls++;
                    try {
                        Object fn = en(PQ + "TvPqFuncType", "EN_PQ_FUNCTION_" + f[1]);
                        put(fac, f[0], call(mPq, "getDefaultData", new Class<?>[]{int.class, int.class, Class.forName(PQ + "TvPqFuncType")}, WIN, sg[0], fn));
                    } catch (Throwable e) { put(r.errs, "factory." + f[0], e.getClass().getSimpleName() + ": " + e.getMessage()); }
                }
            }
            put(out, "factory", fac);
            // Поддержка функций (getFunctionIsSupport)
            JSONObject sup = new JSONObject();
            if (mPq != null) {
                try {
                    Class<?> et = Class.forName(PQ + "TvPqManager$EnFunctionSupportType");
                    for (Object e : et.getEnumConstants()) {
                        String n = ((Enum<?>) e).name();
                        if (n.endsWith("_MAX")) continue;
                        r.calls++;
                        try { put(sup, n.replace("EN_FUNCTION_SUPPORT_", ""), call(mPq, "getFunctionIsSupport", new Class<?>[]{et}, e)); }
                        catch (Throwable x) { put(r.errs, "support." + n, x.getClass().getSimpleName() + ": " + x.getMessage()); }
                    }
                } catch (Throwable e) { put(r.errs, "support", e.getClass().getSimpleName() + ": " + e.getMessage()); }
            }
            put(out, "support", sup);
            put(out, "errors", r.errs);
            put(out, "unsupported", new JSONArray(r.unsup));
            put(out, "calls", r.calls);
            store(c, stamp, out);   // сохранить базовую часть сразу: если дальше что-то повиснет, она не пропадёт

            if (full) {
                for (String[] g : PQ_WIN_EXTRA) r.gi(g[0], mPq, g[1], I1, WIN);
                r.gi("vrrRefreshRate", mPq, "getVrrRefreshRate", NONE);
                put(out, "pqDemoMode", mPq == null ? null : tryStr(r, "pqDemoMode", mPq, "getPQDemoMode", I1, WIN));
                r.gi("panelFrameRate", mPanel, "getPanelFrameRate", NONE);
                r.gi("viewAngle", mPanel, "getViewAngle", NONE);
                r.gi("ldmRows", mPanel, "getLocalDimmingRow", NONE);
                r.gi("ldmCols", mPanel, "getLocalDimmingCol", NONE);
                put(out, "videoInfo", r.fields("videoInfo", mPq, "getTvVideoInfo", WIN));
                Object mDisp = mgrOrNull(r, "TvDisplayManager", true);
                put(out, "displayVideoInfo", r.fields("displayVideoInfo", mDisp, "getTvInputVideoInfo", WIN));
                JSONObject si = new JSONObject();
                if (mDisp != null) {
                    try {
                        Class<?> et = Class.forName("com.tcl.tv.display.TvDisplayManager$EnSignalInfoType");
                        for (Object e : et.getEnumConstants()) {
                            String n = ((Enum<?>) e).name();
                            if (n.endsWith("_MAX")) continue;
                            r.calls++;
                            try { put(si, n.replace("EN_SIGNAL_INFO_", ""), call(mDisp, "getSignalInfo", new Class<?>[]{et}, e)); }
                            catch (Throwable x) { put(r.errs, "signal." + n, x.getClass().getSimpleName() + ": " + x.getMessage()); }
                        }
                    } catch (Throwable e) { put(r.errs, "signal", e.getClass().getSimpleName() + ": " + e.getMessage()); }
                }
                put(out, "signalInfo", si);
                put(out, "unsupported", new JSONArray(r.unsup));
                put(out, "calls", r.calls);
            }
        } catch (Throwable e) {
            put(r.errs, "dump", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        put(out, "ms", System.currentTimeMillis() - t0);
        File f = store(c, stamp, out);
        try { logLong("pq dump" + (f != null ? " " + f.getName() : ""), out.toString()); } catch (Throwable ignored) { }
        return out;
    }

    private static Object tryStr(Rec r, String key, Object m, String name, Class<?>[] t, Object... a) {
        r.calls++;
        try { return str(call(m, name, t, a)); }
        catch (Throwable e) { put(r.errs, key, e.getClass().getSimpleName() + ": " + e.getMessage()); return null; }
    }

    private static void logLong(String head, String s) {
        final int n = 3000;
        int parts = Math.max(1, (s.length() + n - 1) / n);
        for (int i = 0; i < parts; i++)
            Log.i(TAG, head + " [" + (i + 1) + "/" + parts + "] " + s.substring(i * n, Math.min(s.length(), (i + 1) * n)));
    }

    // files/pq-<время>.json (+ копия pq-latest.json в files внешней папки приложения, чтобы забрать с ПК через adb pull)
    private static File store(Context c, String stamp, JSONObject out) {
        try {
            String text = out.toString(2);
            File dir = c.getFilesDir();
            File f = new File(dir, "pq-" + stamp + ".json");
            write(f, text);
            File[] old = dir.listFiles();
            if (old != null) {
                List<String> names = new ArrayList<String>();
                for (File x : old) if (x.getName().startsWith("pq-2") && x.getName().endsWith(".json")) names.add(x.getName());
                java.util.Collections.sort(names);
                for (int i = 0; i < names.size() - 20; i++) new File(dir, names.get(i)).delete();   // держим последние 20
            }
            try {
                File ext = c.getExternalFilesDir(null);
                if (ext != null) write(new File(ext, "pq-latest.json"), text);
            } catch (Throwable ignored) { }
            return f;
        } catch (Throwable e) {
            Log.w(TAG, "pq: файл снимка не записался", e);
            return null;
        }
    }

    private static void write(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try { w.write(text); } finally { w.close(); }
    }

    // ===== Состояние: снимок дня, что записали мы, что тронул пульт =====

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("pq", Context.MODE_PRIVATE); }

    private static final class St {
        boolean evening; int boot = -1, mode = -1, hdr = -1; long time;
        final Map<String, Integer> snap = new LinkedHashMap<String, Integer>();    // «День»: значения до первой записи
        final Map<String, Integer> wrote = new LinkedHashMap<String, Integer>();   // что стоит на панели после нашей записи
        final Set<String> dirty = new LinkedHashSet<String>();                     // параметры, которые тронул пульт
        final Set<String> skip = new LinkedHashSet<String>();                      // параметры, которыми Pq не распоряжается (их ведёт Сторож)
        final Set<String> planned = new LinkedHashSet<String>();                   // что вечер собирался писать: на диске ДО первой записи, «День» возвращает только это
        int dev = -1;                                                              // номер входа (option 14), -1 если не прочитался

        static St load(SharedPreferences s) {
            St x = new St();
            x.evening = s.getBoolean("evening", false);
            x.boot = s.getInt("boot", -1); x.mode = s.getInt("mode", -1); x.hdr = s.getInt("hdr", -1); x.time = s.getLong("time", 0);
            for (String k : PARAMS.keySet()) {
                if (s.contains("snap." + k)) x.snap.put(k, s.getInt("snap." + k, 0));
                if (s.contains("wrote." + k)) x.wrote.put(k, s.getInt("wrote." + k, 0));
            }
            for (String k : s.getString("dirty", "").split(",")) if (!k.isEmpty()) x.dirty.add(k);
            for (String k : s.getString("skip", "").split(",")) if (!k.isEmpty()) x.skip.add(k);
            for (String k : s.getString("planned", "").split(",")) if (!k.isEmpty()) x.planned.add(k);
            x.dev = s.getInt("dev", -1);
            return x;
        }

        void save(SharedPreferences s) {
            SharedPreferences.Editor e = s.edit().clear();
            e.putBoolean("evening", evening); e.putInt("boot", boot); e.putInt("mode", mode); e.putInt("hdr", hdr); e.putLong("time", time);
            for (Map.Entry<String, Integer> m : snap.entrySet()) e.putInt("snap." + m.getKey(), m.getValue());
            for (Map.Entry<String, Integer> m : wrote.entrySet()) e.putInt("wrote." + m.getKey(), m.getValue());
            StringBuilder d = new StringBuilder();
            for (String k : dirty) d.append(d.length() > 0 ? "," : "").append(k);
            e.putString("dirty", d.toString());
            StringBuilder sk = new StringBuilder();
            for (String k : skip) sk.append(sk.length() > 0 ? "," : "").append(k);
            e.putString("skip", sk.toString());
            StringBuilder pl = new StringBuilder();
            for (String k : planned) pl.append(pl.length() > 0 ? "," : "").append(k);
            e.putString("planned", pl.toString());
            e.putInt("dev", dev);
            e.commit();
        }
    }

    static boolean isEveningActive(Context c) { return sp(c).getBoolean("evening", false); }

    private static void forget(SharedPreferences s) { s.edit().clear().commit(); }

    // ===== Чтение и запись параметров =====

    private static Map<String, Integer> readAll(List<String> missing) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (Param p : PARAMS.values()) {
            try {
                Integer v = p.rd.get();
                if (v != null) m.put(p.key, v); else missing.add(p.key);
            } catch (Throwable e) {
                missing.add(p.key + "!");
                Log.w(TAG, "pq: " + p.key + ": чтение не удалось: " + e);
            }
        }
        return m;
    }

    // Записать и прочитать обратно. Возвращает то, что реально стоит на панели (null: не записалось и не читается)
    private static Integer put(Param p, int v) {
        try {
            boolean ok = p.wr.put(v);
            Integer back;
            try { back = p.rd.get(); } catch (Throwable e) { back = null; }
            if (!ok) Log.w(TAG, "pq: " + p.key + "=" + v + ": служба ответила «нет», на панели " + back);
            else if (back != null && back.intValue() != v) Log.w(TAG, "pq: " + p.key + ": просил " + v + ", на панели " + back);
            return back != null ? back : (ok ? Integer.valueOf(v) : null);
        } catch (Throwable e) {
            Log.w(TAG, "pq: запись " + p.key + "=" + v + " не удалась", e);
            return null;
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // Плавный ход подсветки. На каждом шаге сверяем с тем, что поставили сами: сдвинул пульт, значит уступаем
    private static Integer ramp(Param p, int from, int to, St st) {
        int n = Math.min(Math.abs(to - from), RAMP_STEPS);
        if (RAMP_MS <= 0 || n <= 1) return put(p, to);
        int last = from;
        for (int i = 1; i <= n; i++) {
            if (Thread.currentThread().isInterrupted()) return last;
            try {
                Integer now = p.rd.get();
                if (now != null && now.intValue() != last) {
                    st.dirty.add(p.key);
                    Log.i(TAG, "pq: подсветку сдвинули пультом (" + last + " -> " + now + "), плавный ход прекращён");
                    return now;
                }
            } catch (Throwable ignored) { }
            Integer live = put(p, from + Math.round((to - from) * (float) i / n));
            if (live == null) return null;
            last = live;
            if (i < n) sleep(RAMP_MS / n);
        }
        return last;
    }

    // Целевые значения «Вечера»: только допустимые, читаемые на этом ТВ, и не ярче дня там, где так решено
    private static Map<String, Integer> plan(Map<String, Integer> target, Map<String, Integer> day) {
        Map<String, Integer> res = new LinkedHashMap<String, Integer>();
        for (String k : ORDER_APPLY) {
            Integer t = target.get(k);
            Param p = PARAMS.get(k);
            if (t == null || p == null) continue;
            Integer d = day.get(k);
            if (d == null) { Log.i(TAG, "pq: " + k + " не читается на этом ТВ, не пишу"); continue; }
            if (k.equals("backlight")) t = Math.max(MIN_BACKLIGHT, t);
            if (t < p.min || t > p.max) { Log.w(TAG, "pq: " + k + "=" + t + " вне допустимого " + p.min + ".." + p.max + ", пропуск"); continue; }
            if (k.equals("localDimming") && t == 2) { Log.w(TAG, "pq: localDimming=2 «Настройки» TCL не пишут никогда (у них 0, 1 и 3), пропуск"); continue; }
            if (ONLY_LOWER.contains(k) && t > d) t = d;
            res.put(k, t);
        }
        return res;
    }

    // Записать план в нужном порядке; пишем только то, что отличается от текущего
    private static String writePlan(St st, Map<String, Integer> plan, Map<String, Integer> cur, String[] order, boolean recordWrote) {
        StringBuilder sb = new StringBuilder();
        for (String k : order) {
            Integer want = plan.get(k), before = cur.get(k);
            Param p = PARAMS.get(k);
            if (want == null || before == null || p == null) continue;
            if (before.equals(want)) { if (recordWrote) st.wrote.put(k, want); continue; }
            Integer live = k.equals("backlight") ? ramp(p, before, want, st) : put(p, want);
            if (recordWrote && live != null && !st.dirty.contains(k) && (live.equals(want) || !live.equals(before))) st.wrote.put(k, live);
            sb.append(sb.length() > 0 ? ", " : "").append(k).append(' ').append(before).append("→").append(live == null ? "?" : live.toString());
        }
        return sb.toString();
    }

    // ===== Вечер, День, проверка по времени =====

    static Map<String, Integer> evening() { return new LinkedHashMap<String, Integer>(EVENING); }

    // Начало вечера (или повтор после перезагрузки). Сначала снимок дня на диск, потом записи.
    // skip: параметры, которыми Pq не распоряжается (из службы так исключена подсветка: её ведёт ночной ход Сторожа)
    private static String enterEvening(Context c, Map<String, Integer> target, boolean force, Set<String> skip) {
        SharedPreferences s = sp(c);
        St st = St.load(s);
        int[] sg = sig();
        if (SDR_ONLY && !force && sg[1] != 0) {
            Log.i(TAG, "pq: вечер пропущен: сигнал " + hdrName(sg[1]) + " (" + modeName(sg[0]) + "), включаю только при SDR");
            return null;
        }
        List<String> missing = new ArrayList<String>();
        Map<String, Integer> cur = readAll(missing);
        if (cur.isEmpty()) { Log.w(TAG, "pq: ничего не читается " + missing + ", вечер не включаю"); return null; }
        int boot = bootId(c);
        boolean fresh = !st.evening || st.boot != boot || !sameCtx(st, sg) || st.snap.isEmpty();
        if (fresh) {
            st = new St();
            st.evening = true; st.boot = boot; st.mode = sg[0]; st.hdr = sg[1]; st.dev = sg[2]; st.time = System.currentTimeMillis();
            st.snap.putAll(cur);
            st.save(s);   // СНИМОК ДНЯ ПОПАДАЕТ НА ДИСК ДО ПЕРВОЙ ЗАПИСИ
            Log.i(TAG, "pq: снимок дня " + st.snap + ", " + ctxName(sg[0], sg[1], sg[2]) + ", загрузка " + boot);
        }
        st.dirty.clear();   // новый переход: прежние уступки пульту сняты
        st.skip.clear();
        if (skip != null) st.skip.addAll(skip);
        Map<String, Integer> tgt = new LinkedHashMap<String, Integer>(target);
        tgt.keySet().removeAll(st.skip);
        Map<String, Integer> pl = plan(tgt, st.snap);
        st.planned.addAll(pl.keySet());
        st.save(s);   // СПИСОК «ЧТО СОБИРАЕМСЯ ПИСАТЬ» ТОЖЕ НА ДИСКЕ ДО ПЕРВОЙ ЗАПИСИ: если процесс упадёт посреди хода, «День» всё равно вернёт эти параметры
        String r = writePlan(st, pl, cur, ORDER_APPLY, true);
        st.save(s);
        return "вечер: " + (r.isEmpty() ? "уже как нужно" : r);
    }

    // Вечер уже идёт: ничего не пишем, только замечаем, что тронул пульт. Исключение: система сама откатила всю картинку
    private static String inPeriod(Context c, St st) {
        int[] sg = sig();
        if (!sameCtx(st, sg)) {
            Log.i(TAG, "pq: картинка сменилась (" + ctxName(st.mode, st.hdr, st.dev) + " -> " + ctxName(sg[0], sg[1], sg[2]) + "), ничего не трогаю");
            return null;
        }
        Map<String, Integer> cur = readAll(new ArrayList<String>());
        boolean changed = false, allBack = !st.wrote.isEmpty(), anyDiff = false;
        for (Map.Entry<String, Integer> e : st.wrote.entrySet()) {
            String k = e.getKey();
            Integer w = e.getValue(), now = cur.get(k), day = st.snap.get(k);
            if (now == null || day == null) { allBack = false; continue; }
            boolean ours = now.equals(w), isDay = now.equals(day);
            if (!w.equals(day)) {
                anyDiff = true;
                if (!isDay) allBack = false;                 // наше значение осталось (или стало третье): это не откат
            }
            if (!ours && !isDay) {                           // третье значение: это рука, а не система
                allBack = false;
                if (st.dirty.add(k)) {
                    changed = true;
                    Log.i(TAG, "pq: " + k + " поменяли руками (" + w + " -> " + now + "), до следующего перехода не трогаю");
                }
            }
            if (st.dirty.contains(k)) allBack = false;
        }
        String r = null;
        if (allBack && anyDiff) {
            // Все наши параметры разом вернулись к дневным значениям: так не бывает от пульта, это сброс системой
            Map<String, Integer> again = new LinkedHashMap<String, Integer>(st.wrote);
            st.wrote.clear();
            String w = writePlan(st, again, cur, ORDER_APPLY, true);
            r = "вечер заново (система сбросила картинку): " + w;
            changed = true;
        }
        if (changed) st.save(sp(c));
        return r;
    }

    // Переход в «День»: вернуть снимок. force=true пишет даже при смене режима
    private static String leaveEvening(Context c, boolean force) {
        SharedPreferences s = sp(c);
        St st = St.load(s);
        if (!st.evening) return null;
        int[] sg = sig();
        String why = null;
        if (!force && st.boot != bootId(c)) why = "после перезагрузки картинка и так сохранённая";
        else if (!force && !sameCtx(st, sg)) why = "картинка сменилась (" + ctxName(st.mode, st.hdr, st.dev) + " -> " + ctxName(sg[0], sg[1], sg[2]) + "), ничего не пишу";
        if (why != null) { forget(s); Log.i(TAG, "pq: день: " + why); return "день: " + why; }
        Map<String, Integer> cur = readAll(new ArrayList<String>());
        Map<String, Integer> plan = new LinkedHashMap<String, Integer>(st.snap);
        if (!force) plan.keySet().removeAll(st.skip);   // чем не распоряжались вечером, того и утром не трогаем
        if (!force && !st.planned.isEmpty()) {          // ПРАВКА ПРОВЕРКИ: возвращаем только то, что вечер собирался писать (или записал), остальное не наше
            Set<String> mine = new LinkedHashSet<String>(st.planned);
            mine.addAll(st.wrote.keySet());
            plan.keySet().retainAll(mine);
        }
        if (RESPECT_REMOTE_AT_TRANSITION) {
            for (String k : new ArrayList<String>(plan.keySet())) {
                Integer w = st.wrote.get(k), n = cur.get(k);
                if (w != null && n != null && !n.equals(w)) plan.remove(k);
            }
        }
        String r = writePlan(st, plan, cur, ORDER_RESTORE, false);
        forget(s);
        return "день: " + (r.isEmpty() ? "уже как в снимке" : r);
    }

    // ===== Публичные операции =====

    static synchronized String apply(Context c, String profile, Map<String, Integer> override, boolean force) {
        try {
            if ("day".equals(profile) || "день".equals(profile)) return restore(c, force);
            boolean only = "only".equals(profile) || "только".equals(profile);   // писать только перечисленное в set: для проб по одному параметру
            if (!only && !"evening".equals(profile) && !"вечер".equals(profile)) return "неизвестный профиль «" + profile + "» (есть evening, day и only)";
            if (only && (override == null || override.isEmpty())) return "only: нужен --es set \"имя=значение\"";
            Map<String, Integer> t = only ? new LinkedHashMap<String, Integer>() : evening();
            if (override != null) t.putAll(override);
            String r = enterEvening(c, t, force, null);
            return r != null ? r : "вечер: пропущен, причина в логе (по умолчанию только при SDR; --ez force true)";
        } catch (Throwable e) {
            Log.w(TAG, "pq: apply: сбой", e);
            return "сбой: " + e;
        }
    }

    static synchronized String restore(Context c, boolean force) {
        try {
            String r = leaveEvening(c, force);
            return r != null ? r : "день: возвращать нечего, вечер не включали";
        } catch (Throwable e) {
            Log.w(TAG, "pq: restore: сбой", e);
            return "сбой: " + e;
        }
    }

    // Для GuardService: вызывать при запуске, включении экрана, каждые 15 минут и на границах 22:00 и 7:00.
    // Возвращает строку, только если что-то изменилось (для журнала), иначе null
    static String tick(Context c, boolean wantEvening, String why) { return tick(c, wantEvening, why, null); }

    // skip: ключи, которые Pq не трогает (GuardService передаёт «backlight»: подсветку ведёт его ночной ход)
    static synchronized String tick(Context c, boolean wantEvening, String why, Set<String> skip) {
        try {
            St st = St.load(sp(c));
            if (wantEvening) {
                if (st.evening && st.boot == bootId(c)) return inPeriod(c, st);
                return enterEvening(c, evening(), false, skip);
            }
            return st.evening ? leaveEvening(c, false) : null;
        } catch (Throwable e) {
            Log.w(TAG, "pq: tick (" + why + "): сбой", e);
            return null;
        }
    }

    static synchronized String status(Context c) {
        St st = St.load(sp(c));
        return "вечер=" + st.evening + " загрузка=" + st.boot + " (сейчас " + bootId(c) + ") режим=" + modeName(st.mode) + " сигнал=" + hdrName(st.hdr)
                + "\nснимок дня=" + st.snap + "\nзаписано нами=" + st.wrote + "\nсобирались писать=" + st.planned + "\nтронул пульт=" + st.dirty + "\nне трогаем (ведёт Сторож)=" + st.skip + "\nвход=" + st.dev;
    }

    static synchronized String forgetAll(Context c) {
        forget(sp(c));
        return "состояние забыто, на панели ничего не менял";
    }

    // ===== Для «Картинки» (Pic.java): чтение и запись по одному параметру =====
    // Те же сеттеры из белого списка PARAMS, те же EN_APPLY_CURRENT и EN_TCL_ACT_EXEC (без сохранения). Ничего нового в список
    // не добавлено: «Картинка» ходит только по lightSensor, dbc, localDimming, peak, colorTemp, lowBlueRay и backlight.
    // Блокировка та же, что у tick() и apply(): запись «Картинки» не перемешивается с «Вечером»

    static synchronized Integer picGet(String key) {
        Param p = PARAMS.get(key);
        if (p == null) return null;
        try { return p.rd.get(); }
        catch (Throwable e) { Log.w(TAG, "pq: " + key + ": чтение не удалось: " + e); return null; }
    }

    // Записать и вернуть то, что реально стоит на панели (null: не записалось и не читается)
    static synchronized Integer picPut(String key, int v) {
        Param p = PARAMS.get(key);
        if (p == null || v < p.min || v > p.max) return null;
        if (key.equals("localDimming") && v == 2) return null;   // 2 «Настройки» TCL не пишут никогда (у них 0, 1 и 3)
        return put(p, v);
    }

    // Тип сигнала по номеру режима картинки (таблица modeName): ТВ сам переключает режим на HDR-вариант (HDR10_MOVIE, HLG_MOVIE, DOLBY_*, HDR10P_*).
    // 0 для SDR и для номеров, которых мы не знаем
    static int hdrOfMode(int m) {
        if (m >= 16 && m <= 29) return 1;   // HDR10
        if (m >= 61 && m <= 73) return 2;   // HDR10+
        if (m >= 31 && m <= 43) return 3;   // HLG
        if (m >= 46 && m <= 53) return 5;   // Dolby
        return 0;
    }

    // {номер режима картинки, тип сигнала (0 = SDR, остальное HDR, -1 не читается), номер входа}
    // Для «Картинки» тип сигнала берётся ещё и по номеру режима: если видеоинфо ТВ отдаёт 0 (приложения-плееры), а режим уже HDR-вариант,
    // подсветка HDR-колонки всё равно включится. Pq.tick() это не касается: он ходит через sig() как раньше
    static synchronized int[] picSig() {
        int[] s = sig();
        if (s[1] <= 0) {
            int h = hdrOfMode(s[0]);
            if (h > 0) s[1] = h;
        }
        return s;
    }

    // «backlight=20,colorTemp=2» -> карта
    static Map<String, Integer> parseSet(String s) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        if (s == null) return m;
        for (String part : s.split(",")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i).trim();
            try {
                int v = Integer.parseInt(part.substring(i + 1).trim());
                if (PARAMS.containsKey(k)) m.put(k, v); else Log.w(TAG, "pq: параметра «" + k + "» нет в белом списке " + PARAMS.keySet());
            } catch (NumberFormatException e) { Log.w(TAG, "pq: не число в «" + part + "»"); }
        }
        return m;
    }

    // Команда для Cmd: dump | apply | restore | status | forget. flag: у dump это full, у apply и restore это force
    static String command(Context c, String action, String profile, String set, boolean flag) {
        String a = action == null || action.isEmpty() ? "dump" : action;
        switch (a) {
            case "dump": { JSONObject j = dump(c, flag); return "снимок готов, ключей " + j.length() + ", файл в files/pq-*.json"; }
            case "apply": return apply(c, profile == null ? "evening" : profile, parseSet(set), flag);
            case "restore": return restore(c, flag);
            case "status": return status(c);
            case "forget": return forgetAll(c);
            default: return "неизвестное действие «" + a + "» (dump, apply, restore, status, forget)";
        }
    }

    // Одна очередь на все операции: вызовы службы TCL и плавный ход не должны идти в главном потоке
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "guard-pq");
        t.setDaemon(true);
        return t;
    });

    static void async(Runnable r) {
        EXEC.execute(() -> {
            try { r.run(); } catch (Throwable e) { Log.w(TAG, "pq: сбой в очереди", e); }
        });
    }
}
