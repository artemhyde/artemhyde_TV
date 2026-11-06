package app.glasstv.guard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

// Проба скрытой службы Dolby из прошивки ТВ: com.dolby.android.audio.service (интерфейс IDolbyAudioService, AIDL версии 3).
// Без AIDL-компилятора: ручной Binder transact. Номера вызовов получены разбором dex прошивки ТВ.
//
// Что важно знать (проверено по dex прошивки ТВ):
//  * Служба открыта всем (exported, прав не требует), в коде нет проверки вызывающего.
//  * register() НЕ вызываем: слот клиента ОДИН (второй register с другим токеном молча заменяет первого, токены потом не сойдутся),
//    а register() и deregister() (с ЛЮБЫМ токеном) гонят в железо весь кэш службы (updateMs12State): и то, что записала эта проба,
//    и сброс трёх полей (Atmos-lock выкл, режим каналов AUTO, PCM-сжатие = 1 «кино стандарт»), AAC-уровень 92.
//    Кто сейчас держит слот (калибровка Flex или никто), НЕ проверено. Все ручки, что здесь есть, регистрации не требуют.
//  * Геттеров текущего значения у «чёткости диалогов» и «выравнивания громкости» в службе НЕТ. Геттеры DRC и downmix отдают
//    кэш самой службы (нули, пока никто не писал), а не железо. Если процесс службы перезапустят отдельно от HAL,
//    кэш обнулится, а железо сохранит записанное: «кэш» и железо могут разойтись.
//  * Всё держится только в памяти службы (SharedPreferences/Settings/файлов в ней нет); у HAL хранение на диске не проверялось.
//    Перезагрузка ТВ сбрасывает всё. После падения и переподключения HAL служба кэш заново НЕ заливает (connect() не зовёт updateMs12State).
//  * Вызывать только не из главного потока. Ошибки службы (её RemoteException) приходят как transact==false.
//  * Эксперименты flexr и centermode (Flexible Renderer, возможно связан с Flex-колонками TCL) пишутся только со значением, начинающимся с «force:»
//    (например --es val force:1): эффект на звук не проверен, поэтому случайно они не включатся. restore пишет их без этой приставки.
final class DolbyProbe {
    private DolbyProbe() { }

    private static final String TAG = "guard";
    static final String PKG = "com.dolby.android.audio.service";
    private static final String CLS = PKG + ".DolbyAudioService";
    // onBind() проверяет действие по имени класса интерфейса: IDolbyAudioService даёт mBinder; vendor.dolby.audio.measurement.ILatencyReporter
    // даёт другой бинд (задержка); IDolbyAudioServiceInternal и IDolbyAudioServicePrivate из манифеста дают null (DolbyAudioService.onBind)
    private static final String ACTION = PKG + ".IDolbyAudioService";
    // IDolbyAudioService.DESCRIPTOR: "com$dolby$android$audio$service$IDolbyAudioService".replace('$', '.')
    private static final String DESC = "com.dolby.android.audio.service.IDolbyAudioService";

