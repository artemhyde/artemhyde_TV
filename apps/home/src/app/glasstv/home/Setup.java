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

    // cc: код страны из поиска города («BY»), по нему курсы «по стране города»; пусто, если поиск его не дал
    static void setPlace(Context c, String city, double lat, double lon, String cc) {
        sp(c).edit().putBoolean("hasPlace", true).putString("city", city).putString("cc", cc == null ? "" : cc)
                .putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).apply();
        bump(c);
    }

    // Страна города для курсов. У города, выбранного до 1.1.1, её нет: Data один раз узнаёт её поиском по названию
    static String country(Context c) { return sp(c).getString("cc", ""); }

    static void setCountry(Context c, String cc) { sp(c).edit().putString("cc", cc).apply(); }

    static void noPlace(Context c) {
        sp(c).edit().putBoolean("hasPlace", false).apply();
        bump(c);
    }

    // ===== Курсы и стих =====

    // none: строки курсов нет; auto: по стране города; nbrb, cbr, nbu, nbk, ecb: банк выбран вручную (Rates, с 1.1.1;
    // до того был только nbrb, Нацбанк Беларуси)
    static String rates(Context c) { return sp(c).getString("rates", Config.RATES); }

    static boolean ratesOn(Context c) { String r = rates(c); return r != null && !r.isEmpty() && !Rates.NONE.equals(r); }

    static String ratesSource(Context c) { return Rates.source(rates(c), country(c)); }

    // bible: подборка Синодального перевода из приложения; quotes (10.11): цитаты о жизни и поддержке; off: без стиха.
    // custom: свои стихи, их кладёт сборка вместо подборки Библии
    static String verses(Context c) { return sp(c).getString("verses", Config.VERSES); }

    static boolean versesOn(Context c) { return !"off".equals(verses(c)); }

    static boolean quotes(Context c) { return "quotes".equals(verses(c)); }

    // Подпись выбора словами
    static String versesTitle(Context c) {
        String v = verses(c);
        return "off".equals(v) ? "не показывать" : "quotes".equals(v) ? "цитаты" : "custom".equals(v) ? "свои стихи" : "Библия";
    }

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

    // ===== Музыка (07.11, план Codex: человек сам выбирает музыку, «альтернативный запуск музыки») =====

    // Что открывают капсула на главном экране и таблетка в полосе шестерёнки. Пусто: как было, «Волна», если она стоит.
    // «volna»: «Волна» (в файле переноса слово, а не пакет: файл один и для личной сборки, и для выпуска).
    // «off»: музыки на главном экране и в полосе нет. Иначе пакет выбранного приложения
    static final String VOLNA = "app.glasstv.volna";

    static String music(Context c) { return sp(c).getString("music", ""); }

    static void setMusic(Context c, String v) { put(c, "music", v == null ? "" : v); }

    static boolean launchable(Context c, String pkg) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
    }

    /** Пакет, который сейчас открывает музыку, или null: выбранное приложение, пока оно стоит, иначе «Волна», если стоит */
    static String musicApp(Context c) {
        String m = music(c);
        if ("off".equals(m)) return null;
        if (!m.isEmpty() && !"volna".equals(m) && launchable(c, m)) return m;
        return launchable(c, VOLNA) ? VOLNA : null;
    }

    // Номер правки: «Дом» по нему видит, что настройки меняли, и перерисовывает экран
    static int rev(Context c) { return sp(c).getInt("rev", 0); }

    static void bump(Context c) { sp(c).edit().putInt("rev", rev(c) + 1).apply(); }
}
