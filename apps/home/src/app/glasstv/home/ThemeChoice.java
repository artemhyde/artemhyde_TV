package app.glasstv.home;

import android.content.Context;

// Цвет стекла выбирается на ТВ (06.11, план Codex: ничего личного не вшивать в сборку, один APK на всех):
// «Графит» (тёмное дымчатое стекло) или «Объём» (светлое матовое). Theme.java считает все числа один раз при загрузке
// класса, поэтому выбор читается раньше неё, при старте процесса (HomeApp), а после смены «Дом» перезапускается (Restart).
// Хранится в настройках «Дома» (ключ theme) и едет в файле переноса. Config.THEME из сборки только начальное значение
final class ThemeChoice {
    private ThemeChoice() { }

    static final String GRAPHITE = "graphite", VOLUME = "volume";

    // Тема, с которой запущен процесс: её и рисует Theme
    static volatile String current = VOLUME.equals(Config.THEME) ? VOLUME : GRAPHITE;

    static void load(Context c) { current = get(c); }

    // Что выбрано сейчас в настройках (может отличаться от current до перезапуска)
    static String get(Context c) {
        return VOLUME.equals(Setup.sp(c).getString("theme", Config.THEME)) ? VOLUME : GRAPHITE;
    }

    static boolean volume() { return VOLUME.equals(current); }

    static boolean needsRestart(Context c) { return !current.equals(get(c)); }

    static String title(String t) { return VOLUME.equals(t) ? "светлое" : "тёмное"; }
}