    // Номер транзакции = FIRST_CALL_TRANSACTION + n, n по порядку объявления метода в AIDL. В комментарии итоговый номер.
    private static final int F = IBinder.FIRST_CALL_TRANSACTION;
    private static final int T_VERSION = IBinder.LAST_CALL_TRANSACTION;      // getInterfaceVersion, 16777215
    private static final int T_HASH = IBinder.LAST_CALL_TRANSACTION - 1;     // getInterfaceHash, 16777214
    private static final int T_FEATURE = F + 76;            // 77  boolean isFeatureSupported(int feature)
    private static final int T_G_DOWNMIX = F + 13;          // 14  int getDownmixMode()
    private static final int T_G_DOWNMIX2 = F + 15;         // 16  int getDownmixModeSecondary()
    private static final int T_G_DRCMODE = F + 17;          // 18  int getDrcModeStereo()
    private static final int T_G_DRCMODE2 = F + 19;         // 20  int getDrcModeSecondary()
    private static final int T_G_DRCMODEMC = F + 21;        // 22  int getDrcModeMultichannel()
    private static final int T_G_DRCBOOST = F + 35;         // 36  int getDrcBoostStrengthStereo()
    private static final int T_G_DRCCUT = F + 38;           // 39  int getDrcCutStrengthStereo()
    private static final int T_G_DRCBOOST2 = F + 29;        // 30  int getDrcBoostStrengthSecondary()
    private static final int T_G_DRCCUT2 = F + 32;          // 33  int getDrcCutStrengthSecondary()
    private static final int T_G_DRCBOOSTMC = F + 23;       // 24  int getDrcBoostStrengthMultichannel()
    private static final int T_G_DRCCUTMC = F + 26;         // 27  int getDrcCutStrengthMultichannel()
    private static final int T_R_DE = F + 54;               // 55  int[] getDialogueEnhancerStrengthRange()
    private static final int T_R_DEALL = F + 55;            // 56  int[] getAllDialogueEnhancerStrengthRange()
    private static final int T_R_AC4DE = F + 43;            // 44  int[] getAc4DialogueEnhancerStrengthRange()
    private static final int T_R_VLSTR = F + 48;            // 49  int[] getVolumeLevelerStrengthRange()
    private static final int T_R_DRCBOOST = F + 36;         // 37  int[] getDrcBoostStrengthStereoRange()
    private static final int T_R_DRCCUT = F + 39;           // 40  int[] getDrcCutStrengthStereoRange()
    private static final int T_R_PCMGAIN = F + 84;          // 85  float[] getPcmPlaybackStreamGainRange()
    private static final int T_R_AACREF = F + 41;           // 42  int[] getAacProgramReferenceLevelRange()
    private static final int T_R_ANGLE = F + 59;            // 60  int[] getSpeakerVirtualizerAngleRange()
    private static final int T_R_STARTFREQ = F + 61;        // 62  int[] getSpeakerVirtualizerStartFrequencyRange()
    private static final int T_R_SURBOOST = F + 63;         // 64  int[] getVirtualizerSurroundBoostStrengthRange()
    private static final int T_G_VIRTMODES = F + 45;        // 46  int[] getSupportedVirtualizerModes()
    private static final int T_G_PROFILES = F + 71;         // 72  List<String> getAvailableTuningProfiles(int deviceType)
    private static final int T_G_DUMPON = F + 78;           // 79  boolean isAudioDumpOn()
    private static final int T_G_DUMPMODE = F + 80;         // 81  int getAudioDumpFileMode()
    private static final int T_G_CONTIN = F + 82;           // 83  boolean isContinuousOutputOn()
    private static final int T_S_DE = F + 53;               // 54  int setDialogueEnhancerStrength(int)
    private static final int T_S_DEALL = F + 10;            // 11  int setAllDialogueEnhancerStrength(int)
    private static final int T_S_VLMODE = F + 46;           // 47  int setVolumeLevelerMode(int)
    private static final int T_S_VLSTR = F + 47;            // 48  int setVolumeLevelerStrength(int)
    private static final int T_S_DRCMODE = F + 16;          // 17  int setDrcModeStereo(int)
    private static final int T_S_DRCBOOST = F + 34;         // 35  int setDrcBoostStrengthStereo(int)
    private static final int T_S_DRCCUT = F + 37;           // 38  int setDrcCutStrengthStereo(int)
    private static final int T_S_DOWNMIX = F + 12;          // 13  int setDownmixMode(int)
    private static final int T_S_PCMGAIN = F + 83;          // 84  int setPcmPlaybackStreamGain(float)
    private static final int T_S_FLEXR = F + 68;            // 69  int enableFlexibleRendering(boolean)
    private static final int T_S_CENTER = F + 70;           // 71  int setFlexibleRendererCenterExtractionMode(int)

