package app.glasstv.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Самообновление приложений HYDE (выпуск 1.0.1): правила без Android, проверка на ПК (test/HydeRulesTest.java).
// В выпуске на GitHub рядом с APK лежит опись hyde-update.json (собирает build/release.sh): номер выпуска, с какой версии
// он ставится без установщика (silent_from), отпечаток ключа и список APK с версиями, размерами и суммами.
// «Сторож» обновляет только те приложения HYDE, что стоят на ТВ, подписаны ключом выпуска и поставлены от его имени.
// Подпись каждого скачанного файла сверяется с ключом, зашитым ниже: файл с другим ключом не ставится, даже если его
// подложили в выпуск. Поставить поверх чужую подпись не даст и сам Android
final class HydeRules {
    private HydeRules() { }

    // SHA-256 сертификата ключа выпуска HYDE TV (docs/release.md)
    static final String RELEASE_CERT = "f3fdaa1db069a0edf944d4d5cccb735a7825007ce8b4ea946e6404d141b3a2ac";
    static final int SCHEMA = 1;
    static final String RELEASES = "https://github.com/artemhyde/artemhyde_TV/releases/";
    static final String LATEST = RELEASES + "latest/download/hyde-update.json";
    static final String HOME = "app.glasstv.home";

    static final long USER_MS = 2 * 60_000;         // пультом пользовались меньше 2 минут назад: человек у ТВ, ничего не ставим
    static final long HOME_IDLE_MS = 10 * 60_000;   // «Дом» на экране: ставить его, только если пульт не трогали 10 минут

    static final String FRESH = "fresh", INSTALLER = "installer", UPDATE = "update";

    static final class App {
        String pkg, file, sha256;
        long code, size;
    }

    static final class Release {
        int schema = SCHEMA;
        String version, signer, why;   // why: почему выпуску нужен установщик, словами сборки (для отчёта)
        long code, silentFrom;
        final List<App> apps = new ArrayList<>();
    }

    // Что стоит на ТВ
    static final class Have {
        long code;
        String installer, cert;
        boolean admin;   // у приложения включены права администратора устройства
    }

    // Шаг плана: приложение, которое в выпуске новее стоящего; skip: почему не ставим (null: ставим).
    // phase: 0 остальные, 1 «Дом», 2 сам «Сторож». Фазы идут по порядку: «Дом» после остальных, «Сторож» последним,
    // потому что установка закрывает его процесс
    static final class Step {
        final App app;
        final String skip;
        final int phase;
        Step(App app, String skip, int phase) { this.app = app; this.skip = skip; this.phase = phase; }
    }

    interface Gate { String why(Step s); }   // null: ставить можно прямо сейчас, иначе почему ждём

    private static final Pattern VERSION = Pattern.compile("\\d{1,3}\\.\\d{1,2}\\.\\d{1,2}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]{1,120}\\.apk");
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    // Опись в порядке? null: да, иначе что не так. Имя файла идёт в адрес, поэтому только простое имя без папок
    static String problem(Release r) {
        if (r.schema > SCHEMA) return null;   // новый формат: дальше не читаем, нужен установщик (verdict)
        if (r.version == null || !VERSION.matcher(r.version).matches()) return "непонятный номер выпуска " + r.version;
        if (r.code <= 0) return "нет version_code";
        if (r.apps.isEmpty()) return "в описи нет приложений";
        for (App a : r.apps) {
            if (a.pkg == null || !PKG.matcher(a.pkg).matches()) return "непонятный пакет " + a.pkg;
            if (a.file == null || !FILE.matcher(a.file).matches()) return a.pkg + ": непонятное имя файла " + a.file;
            if (a.sha256 == null || !HEX64.matcher(a.sha256).matches()) return a.pkg + ": нет суммы SHA-256";
            if (a.code <= 0) return a.pkg + ": нет version_code";
            if (a.size <= 0) return a.pkg + ": нет размера файла";
        }
        return null;
    }

    static boolean anyNewer(Release r, Map<String, Have> have) {
        for (App a : r.apps) {
            Have h = have.get(a.pkg);
            if (h != null && h.code < a.code) return true;
        }
        return false;
    }

    // myCode: версия самого «Сторожа», она же версия HYDE на этом ТВ (модули ставятся одним выпуском)
    static String verdict(Release r, long myCode, Map<String, Have> have) {
        if (r.schema > SCHEMA) return r.code > myCode ? INSTALLER : FRESH;
        if (!anyNewer(r, have)) return FRESH;
        return installerWhy(r, myCode) != null ? INSTALLER : UPDATE;
    }

