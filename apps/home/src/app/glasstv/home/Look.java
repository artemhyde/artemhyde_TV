package app.glasstv.home;

import android.content.Context;

// Оформление «Дома» (10.11, раздел «Оформление»): обои, часы и материал ряда. Форма ряда (кружки «Vision» или квадраты
// «Графит») живёт в Style, как и раньше. Выбор человека лежит в настройках «Дома» (Setup, ключи look.*) и поэтому сам
// едет в файле переноса. Пока в «Оформлении» стрелками перебирают варианты, экран показывает примерку: она живёт только
// в памяти (поля try*) и на диск не пишется, пока не нажали OK. Вышли без OK: примерка снимается, всё как было
final class Look {
    private Look() {}

    // Обои: фото дня (утро, день, вечер по подборке), четыре своих градиента и своё фото с телефона
    static final String PHOTO = "photo", DUSK = "dusk", DEEP = "deep", GRAPHITE = "graphite", SAND = "sand", OWN = "own";
    static final String[] WALLS = {PHOTO, DUSK, DEEP, GRAPHITE, SAND, OWN};
    // Часы: тонкие (Inter Light, как было), плотные (Inter SemiBold), стекло (GlassClock)
    static final String THIN = "thin", BOLD = "bold", GLASS = "glass";
    static final String[] CLOCKS = {THIN, BOLD, GLASS};
    // Материал ряда: «Цвет» (значки в цвете на дымчатом стекле, как было) или Liquid Glass (прозрачное стекло, белые знаки)
    static final String COLOR = "color", LIQUID = "liquid";
    static final String[] MATERIALS = {COLOR, LIQUID};

    // Примерка: null значит «как выбрано»
    static String tryWall, tryClock, tryMaterial, tryStyle;

    static void clearTries() { tryWall = tryClock = tryMaterial = tryStyle = null; }

    static boolean trying() { return tryWall != null || tryClock != null || tryMaterial != null || tryStyle != null; }

    private static String pick(String v, String[] all, String def) {
        for (String a : all) if (a.equals(v)) return v;
        return def;
    }

    // ===== Обои =====

    static String savedWall(Context c) {
        String w = pick(Setup.sp(c).getString("look.wall", PHOTO), WALLS, PHOTO);
        return OWN.equals(w) && !Walls.hasOwn(c) ? PHOTO : w;   // своё фото пропало (переустановка): фото дня
    }

    static String wall(Context c) {
        if (tryWall != null && !(OWN.equals(tryWall) && !Walls.hasOwn(c))) return tryWall;
        return savedWall(c);
    }

    static void saveWall(Context c, String w) { Setup.sp(c).edit().putString("look.wall", w).apply(); }

    // ===== Часы =====

    static String savedClock(Context c) { return pick(Setup.sp(c).getString("look.clock", THIN), CLOCKS, THIN); }

    static String clock(Context c) { return tryClock != null ? tryClock : savedClock(c); }

    static void saveClock(Context c, String v) { Setup.sp(c).edit().putString("look.clock", v).apply(); }

    // ===== Материал ряда =====

    static String savedMaterial(Context c) { return pick(Setup.sp(c).getString("look.material", COLOR), MATERIALS, COLOR); }

    static String material(Context c) { return tryMaterial != null ? tryMaterial : savedMaterial(c); }

    static boolean liquid(Context c) { return LIQUID.equals(material(c)); }

    static void saveMaterial(Context c, String v) { Setup.sp(c).edit().putString("look.material", v).apply(); }

    // ===== Форма ряда (Style) с примеркой =====

    static String style(Context c) { return tryStyle != null ? tryStyle : Style.get(c); }

    // Подписи вариантов для экрана и журнала
    static String wallTitle(String w) {
        switch (w) {
            case DUSK: return "Сумерки";
            case DEEP: return "Глубина";
            case GRAPHITE: return "Графит";
            case SAND: return "Песок";
            case OWN: return "Своё фото";
            default: return "Фото дня";
        }
    }

    static String clockTitle(String v) {
        return BOLD.equals(v) ? "Плотные" : GLASS.equals(v) ? "Стекло" : "Тонкие";
    }

    static String materialTitle(String v) { return LIQUID.equals(v) ? "Liquid Glass" : "Цвет"; }
}