    // Feature (vendor.dolby.audio.ms12.Feature), индекс = значение
    private static final String[] FEATURES = {
        "AAC_PROGRAM_REFERENCE_LEVEL", "AC4_DIALOGUE_ENHANCER", "AC4_PRESENTATION_PREFERENCE", "ATMOS_INDICATOR",
        "BITSTREAM_OUTPUT_ATMOS_LOCK", "BITSTREAM_OUTPUT_CHANNEL_MODE", "DIALOGUE_ENHANCER", "DOWNMIX_MODE",
        "DRC_BOOST_STRENGTH_MULTICHANNEL", "DRC_BOOST_STRENGTH_STEREO", "DRC_CUT_STRENGTH_MULTICHANNEL", "DRC_CUT_STRENGTH_STEREO",
        "DRC_MODE_MULTICHANNEL", "DRC_MODE_STEREO", "VIRTUALIZER", "VIRTUALIZER_HEADPHONE_REVERB",
        "VIRTUALIZER_SPEAKER_ANGLE", "VIRTUALIZER_SPEAKER_START_FREQUENCY", "VIRTUALIZER_SURROUND_BOOST", "VOLUME_LEVELER",
        "ASSOCIATED_MIXING", "BITSTREAM_OUTPUT_ATMOS_ALLOW", "OUTPUT_CHANNEL_CAPABILITY_INDICATOR", "AC4_MAIN_ASSOCIATED_MIX_LEVEL",
        "BITSTREAM_PASSTHROUGH", "DIALOGUE_ENHANCER_ALL", "DOWNMIX_MODE_SECONDARY", "DRC_BOOST_STRENGTH_SECONDARY",
        "DRC_CUT_STRENGTH_SECONDARY", "DRC_MODE_SECONDARY", "OUTPUT_SECONDARY", "TUNING_HEADPHONE",
        "TUNING_SPEAKER", "AUDIO_DEVICE_SUPPORTED_PROFILES", "FLEXIBLE_RENDERING", "MAT_OUTPUT_SINGLE_OBJECT_AUDIO_ELEMENT",
        "PROGRAM_PCM_COMPRESSION_PROFILE"
    };

    // ---------- что умеет set(): имя, транзакция, тип значения, откуда брать допустимый диапазон ----------

    private static final int INT = 0, ENUM = 1, BOOL = 2, FLOAT = 3;

    private static final class P {
        final String name; final int code; final int kind; final int rangeCode; final int[] allowed; final String about;
        final boolean risky;   // эксперимент: set требует приставку force: в значении
        P(String name, int code, int kind, int rangeCode, int[] allowed, String about) {
            this.name = name; this.code = code; this.kind = kind; this.rangeCode = rangeCode; this.allowed = allowed; this.about = about;
            this.risky = name.equals("flexr") || name.equals("centermode");
        }
    }

    // Порядок = рекомендуемый порядок проб: сначала выравнивание громкости и диалоги, DRC в самом конце
    private static final P[] PARAMS = {
        new P("vlmode", T_S_VLMODE, ENUM, 0, new int[]{0, 1, 2}, "выравнивание громкости: 0 выкл, 1 вкл, 2 вкл для невыровненного"),
        new P("vlstrength", T_S_VLSTR, INT, T_R_VLSTR, null, "выравнивание громкости: сила (диапазон из железа)"),
        new P("de", T_S_DE, INT, T_R_DE, null, "чёткость диалогов: сила (диапазон из железа), 0 = выкл"),
        new P("deall", T_S_DEALL, INT, T_R_DEALL, null, "чёткость диалогов для всех форматов (DIALOGUE_ENHANCER_ALL), сила"),
        new P("pcmgain", T_S_PCMGAIN, FLOAT, T_R_PCMGAIN, null, "усиление PCM-потока (единица не проверена, диапазон из железа)"),
        new P("flexr", T_S_FLEXR, BOOL, 0, null, "гибкий рендерер вкл/выкл (эксперимент, значение с приставкой force:, например force:1)"),
        new P("centermode", T_S_CENTER, ENUM, 0, new int[]{0, 1, 2}, "выделение центра: 0 выкл, 1 только L/R, 2 все (эксперимент, значение с приставкой force:, например force:2)"),
        new P("drcmode", T_S_DRCMODE, ENUM, 0, new int[]{0, 1}, "DRC стерео: 0 LINE, 1 RF; ПЕРЕСЫЛАЕТ boost/cut/downmix из кэша службы"),
        new P("drcboost", T_S_DRCBOOST, INT, T_R_DRCBOOST, null, "DRC стерео: подъём тихого; пересылает остальное из кэша службы"),
        new P("drccut", T_S_DRCCUT, INT, T_R_DRCCUT, null, "DRC стерео: срез громкого; пересылает остальное из кэша службы"),
        new P("downmix", T_S_DOWNMIX, ENUM, 0, new int[]{0, 1, 2}, "downmix стерео: 0 LTRT, 1 LORO, 2 ARIB; пересылает остальное из кэша службы"),
    };

