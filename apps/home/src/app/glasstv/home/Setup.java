package app.glasstv.home;

import android.content.Context;
import android.content.SharedPreferences;

// Настройки «Дома» на самом ТВ (06.11): город для погоды, курсы, стих, какое приложение открывает плитка-ярлык.
// Меняются с пульта на экране «Настройки «Дома»» (SetupActivity) и переживают обновления «Дома». Значения из сборки
// (Config) только начальные: один и тот же файл приложения подходит любому человеку, и город не требует пересборки
final class Setup {
    static final String PREFS = "setup";

    private Setup() {}

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ===== Город и погода =====

    static boolean weatherOn(Context c) { return sp(c).getBoolean("hasPlace", Config.HAS_LOCATION); }

    static String city(Context c) { return sp(c).getString("city", Config.CITY); }

    static double lat(Context c) { return Double.longBitsToDouble(sp(c).getLong("lat", Double.doubleToLongBits(Config.LAT))); }

    static double lon(Context c) { return Double.longBitsToDouble(sp(c).getLong("lon", Double.doubleToLongBits(Config.LON))); }

    static void setPlace(Context c, String city, double lat, double lon) {
        sp(c).edit().putBoolean("hasPlace", true).putString("city", city)
                .putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).apply();
        bump(c);
    }

    static void noPlace(Context c) {
        sp(c).edit().putBoolean("hasPlace", false).apply();
        bump(c);
    }

    // ===== Курсы и стих =====

    // none: строки курсов нет; nbrb: Нацбанк Беларуси (доллар, евро, сто российских рублей к белорусскому)
    static String rates(Context c) { return sp(c).getString("rates", Config.RATES); }

    static boolean ratesOn(Context c) { return "nbrb".equals(rates(c)); }

    // bible: подборка Синодального перевода из приложения; off: без стиха
    static String verses(Context c) { return sp(c).getString("verses", Config.VERSES); }

    static boolean versesOn(Context c) { return !"off".equals(verses(c)); }

    // Облегчённый режим для слабых ТВ (план Codex, блок 6): без частиц и наплыва фото, фото вдвое меньше по сторонам. on или off
    static boolean lite(Context c) { return "on".equals(sp(c).getString("lite", "off")); }

    // Ночь «Сторожа» (план Codex, блок 3): «22-7» (с 22:00 до 7:00) и подобные или off. Ведёт подсветку «Сторож»; здесь копия
    // выбора для строки настроек, сам выбор уходит ему командой nighthours через вход AppCmd
    static String night(Context c) { return sp(c).getString("night", "22-7"); }

    static String nightTitle(String v) {
        if ("off".equals(v)) return "не затемнять";
        String[] a = v.split("-");
        return a.length == 2 ? "с " + a[0] + ":00 до " + a[1] + ":00" : v;
    }

    static void sendNight(Context c, String v) {
        android.content.Intent i = new android.content.Intent().setClassName("app.glasstv.guard", "app.glasstv.guard.AppCmd").putExtra("do", "nighthours");
        if ("off".equals(v)) i.putExtra("enabled", false);
        else {
            String[] a = v.split("-");
            try { i.putExtra("from", Integer.parseInt(a[0])).putExtra("to", Integer.parseInt(a[1])).putExtra("enabled", true); }
            catch (Exception e) { return; }
        }
        c.sendBroadcast(i);
    }

    static void put(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
        bump(c);
    }

    // ===== Плитки-ярлыки =====

    // Приложение, которое человек выбрал для ярлыка вместо заданного в нём (например, свой файловый менеджер); null: как в ярлыке
    static String tileApp(Context c, String tilePkg) { return sp(c).getString("tile." + tilePkg, null); }

    static void setTileApp(Context c, String tilePkg, String app) {
        SharedPreferences.Editor e = sp(c).edit();
        if (app == null) e.remove("tile." + tilePkg); else e.putString("tile." + tilePkg, app);
        e.apply();
        bump(c);
    }

    // Номер правки: «Дом» по нему видит, что настройки меняли, и перерисовывает экран
    static int rev(Context c) { return sp(c).getInt("rev", 0); }

    static void bump(Context c) { sp(c).edit().putInt("rev", rev(c) + 1).apply(); }
}
