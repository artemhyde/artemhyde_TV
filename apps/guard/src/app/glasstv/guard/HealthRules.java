package app.glasstv.guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// Присмотр за тем, что на ТВ ломается без установщика (выпуск 1.2.0, задача 10.11): кнопки пульта (служба Key Mapper),
// главный экран HYDE и пакеты, которые отключил установщик. Правила без Android, проверка на ПК (test/HealthRulesTest.java).
// Работа с Android в Health.java
final class HealthRules {
    private HealthRules() { }

    static final String KM_PKG = "io.github.sds100.keymapper";
    static final String KM_SERVICE = KM_PKG + "/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService";

    // Поля ежедневного отчёта автору (Ping): keys и home
    static final String OK = "ok", REPAIRED = "repaired", BROKEN = "broken", ABSENT = "absent", LOST = "lost";

    // Откуда проверка кнопок (и слово для журнала)
    static final String START = "запуск", WAKE = "включение ТВ", PACKAGE = "установка пакета", PERIODIC = "проверка",
            MANUAL = "вручную", FIRMWARE = "обновление прошивки";

    static final long PACKAGE_GAP = 5 * 60_000;      // после неудачи перезапуск по установке пакета не чаще раза в 5 минут
    static final long NOTE_GAP = 24 * 3_600_000L;    // одна и та же записка не чаще раза в сутки
    static final long HOME_SETTLE = 3 * 60_000;      // главный экран не HYDE дольше 3 минут: не посреди работы установщика
    static final int MAX_LIST = 200;                 // столько отключённых пакетов принимает таблица автора (поле reenabled)

    // ===== Кнопки пульта =====

    // Перезапускать ли службу Key Mapper. broken: прошлый перезапуск не помог (прошивка не даёт поднять службу);
    // sinceTry: сколько мс прошло с прошлой попытки. После неудачи список спецвозможностей не трогаем каждые 15 минут:
    // следующая попытка при включении ТВ, после установки пакета (система тогда заново привязывает службы, так было 10.11)
    // или по просьбе человека и установщика
    static boolean shouldRepair(String why, boolean broken, long sinceTry) {
        if (!broken) return true;
        if (PERIODIC.equals(why)) return false;
        if (PACKAGE.equals(why)) return sinceTry < 0 || sinceTry >= PACKAGE_GAP;
        return true;
    }

    // Список служб спецвозможностей без служб пакета pkg
    static String without(String list, String pkg) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String s : list.split(":")) {
            if (s.isEmpty() || s.startsWith(pkg + "/")) continue;
            if (b.length() > 0) b.append(':');
            b.append(s);
        }
        return b.toString();
    }

    // Тот же список, где служба пакета стоит последней ровно один раз
    static String with(String list, String pkg, String service) {
        String others = without(list, pkg);
        return others.isEmpty() ? service : others + ":" + service;
    }

    // Три записи перезапуска службы, каждая {что должно быть в списке перед записью, что записать}: со службой, без неё,
    // снова со службой (порядок ServiceRepair из Clean Your TV, см. Health.java). Первая запись в список, где служба уже
    // последняя, ничего не меняет: тогда Android заметит вторую, и пометка «упала» снимется
    static String[][] steps(String was) {
        String with = with(was, KM_PKG, KM_SERVICE), without = without(was, KM_PKG);
        return new String[][]{{was, with}, {with, without}, {without, with}};
    }

    // Отсутствующая настройка (null) читается как пустая
    static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    // Поле keys: Key Mapper не стоит, работает, работает после удачного перезапуска с прошлого принятого отчёта, не работает
    static String keysField(boolean installed, boolean alive, long repairedAt, long lastReport) {
        if (!installed) return ABSENT;
        if (!alive) return BROKEN;
        return repairedAt > 0 && repairedAt > lastReport ? REPAIRED : OK;
    }

    // ===== Главный экран =====

    // Поле home: null, если «Дома» на ТВ нет (защищать нечего, поле не шлётся)
    static String homeField(boolean homeInstalled, boolean homeIsOurs) {
        if (!homeInstalled) return null;
        return homeIsOurs ? OK : LOST;
    }

    // Как назвать чужой главный экран в записке: null, если имя ничего не скажет человеку
    static String homeName(String pkg) {
        if ("com.google.android.apps.tv.launcherx".equals(pkg) || "com.google.android.tvlauncher".equals(pkg)) return "Google TV";
        if ("com.google.android.leanbacklauncher".equals(pkg)) return "Android TV";
        return null;
    }

    // Главный экран не HYDE достаточно долго, чтобы это не было серединой работы установщика
    static boolean homeSettled(long lostAt, long now) {
        return lostAt > 0 && now - lostAt >= HOME_SETTLE;
    }

    // ===== Отключённые установщиком пакеты =====

    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    // Список от установщика: имена пакетов через запятую (пробелы не мешают), повторы убираются. Пустой: списка нет.
    // Чужое имя или больше 200 штук: весь список не принимается, чтобы ошибка установщика была видна сразу
    static List<String> parseList(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) {
            for (String w : s.split("[,\\s]+")) {
                if (w.isEmpty()) continue;
                if (w.length() > 200 || !PKG.matcher(w).matches()) throw new IllegalArgumentException("не имя пакета: " + cut(w, 60));
                out.add(w);
            }
        }
        if (out.size() > MAX_LIST) throw new IllegalArgumentException("пакетов " + out.size() + ", больше " + MAX_LIST);
        return new ArrayList<>(out);
    }

    static String join(List<String> list) { return String.join(",", list); }

    // ===== Записка «нужен установщик» =====

    // Пора ли снова сказать то же самое (часы ТВ могли уйти назад: тогда тоже пора)
    static boolean due(long shownAt, long now) {
        return shownAt <= 0 || now < shownAt || now - shownAt >= NOTE_GAP;
    }

    // Почему не работают кнопки: прошивка не дала поднять службу или у «Сторожа» нет прав её перезапустить
    static final String WHY_FIRMWARE = "fw", WHY_RIGHTS = "rights";

    // Одна записка на все причины сразу. keysWhy: null, если с кнопками всё в порядке; tcl: телевизор TCL;
    // homeLost: главный экран не HYDE; homePkg: чей он сейчас; reenabled: сколько отключённых пакетов включила прошивка
    static String note(String keysWhy, boolean tcl, boolean homeLost, String homePkg, int reenabled) {
        List<String> parts = new ArrayList<>();
        if (WHY_RIGHTS.equals(keysWhy)) parts.add("Кнопки пульта отключились, а прав перезапустить Key Mapper у «Сторожа» нет");
        else if (keysWhy != null) parts.add(tcl ? "Кнопки пульта отключились: прошивка TCL не даёт запустить Key Mapper"
                : "Кнопки пульта отключились: Key Mapper не запускается");
        if (homeLost) {
            String name = homeName(homePkg);
            parts.add(name != null ? "Главный экран снова " + name : "Главный экран больше не HYDE");
        }
        if (reenabled > 0) parts.add("Обновление прошивки снова включило приложения, отключённые установщиком (" + reenabled + ")");
        if (parts.isEmpty()) return null;
        parts.add("Запустите установщик HYDE на компьютере");
        return String.join(". ", parts);
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