    // Что считаем «как было», если вернуть точнее нечем (геттера нет). Значения службы по умолчанию (mCenterExtractionMode=1 в конструкторе)
    // и базовая настройка DAP на ТВ (audioDAP_MT9618.ini: volume-leveler-enable=0 строка 20, dialog-enhancer-enable=0 строка 66).
    // Реальное текущее состояние железа этим НЕ подтверждено: его читать нечем.
    private static final String NEUTRAL = "vlmode=0,de=0,deall=0,flexr=0,centermode=1";
    // Ключи, у которых есть геттер в службе (отдаёт кэш службы)
    private static final String[] WITH_GETTER = {"downmix", "drcmode", "drcboost", "drccut"};

    // Что менял этот процесс (для restore без аргументов) и исходные значения тех, у кого есть геттер
    private static final Set<String> touched = new LinkedHashSet<>();
    private static final Map<String, String> orig = new LinkedHashMap<>();

    private static final long BUDGET_MS = 15_000;

    // ---------- вход для Cmd ----------

    // op: dump | set | restore. key/val только для set, snap только для restore (пусто: откатить то, что менял этот процесс)
    static String run(Context c, String op, String key, String val, String snap) {
        if (op == null || "dump".equals(op)) return dump(c);
        if ("set".equals(op)) return set(c, key, val);
        if ("restore".equals(op)) return restore(c, snap);
        if ("help".equals(op)) return help();
        return log("dolby: неизвестная операция «" + op + "»; есть dump, set (key, val), restore [snap]");
    }

    static String help() {
        StringBuilder sb = new StringBuilder("dolby: операции dump | set --es key ИМЯ --es val ЗНАЧЕНИЕ | restore [--es snap neutral|k=v,...] | help\nимена для set:\n");
        for (P p : PARAMS) sb.append("  ").append(p.name).append(": ").append(p.about).append('\n');
        return sb.toString();
    }

    // ---------- dump: только чтение, ничего не меняет ----------

