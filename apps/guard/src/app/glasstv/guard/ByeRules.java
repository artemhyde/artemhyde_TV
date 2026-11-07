package app.glasstv.guard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Правила присмотра за ByeByeDPI без Android (07.11, модуль для России): их проверяет тест на ПК (Guard/test/ByeRulesTest.java).
// Наблюдение и запуск в Bye.java. Решение одно на всё: что сейчас с туннелем и включать ли его
final class ByeRules {
    private ByeRules() { }

    static final String PKG = "io.github.romanvht.byedpi";
    static final String TOGGLE = PKG + ".activities.ToggleActivity";   // выключатель автора без окна, его запуски окном не считаются

    // Решения, они же строка ByeByeDPI на экране «Сторожа» (коротко, как у соседних строк); подробности в отчёте и do vpn
    static final String OFF = "«Сторож» не присматривает";
    static final String MISSING = "не установлен";
    static final String UP = "работает · «Сторож» присматривает";
    static final String OTHER = "работает VPN другого приложения · не трогаю";
    static final String STOPPED = "остановлен в настройках · не включаю, пока не откроют";
    static final String HUMAN = "выключен человеком · не включаю до перезагрузки ТВ";
    static final String UPDATING = "обновляется · включу после";
    static final String SLEEP = "экран выключен · проверю при включении";
    static final String PLAYING = "выключен · включу, когда видео закончится";
    static final String LATER = "не включился · попробую ещё раз позже";
    static final String GAVE_UP = "не включается · откройте его и нажмите кнопку";
    static final String START = "включаю";

    static final long SELF_MS = 15_000;   // столько после своего запуска пропажа туннеля считается сбоем запуска, а не человеком

    // Что видно сейчас
    static final class See {
        boolean watch;       // присмотр включён: установщик после удачной загрузки списка приложений или команда do vpn
        boolean installed;
        boolean vpn;         // поднят какой-нибудь VPN
        boolean ours = true; // этот VPN от ByeByeDPI (по владельцу сети); не удалось узнать: считаем, что его
        boolean stopped;     // приложение остановлено принудительно (FLAG_STOPPED): так делает человек в настройках или adb
        boolean human;       // человек выключил его сам или включил другой VPN, и ТВ с тех пор не перезагружался
        boolean updating;    // «Сторож» сейчас ставит обновление ByeByeDPI
        boolean screen;      // экран горит
        boolean playing;     // идёт видео (декодер занят) или играет медиасессия
        int fails;           // неудачных запусков подряд
        long sinceTry = -1;  // мс с прошлой попытки; -1: попыток не было
        int recent;          // сколько раз «Сторож» включал его за последние LOOP_MS
    }

    // ByeByeDPI падает снова и снова: после MAX_STARTS запусков за 10 минут «Сторож» перестаёт, пока ТВ не перезагрузят.
    // Каждый запуск на мгновение ставит невидимое окно поверх того, что открыто, дёргать так экран без конца нельзя
    static final int MAX_STARTS = 3;
    static final long LOOP_MS = 10 * 60_000;

    static String decide(See s) {
        if (!s.watch) return OFF;
        if (!s.installed) return MISSING;
        if (s.vpn) return s.ours ? UP : OTHER;
        if (s.stopped) return STOPPED;
        if (s.human) return HUMAN;
        if (s.updating) return UPDATING;
        if (!s.screen) return SLEEP;
        if (s.playing) return PLAYING;
        long after = retryAfter(s.fails);
        if (after < 0 || s.recent >= MAX_STARTS) return GAVE_UP;
        if (s.sinceTry >= 0 && s.sinceTry < after) return LATER;
        return START;
    }

    // Сколько запусков из списка (SystemClock.elapsedRealtime) было за последние LOOP_MS к моменту now
    static int recent(List<Long> starts, long now) {
        int n = 0;
        for (long t : starts) if (now - t < LOOP_MS) n++;
        return n;
    }

    // Пауза после неудачных запусков подряд: минута, пять минут, дальше не пробуем до перезагрузки (что-то не так в самом
    // ByeByeDPI: нет разрешения на VPN, выбран режим прокси; без человека не починить, а дёргать его зря незачем)
    static long retryAfter(int fails) {
        if (fails <= 0) return 0;
        if (fails == 1) return 60_000;
        if (fails == 2) return 5 * 60_000;
        return -1;
    }

    // Туннель пропал: выключил ли его человек. window: окно ByeByeDPI было на экране после того, как туннель видели последний
    // раз (null: окна «Сторожу» не видно, нет права на историю использования); sinceSelf: мс с запуска ByeByeDPI «Сторожем»
    // (-1: не запускал); updated: ByeByeDPI обновили уже после того, как туннель видели (обновление само закрывает приложение).
    // Окна видно: решают они (свой запуск идёт через выключатель без окна и окном не считается). Окон не видно: сразу после
    // своего запуска это сбой запуска, иначе считаем, что выключил человек: лучше не включить лишний раз, чем спорить с ним
    static boolean byHuman(Boolean window, long sinceSelf, boolean updated) {
        if (updated) return false;
        if (window != null) return window;
        return sinceSelf < 0 || sinceSelf >= SELF_MS;
    }

    // ===== События окон (UsageStatsManager) =====
    static final int RESUMED = 1, PAUSED = 2, STOPPED_EV = 23;   // UsageEvents.Event: ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED

    static final class Ev {
        final long t;
        final int type;
        final String pkg, cls;
        Ev(long t, int type, String pkg, String cls) { this.t = t; this.type = type; this.pkg = pkg; this.cls = cls; }
    }

    // Было ли окно ByeByeDPI (любое, кроме невидимого выключателя) на экране в какой-то момент с since до конца событий.
    // Открыто на момент since (своё состояние у каждого окна: из главного в настройки события идут вперемешку) или
    // открывалось и закрывалось после since. События по возрастанию времени
    static boolean windowSince(List<Ev> evs, long since) {
        Set<String> open = new HashSet<>();
        for (Ev e : evs) {
            if (!PKG.equals(e.pkg) || TOGGLE.equals(e.cls)) continue;
            if (e.t >= since && (e.type == RESUMED || e.type == PAUSED)) return true;
            if (e.type == RESUMED) open.add(e.cls);
            else if (e.type == PAUSED || e.type == STOPPED_EV) open.remove(e.cls);
        }
        return !open.isEmpty();
    }

    // ===== Разбор dumpsys (право DUMP) =====

    // Владелец поднятого VPN из dumpsys connectivity: строка сети с «VPN CONNECTED» и «OwnerUid: N». -1: VPN нет или не видно
    private static final Pattern OWNER = Pattern.compile("OwnerUid: (\\d+)");

    static int vpnOwner(String dump) {
        if (dump == null) return -1;
        for (String line : dump.split("\n")) {
            if (!line.contains("NetworkAgentInfo") || !line.contains("VPN CONNECTED")) continue;
            Matcher m = OWNER.matcher(line);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }
        return -1;
    }
}