    // Почему без установщика на компьютере нельзя, или null
    static String installerWhy(Release r, long myCode) {
        if (r.schema > SCHEMA) return "новый формат описи выпуска";
        if (r.signer == null || !RELEASE_CERT.equalsIgnoreCase(r.signer)) return "у выпуска другой ключ подписи";
        if (myCode < r.silentFrom) return "в выпуске новые права или настройки";
        return null;
    }

    static List<Step> plan(Release r, Map<String, Have> have, String self) {
        List<Step> l = new ArrayList<>();
        for (int phase = 0; phase < 3; phase++) {
            for (App a : r.apps) {
                int ph = a.pkg.equals(self) ? 2 : HOME.equals(a.pkg) ? 1 : 0;
                if (ph != phase) continue;
                Have h = have.get(a.pkg);
                if (h == null || h.code >= a.code) continue;
                String skip = h.cert == null || !RELEASE_CERT.equalsIgnoreCase(h.cert) ? "подписано другим ключом"
                        // переустановка поверх активного администратора вешает систему примерно на минуту (docs/updates.md)
                        : h.admin ? "включены права администратора, обновит установщик на компьютере"
                        // тихо Android обновляет, только если установщик приложения сам «Сторож» (или он обновляет себя)
                        : !a.pkg.equals(self) && !self.equals(h.installer) ? "ставил не «Сторож», обновит установщик на компьютере"
                        : null;
                l.add(new Step(a, skip, ph));
            }
        }
        return l;
    }

    // Можно ли ставить приложение сейчас. busy: идёт видео или музыка; sinceInput: мс с последнего нажатия (-1: не видно);
    // top: верхнее окно (null: не видно); home: это «Дом». null: можно, иначе чего ждём
    static String moment(boolean busy, boolean screenOn, long sinceInput, String top, String pkg, boolean home) {
        if (busy) return "жду конца видео или музыки";
        if (!screenOn) return null;   // экран погас: никто не увидит, как приложение закроется
        if (sinceInput >= 0 && sinceInput < USER_MS) return "жду, пока отложат пульт";
        if (top == null) return home && sinceInput >= HOME_IDLE_MS ? null : "жду погасшего экрана (окна не видно)";
        if (!pkg.equals(top)) return null;
        if (home && sinceInput >= HOME_IDLE_MS) return null;   // «Дом» на экране, но ТВ стоит без дела: никто не смотрит
        return home ? "жду простоя: на экране «Дом»" : "жду, пока закроют приложение";
    }

    // Что ставить сейчас: первый подходящий шаг в самой ранней фазе, где остались незаконченные; в позднюю фазу не идём,
    // пока в ранней что-то не закончено. finished: поставлено, пропущено или не вышло. wait[0]: почему ждём (первая причина)
    static Step pick(List<Step> plan, Set<String> finished, Gate gate, String[] wait) {
        int phase = -1;
        for (Step s : plan) {
            if (s.skip != null || finished.contains(s.app.pkg)) continue;
            if (phase >= 0 && s.phase > phase) break;
            phase = s.phase;
            String why = gate.why(s);
            if (why == null) return s;
            if (wait[0] == null) wait[0] = why;
        }
        return null;
    }

    // Адрес описи и файла. testBase: проверочный адрес выпуска (только командой с ПК) или null
    static String jsonUrl(String testBase) { return testBase != null ? testBase + "hyde-update.json" : LATEST; }

    static String apkUrl(String testBase, Release r, App a) {
        return testBase != null ? testBase + a.file : RELEASES + "download/v" + r.version + "/" + a.file;
    }

    // Проверочный адрес: https или сам ТВ (adb reverse с ПК), с косой чертой в конце
    static boolean testBaseOk(String u) {
        return u != null && u.endsWith("/") && u.indexOf(' ') < 0
                && (u.startsWith("https://") || u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:"));
    }

    // Строка на экране «Сторожа» (одна строка, подробности в отчёте и в команде do hyde). when: время последней проверки;
    // chain: выпуск, который ставится; current: что ставится прямо сейчас; wait: чего ждём; skipped: кого обновит установщик
    static String brief(boolean on, String when, String need, String chain, String current, String wait, String err, String skipped) {
        if (!on) return "выключено";
        if (need != null) return "вышел " + need + " · нужен установщик на компьютере";
        if (chain != null) return current != null ? chain + " · ставлю " + current : chain + " скачан · " + (wait != null ? wait : "ставлю по одному");
        if (err != null) return "ошибка: " + err + (when != null ? " · " + when : "");
        if (skipped != null) return skipped + " обновит установщик на компьютере";
        return when == null ? "ещё не проверял" : "свежие · проверка " + when;
    }
}