    static synchronized String dump(Context c) {
        StringBuilder sb = new StringBuilder();
        Conn k = null;
        long end = SystemClock.elapsedRealtime() + BUDGET_MS;
        try {
            k = open(c);
            IBinder b = k.binder;
            Fails f = new Fails();

            add(sb, "интерфейс: версия=" + gi(b, T_VERSION) + " хэш=" + safe(() -> call(b, T_HASH, null, r -> r.readString())));

            // Что реально поддерживает аудио-HAL (IMs12). Первый же отказ HAL заметен: остальные пропускаем
            StringBuilder fs = new StringBuilder();
            for (int i = 0; i < FEATURES.length && f.ok() && SystemClock.elapsedRealtime() < end; i++) {
                final int idx = i;
                try {
                    boolean yes = call(b, T_FEATURE, d -> d.writeInt(idx), r -> r.readInt() != 0).booleanValue();
                    fs.append(FEATURES[i]).append('=').append(yes ? "да" : "нет").append(' ');
                    f.good();
                } catch (Throwable e) { fs.append(FEATURES[i]).append("=ошибка(").append(e.getMessage()).append(") "); f.bad(); }
            }
            add(sb, "HAL поддерживает: " + fs.toString().trim());
            if (!f.ok()) {
                add(sb, "HAL не отвечает (службе нечего вернуть: IMs12 не подключён или отказал). Дальше только кэш службы.");
            } else {
                add(sb, "диапазоны из железа (две цифры, порядок мин/макс не проверен, код берёт меньшую как нижнюю): de=" + rng(b, T_R_DE) + " deall=" + rng(b, T_R_DEALL) + " ac4de=" + rng(b, T_R_AC4DE)
                        + " vlstrength=" + rng(b, T_R_VLSTR) + " drcboost=" + rng(b, T_R_DRCBOOST) + " drccut=" + rng(b, T_R_DRCCUT)
                        + " aacref=" + rng(b, T_R_AACREF) + " угол=" + rng(b, T_R_ANGLE) + " старт=" + rng(b, T_R_STARTFREQ)
                        + " surroundboost=" + rng(b, T_R_SURBOOST) + " pcmgain=" + frng(b, T_R_PCMGAIN));
                add(sb, "режимы виртуализатора: " + safe(() -> ints(call(b, T_G_VIRTMODES, null, r -> r.createIntArray())))
                        + "; профили настройки: динамики=" + profiles(b, 0) + " наушники=" + profiles(b, 1));
            }

            // Кэш службы (ПЕРЕПИСЫВАЕТСЯ ТОЛЬКО ЧЕРЕЗ ЭТУ СЛУЖБУ; железо мог менять и TCL напрямую)
            String boost = gi(b, T_G_DRCBOOST), cut = gi(b, T_G_DRCCUT), mode = gi(b, T_G_DRCMODE), dmx = gi(b, T_G_DOWNMIX);
            add(sb, "кэш службы, не железо: стерео boost=" + boost + " cut=" + cut + " режим=" + mode + " downmix=" + dmx
                    + "; второй выход boost=" + gi(b, T_G_DRCBOOST2) + " cut=" + gi(b, T_G_DRCCUT2) + " режим=" + gi(b, T_G_DRCMODE2) + " downmix=" + gi(b, T_G_DOWNMIX2)
                    + "; многоканал boost=" + gi(b, T_G_DRCBOOSTMC) + " cut=" + gi(b, T_G_DRCCUTMC) + " режим=" + gi(b, T_G_DRCMODEMC));
            add(sb, "служебные флаги: audioDump=" + gb(b, T_G_DUMPON) + " режим файла=" + gi(b, T_G_DUMPMODE) + " непрерывный вывод=" + gb(b, T_G_CONTIN));

            String snap = "downmix=" + dmx + ",drcmode=" + mode + ",drcboost=" + boost + ",drccut=" + cut;
            add(sb, "СНИМОК(кэш службы): " + snap);
            add(sb, "без геттера (текущее значение узнать нельзя): " + names(false) + ". Нейтральные: " + NEUTRAL);
            for (String key : WITH_GETTER) {
                String v0 = valueOf(snap, key);
                if (!orig.containsKey(key) && v0 != null && v0.matches("-?\\d+")) orig.put(key, v0);
            }
        } catch (Throwable e) {
            add(sb, "ОШИБКА dump: " + e);
        } finally {
            close(c, k);
        }
        return sb.toString();
    }

    // ---------- set: одно значение, с проверкой диапазона ----------

    static synchronized String set(Context c, String name, String value) {
        P p = find(name);
        if (p == null) return log("dolby: неизвестное имя «" + name + "»; можно: " + names(true));
        if (value == null || value.trim().isEmpty()) return log("dolby: " + name + ": не задано значение (--es val ...)");
        String v = value.trim();
        if (v.startsWith("force:")) v = v.substring(6).trim();
        else if (p.risky) return log("dolby: " + name + " экспериментальный, эффект на звук не проверен; чтобы записать, допиши приставку: --es val force:" + v);
        Conn k = null;
        try {
            k = open(c);
            return log(apply(k.binder, p, v));
        } catch (Throwable e) {
            return log("dolby: ОШИБКА " + name + ": " + e);
        } finally {
            close(c, k);
        }
    }

    // ---------- restore: вернуть ----------
    // snap пусто: откатить только то, что менял ЭТОТ процесс (геттерные ключи к значению первого чтения, остальные к нейтральным).
    // snap = "neutral" или "k=v,k=v": записать явно (neutral тоже можно дописать: "neutral,drcmode=0")

