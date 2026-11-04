package app.glasstv.home;

import android.content.Context;

// Форма ряда на главном экране (ноябрь). «Плитки»: стеклянные прямоугольники 16:9, как было с октября.
// «Сферы»: круглые стеклянные шары со значком и подписью под ними, в духе visionOS: в ряду видно больше приложений,
// а фокус читается издалека по росту шара и свечению. Стекло у обеих форм одно (Tile.glassShape), меняется только контур.
// Переключается таблеткой «Форма» в полосе шестерёнки (PillsActivity), хранится здесь же. «Дом» перестраивает ряд,
// как только снова показался: форма входит в подпись набора приложений (HomeActivity.loadApps)
final class Shape {
    private Shape() {}

    static final String PREFS = "shape", KEY = "row";
    // Размеры сфер в dp: диаметр шара, ширина ячейки (подпись шире шара), высота ячейки (шар и подпись), зазор
    static final int D = 100, W = 140, H = 138, GAP = 18;   // подпись может зайти в зазор на 8 dp с каждой стороны (Tile.orbExt)

    static boolean round(Context c) {
        return "orbs".equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "tiles"));
    }

    static void toggle(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, round(c) ? "tiles" : "orbs").apply();
    }
}
