package app.glasstv.guard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Паспорт ТВ (план Codex, блок 5): кто это (производитель, серия, прошивка) и какие закрытые функции TCL и Dolby на нём отвечают.
// Правила без Android в CapsRules. Входы в закрытые функции (Backlight.mgr, Pq.invokeStatic, DolbyProbe.open) спрашивают tcl(),
// служба Dolby ещё и сверяет версию и хэш интерфейса. Отчёт: am broadcast -n app.glasstv.guard/.Cmd --es do caps
final class Caps {
    private Caps() { }

    private static volatile String series;

    static boolean tcl() { return CapsRules.isTcl(Build.MANUFACTURER, Build.BRAND); }

    // Серия модели TCL (у этого ТВ C6K): в Build.MODEL только «Smart TV Pro»
    static String series() {
        String s = series;
        if (s == null) series = s = prop("ro.boot.product");
        return s;
    }

    static String level() { return CapsRules.level(Build.MANUFACTURER, Build.BRAND, series(), Build.FINGERPRINT); }

    static String device() {
        return Build.MANUFACTURER + " " + series() + " (" + Build.MODEL + ", " + Build.DEVICE + "), Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                + ", прошивка " + Build.VERSION.INCREMENTAL;
    }

    // Прошивка сменилась с прошлого запуска «Сторожа»: строка для журнала и записки, иначе null. Новый отпечаток запоминается
    static String firmwareNews(SharedPreferences p) {
        String before = p.getString("fw", ""), now = Build.FINGERPRINT;
        if (!now.equals(before)) p.edit().putString("fw", now).apply();
        return CapsRules.firmwareChanged(before, now) ? "Прошивка ТВ сменилась: " + before + " → " + now : null;
    }

    // Системная панель TCL (таблетка «Панель TCL» в «Доме»)
    static final ComponentName TCL_PANEL = new ComponentName("com.android.tv.settings", "com.tcl.settings.ShowWindowService");

    // Полный отчёт с пробами (только чтение). Не из главного потока: служба Dolby отвечает до 6 с
    static String report(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("ТВ: ").append(device()).append("; уровень: ").append(level());
        if (!tcl()) return b.append("; функции TCL и Dolby не вызываются, работают обычные настройки Android").toString();
        int bl = Backlight.get(), ls = Backlight.lightSensor();
        b.append("; подсветка: ").append(bl >= 0 && bl <= 100 ? "отвечает, сейчас " + bl : "нет");
        b.append("; датчик света: ").append(ls == 1 ? "включён" : ls == 0 ? "выключен" : "нет");
        Integer dbc = Pq.picGet("dbc");
        b.append("; картинка: ").append(dbc != null ? "отвечает" : "нет");
        b.append("; Dolby: ").append(DolbyProbe.check(c));
        boolean panel = c.getPackageManager().resolveService(new Intent("com.tcl.settings.SHOW_WINDOW").setComponent(TCL_PANEL), 0) != null;
        b.append("; панель TCL: ").append(panel ? "есть" : "нет");
        return b.toString();
    }

    static String prop(String key) {
        try {
            Process pr = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            String s;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) { s = r.readLine(); }
            pr.waitFor();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