    static synchronized String restore(Context c, String snap) {
        Map<String, String> todo = new LinkedHashMap<>();
        if (snap == null || snap.trim().isEmpty()) {
            if (touched.isEmpty()) return log("dolby: откатывать нечего, этот процесс ничего не менял. Явно: --es snap neutral или k=v,...");
            Map<String, String> neutral = parse(NEUTRAL);
            for (String key : touched) {
                if (orig.containsKey(key)) todo.put(key, orig.get(key));
                else if (neutral.containsKey(key)) todo.put(key, neutral.get(key));
                else todo.put(key, null); // нейтрального нет: скажем об этом
            }
        } else {
            for (String part : snap.trim().split("[,;\\s]+")) {
                if (part.equals("neutral")) { for (Map.Entry<String, String> e : parse(NEUTRAL).entrySet()) if (!todo.containsKey(e.getKey())) todo.put(e.getKey(), e.getValue()); }
                else if (part.contains("=")) { String[] kv = part.split("=", 2); todo.put(kv[0], kv[1]); }
            }
        }
        StringBuilder sb = new StringBuilder();
        Conn k = null;
        try {
            k = open(c);
            // порядок как в PARAMS: DRC и downmix идут последними (каждый вызов шлёт все четыре значения)
            for (P p : PARAMS) {
                if (!todo.containsKey(p.name)) continue;
                String v = todo.get(p.name);
                if (v == null) { add(sb, p.name + ": нейтрального значения нет, верни вручную (или перезагрузкой ТВ)"); continue; }
                try { add(sb, apply(k.binder, p, v)); } catch (Throwable e) { add(sb, p.name + ": не записано: " + e); }
            }
            for (String key : todo.keySet()) if (find(key) == null) add(sb, "пропущено неизвестное «" + key + "»");
            // откат выполнен: снимаем пометки
            for (String key : todo.keySet()) touched.remove(key);
        } catch (Throwable e) {
            add(sb, "ОШИБКА restore: " + e);
        } finally {
            close(c, k);
        }
        return sb.toString();
    }

    // ---------- запись одного параметра ----------

    private static String apply(IBinder b, P p, String v) throws Exception {
        String q0 = hasGetter(p.name) ? quad(b) : null;
        int rc;
        switch (p.kind) {
            case INT: {
                final int x = Integer.parseInt(v);
                int[] r = call(b, p.rangeCode, null, in -> in.createIntArray());
                if (r == null || r.length < 2) return ("dolby: " + p.name + ": диапазон из железа не прочитан, запись отклонена");
                int lo = Math.min(r[0], r[1]), hi = Math.max(r[0], r[1]);
                if (x < lo || x > hi) return ("dolby: " + p.name + "=" + x + " вне диапазона [" + lo + "," + hi + "], запись отклонена");
                remember(b, p);
                rc = call(b, p.code, d -> d.writeInt(x), in -> in.readInt());
                break;
            }
            case ENUM: {
                final int x = Integer.parseInt(v);
                boolean okv = false;
                for (int a : p.allowed) if (a == x) okv = true;
                if (!okv) return ("dolby: " + p.name + "=" + x + " не из допустимых " + ints(p.allowed) + ", запись отклонена");
                remember(b, p);
                rc = call(b, p.code, d -> d.writeInt(x), in -> in.readInt());
                break;
            }
            case BOOL: {
                final boolean x = v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("да");
                final boolean no = v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("нет");
                if (!x && !no) return ("dolby: " + p.name + ": жду 0/1/true/false, пришло «" + v + "»");
                remember(b, p);
                rc = call(b, p.code, d -> d.writeInt(x ? 1 : 0), in -> in.readInt());
                break;
            }
            default: { // FLOAT
                final float x = Float.parseFloat(v.replace(',', '.'));
                float[] r = call(b, p.rangeCode, null, in -> in.createFloatArray());
                if (r == null || r.length < 2) return ("dolby: " + p.name + ": диапазон из железа не прочитан, запись отклонена");
                float lo = Math.min(r[0], r[1]), hi = Math.max(r[0], r[1]);
                if (x < lo || x > hi) return ("dolby: " + p.name + "=" + x + " вне диапазона [" + lo + "," + hi + "], запись отклонена");
                remember(b, p);
                rc = call(b, p.code, d -> d.writeFloat(x), in -> in.readInt());
            }
        }
        if (rc == 0) touched.add(p.name);
        String res = "dolby: " + p.name + "=" + v + " → " + (rc == 0 ? "OK (0)" : "ОТКАЗ (" + rc + "): HAL не принял, смотри logcat IDolbyAudioServiceImpl");
        if (q0 != null) res += "; кэш DRC было [" + q0 + "] стало [" + quad(b) + "]; остальные три ушли в железо из кэша";
        return res;
    }

