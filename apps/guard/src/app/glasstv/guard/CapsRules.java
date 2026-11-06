package app.glasstv.guard;

import java.util.Arrays;
import java.util.List;

// Правила паспорта ТВ (план Codex, блок 5) без Android: их проверяет тест на ПК (Guard/test/CapsRulesTest.java).
// Команды TCL только на TCL. Закрытый интерфейс Dolby только при совпадении версии и хэша: номера вызовов в DolbyProbe сняты
// с прошивки AU02, у другой версии интерфейса они могут значить другое. Пара «серия + прошивка», проверенная на живом ТВ,
// получает «проверено», остальные TCL «экспериментально» (закрытые вызовы с чтением результата), чужой бренд «нет».
final class CapsRules {
    private CapsRules() { }

    static final String VERIFIED_SERIES = "C6K";
    static final List<String> VERIFIED_FW = Arrays.asList(
            "TCL/G08_4K_GB/G08:14/UTT2.250416.001/AU02:user/release-keys");   // TCL 65C6K, проверено 06.11.2026
    static final int DOLBY_VERSION = 3;
    static final String DOLBY_HASH = "229b7cf5f6f02be7549d013d554fc6b1c2a63317";   // IDolbyAudioService на AU02

    static final String VERIFIED = "проверено", EXPERIMENTAL = "экспериментально", OTHER = "не TCL";

    static boolean isTcl(String manufacturer, String brand) {
        return "TCL".equalsIgnoreCase(trim(manufacturer)) || "TCL".equalsIgnoreCase(trim(brand));
    }

    static String level(String manufacturer, String brand, String series, String fingerprint) {
        if (!isTcl(manufacturer, brand)) return OTHER;
        if (VERIFIED_SERIES.equalsIgnoreCase(trim(series)) && VERIFIED_FW.contains(trim(fingerprint))) return VERIFIED;
        return EXPERIMENTAL;
    }

    static boolean dolbyOk(int version, String hash) {
        return version == DOLBY_VERSION && DOLBY_HASH.equals(trim(hash));
    }

    // Сменилась ли прошивка с прошлого запуска. Первая запись (прошлой нет) сменой не считается
    static boolean firmwareChanged(String before, String now) {
        return before != null && !before.isEmpty() && !before.equals(now);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
