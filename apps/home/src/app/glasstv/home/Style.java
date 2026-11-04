package app.glasstv.home;

import android.content.Context;

// Стиль главного экрана (ноябрь). Переключается таблеткой «Стиль» в полосе шестерёнки (PillsActivity), хранится здесь.
// «Графит» (с октября): часы слева сверху, погода справа, фото под рядом размыто и затемнено, стеклянные плитки 16:9
// по левому краю, капсулы под рядом, стих на блокировке слева внизу, частицы.
// «Vision»: всё по центру, как экран блокировки iPhone: дата над крупными часами, под ними погода и курс; фото остаётся
// чётким, затемнение сверху и снизу запечено в картинку (Ambient.bake); капсулы над рядом; ряд из стеклянных сфер
// (Tile.orb) и это карусель: сфера в фокусе всегда посередине экрана; стих на блокировке по центру, частиц нет.
// «Дом» перестраивается, как только снова показался: стиль входит в подпись набора приложений (HomeActivity.loadApps)
final class Style {
    private Style() {}

    static final String PREFS = "style", KEY = "home";
    // Сферы в dp: диаметр шара, ширина ячейки (подпись шире шара), высота ячейки (шар и подпись), зазор
    static final int D = 100, W = 140, H = 138, GAP = 18;   // подпись может зайти в зазор на 8 dp с каждой стороны (Tile.orbExt)
    static final int CAPS_Y = 300;   // «Vision»: верх капсул, сферы под ними с 372

    static boolean vision(Context c) {
        return "vision".equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "graphite"));
    }

    static void toggle(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, vision(c) ? "graphite" : "vision").apply();
    }
}