    private static boolean hasGetter(String name) { for (String s : WITH_GETTER) if (s.equals(name)) return true; return false; }

    // Запоминаем исходное значение геттерного ключа до первой записи
    private static void remember(IBinder b, P p) {
        if (!hasGetter(p.name) || orig.containsKey(p.name)) return;
        try {
            int code = p.name.equals("downmix") ? T_G_DOWNMIX : p.name.equals("drcmode") ? T_G_DRCMODE : p.name.equals("drcboost") ? T_G_DRCBOOST : T_G_DRCCUT;
            orig.put(p.name, Integer.toString(ci(b, code)));
        } catch (Throwable e) { Log.w(TAG, "dolby: исходное " + p.name + " не прочитано", e); }
    }

    private static String quad(IBinder b) {
        return "boost=" + gi(b, T_G_DRCBOOST) + " cut=" + gi(b, T_G_DRCCUT) + " режим=" + gi(b, T_G_DRCMODE) + " downmix=" + gi(b, T_G_DOWNMIX);
    }

    // ---------- Binder ----------

    private interface Out { void write(Parcel p); }
    private interface In<T> { T read(Parcel p); }

    private static <T> T call(IBinder b, int code, Out out, In<T> in) throws Exception {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            if (out != null) out.write(d);
            if (!b.transact(code, d, r, 0))
                throw new IllegalStateException("transact=false, код " + code + " (служба отказала: её RemoteException, например HAL не подключён)");
            r.readException();
            return in == null ? null : in.read(r);
        } finally {
            r.recycle();
            d.recycle();
        }
    }

    private static final Executor DIRECT = new Executor() { @Override public void execute(Runnable r) { r.run(); } };

    private static final class Conn implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder binder;
        @Override public void onServiceConnected(ComponentName n, IBinder s) { binder = s; ready.countDown(); }
        @Override public void onServiceDisconnected(ComponentName n) { binder = null; }
        @Override public void onBindingDied(ComponentName n) { binder = null; ready.countDown(); }
        @Override public void onNullBinding(ComponentName n) { ready.countDown(); }
    }

    private static Conn open(Context c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("нельзя из главного потока");
        if (!Caps.tcl()) throw new IllegalStateException("не TCL: закрытая служба Dolby не вызывается");
        Context app = c.getApplicationContext();
        Intent i = new Intent(ACTION).setComponent(new ComponentName(PKG, CLS));
        Conn k = new Conn();
        boolean ok = app.bindService(i, Context.BIND_AUTO_CREATE, DIRECT, k);
        if (!ok) {
            try { app.unbindService(k); } catch (Exception ignored) { }
            throw new IllegalStateException("bindService=false: в манифесте Guard нужен <queries><package android:name=\"" + PKG + "\"/>, либо службы нет");
        }
        if (!k.ready.await(6, TimeUnit.SECONDS) || k.binder == null) {
            try { app.unbindService(k); } catch (Exception ignored) { }
            throw new IllegalStateException("служба не отозвалась за 6 с (onNullBinding: действие не то)");
        }
        try {
            verify(k.binder);
        } catch (Exception e) {
            try { app.unbindService(k); } catch (Exception ignored) { }
            throw e;
        }
        return k;
    }

    // Номера вызовов выше сняты с интерфейса версии 3 (прошивка AU02). Другая версия или хэш: номера могут значить другое,
    // вплоть до записи вместо чтения, поэтому ни один вызов, кроме версии и хэша, не делаем (план Codex, блок 5)
    private static volatile boolean verified;

    private static void verify(IBinder b) throws Exception {
        if (verified) return;
        int v = ci(b, T_VERSION);
        String hash = call(b, T_HASH, null, r -> r.readString());
        if (!CapsRules.dolbyOk(v, hash))
            throw new IllegalStateException("интерфейс Dolby другой: версия " + v + ", хэш " + hash + " (проверены версия "
                    + CapsRules.DOLBY_VERSION + " и хэш " + CapsRules.DOLBY_HASH + "), вызовы отключены");
        verified = true;
    }

    // Для паспорта ТВ (do caps): отвечает ли служба Dolby и тот ли у неё интерфейс. Не из главного потока
    static synchronized String check(Context c) {
        Conn k = null;
        try {
            k = open(c);
            return "служба отвечает, интерфейс версии " + CapsRules.DOLBY_VERSION + " с тем же хэшем";
        } catch (Throwable e) {
            return "нет (" + e.getMessage() + ")";
        } finally {
            close(c, k);
        }
    }

    private static void close(Context c, Conn k) {
        if (k == null) return;
        try { c.getApplicationContext().unbindService(k); } catch (Exception e) { Log.w(TAG, "dolby: unbind", e); }
    }

    // Три подряд отказа HAL: дальше не ждём (каждый отказ при мёртвом HAL — до 3 с ожидания внутри службы)
    private static final class Fails {
        int n;
        boolean ok() { return n < 3; }
        void bad() { n++; }
        void good() { n = 0; }
    }

    // ---------- мелочи ----------

    private interface Thrower { String get() throws Exception; }

    private static String safe(Thrower t) { try { return t.get(); } catch (Throwable e) { return "ошибка(" + e.getMessage() + ")"; } }

    private static int ci(IBinder b, int code) throws Exception { return call(b, code, null, r -> r.readInt()); }

    private static String gi(IBinder b, int code) { return safe(() -> Integer.toString(ci(b, code))); }

    private static String gb(IBinder b, int code) { return safe(() -> Boolean.toString(call(b, code, null, r -> r.readInt() != 0))); }

    private static String rng(IBinder b, int code) { return safe(() -> ints(call(b, code, null, r -> r.createIntArray()))); }

    private static String frng(IBinder b, int code) {
        return safe(() -> {
            float[] a = call(b, code, null, r -> r.createFloatArray());
            return a == null ? "null" : "[" + String.format(Locale.ROOT, "%.2f,%.2f", a[0], a[1]) + "]";
        });
    }

    private static String profiles(IBinder b, int deviceType) {
        return safe(() -> {
            List<String> l = call(b, T_G_PROFILES, d -> d.writeInt(deviceType), r -> r.createStringArrayList());
            return l == null ? "null" : l.toString();
        });
    }

    private static String ints(int[] a) {
        if (a == null) return "null";
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) s.append(i == 0 ? "" : ",").append(a[i]);
        return s.append(']').toString();
    }

    private static P find(String name) {
        if (name == null) return null;
        for (P p : PARAMS) if (p.name.equals(name.trim())) return p;
        return null;
    }

    private static String names(boolean all) {
        List<String> l = new ArrayList<>();
        for (P p : PARAMS) if (all || !hasGetter(p.name)) l.add(p.name);
        return String.join(", ", l);
    }

    private static Map<String, String> parse(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : s.split("[,;\\s]+")) if (part.contains("=")) { String[] kv = part.split("=", 2); m.put(kv[0], kv[1]); }
        return m;
    }

    private static String valueOf(String snap, String key) { return parse(snap).get(key); }

    private static void add(StringBuilder sb, String line) {
        sb.append(line).append('\n');
        Log.i(TAG, "dolby: " + line);
    }

    private static String log(String s) { Log.i(TAG, s); return s; }
}
