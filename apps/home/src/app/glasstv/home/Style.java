package app.glasstv.home;

import android.content.Context;

// Стиль главного экрана (ноябрь). Переключается таблеткой «Стиль» в полосе шестерёнки (PillsActivity), хранится здесь.
// «Графит» (с октября): часы слева сверху, погода справа, фото под рядом размыто и затемнено, стеклянные плитки 16:9
// по левому краю, капсулы под рядом, стих на блокировке слева внизу, частицы.
// «Vision»: всё по центру, как экран блокировки iPhone: дата над крупными часами, под ними погода и курс; фото остаётся
// чётким, затемнение сверху и снизу запечено в картинку (Ambient.bake); маленькие капсулы над рядом; ряд из стеклянных
// сфер (Tile.orb) и это карусель: сфера в фокусе всегда посередине экрана, растёт на пружине и дышит; частиц нет.
// 05.11 было два варианта «Vision» на выбор; второй (по картинке-образцу: холодный тон, тёплые часы, янтарный ореол,
// отсветы на «полу») отклонён: свечение не появлялось, а просто висело. Остался первый.
// «Дом» перестраивается, как только снова показался: стиль входит в подпись набора приложений (HomeActivity.loadApps)
final class Style {
    private Style() {}

    static final String PREFS = "style", KEY = "home";
    static final String GRAPHITE = "graphite", V1 = "vision";
    static final String[] ORDER = {GRAPHITE, V1};
    // Сферы в dp: диаметр шара, ширина ячейки (подпись шире шара), высота ячейки (шар и подпись), зазор
    static final int D = 116, W = 156, H = 158, GAP = 14;   // подпись может зайти в зазор на 8 dp с каждой стороны (Tile.orbExt)
    static final int CAPS_Y = 274;   // «Vision»: верх капсул (34 dp), сферы под ними с 336
    static final int CAPS_H = 34;
    static final float GROW = 1.17f;  // сфера в фокусе (05.11: было 1,12; с перелётом пик около 1,20)

    static String get(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, GRAPHITE);
        for (String o : ORDER) if (o.equals(s)) return s;
        return s.startsWith(V1) ? V1 : GRAPHITE;   // «vision2» от отклонённого второго варианта: первый
    }

    static boolean vision(Context c) { return !GRAPHITE.equals(get(c)); }

    // Подпись для короткой плашки после переключения
    static String title(String s) {
        return V1.equals(s) ? "Vision" : "Графит";
    }

    // Выбор на экране «Настройки «Дома»»
    static void set(Context c, String s) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s).apply();
    }

    static void toggle(Context c) {
        String cur = get(c);
        int i = 0;
        for (int k = 0; k < ORDER.length; k++) if (ORDER[k].equals(cur)) i = k;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, ORDER[(i + 1) % ORDER.length]).apply();
    }
}
