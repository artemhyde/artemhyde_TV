package app.glasstv.picture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

// Общий стиль полос «Звук» и «Картинка» (27.10). Файл один и тот же в обоих приложениях, отличается только строка package.
// Вид: полупрозрачный дымчатый графит с тонкой светлой кромкой (как плитки «Дома»), без теней (видеоядро ТВ слабое),
// плоские капсулы, шрифт Inter (те же файлы, что у «Дома»), три уровня белого. Цвет акцента у каждого приложения свой.
// Состояния капсулы и строки: «выделено пультом» (setActivated) и «выбрано» (setSelected).
final class Ui {
    private Ui() { }

    // Три уровня белого и тёмный текст на белой капсуле в фокусе
    static final int WHITE = 0xFFFFFFFF, W92 = 0xEBFFFFFF, W72 = 0xB8FFFFFF, W60 = 0x99FFFFFF, DARK = 0xFF16161C;
    static final int WARN = 0xFFFF9B8A;     // то, что мешает работе: мягкий красно-коралловый, не спорит с акцентами

    static Typeface reg = Typeface.DEFAULT, med = Typeface.DEFAULT;

    static void init(Context c) {
        reg = load(c, "Inter_18pt-Regular");
        med = load(c, "Inter_18pt-Medium");
    }

    private static Typeface load(Context c, String name) {
        try { return Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf"); }
        catch (Exception e) { return Typeface.DEFAULT; }
    }

    static TextView text(Context c, String s, float sp, boolean medium, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(medium ? med : reg);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setFontFeatureSettings("tnum");   // цифры одной ширины: число на ползунке не «дышит»
        return t;
    }

    // Подложка полосы: графит сверху вниз, непрозрачная, тонкая кромка
    static GradientDrawable glass(float dp) {
        // Плотная: при 80 % сквозь полосу читались подписи плиток главного экрана, при 95 % ещё проступал стих, при 98 % еле видны были подписи (снимки на ТВ 27.10)
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF302E38, 0xFF1E1C24});
        g.setCornerRadius(22 * dp);
        g.setStroke(Math.max(1, Math.round(dp)), 0x2EFFFFFF);
        return g;
    }

    // Режим: плоская капсула. Обычный светлеет на 8 %, выбранный окрашен акцентом, выделенный пультом белый (текст тёмный)
    static StateListDrawable mode(int accent) {
        int act = android.R.attr.state_activated, sel = android.R.attr.state_selected;
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{act}, pill(0xFFFFFFFF));
        d.addState(new int[]{sel}, pill((accent & 0x00FFFFFF) | 0x38000000));
        d.addState(new int[]{}, pill(0x14FFFFFF));
        return d;
    }

    // Вкладка «Звук» или «Картинка»: без заливки, текущая чуть светлее, выделенная пультом белая
    static StateListDrawable tab() {
        int act = android.R.attr.state_activated, sel = android.R.attr.state_selected;
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{act}, pill(0xFFFFFFFF));
        d.addState(new int[]{sel}, pill(0x26FFFFFF));
        d.addState(new int[]{}, pill(0));
        return d;
    }

    // Строка ползунка: без рамки, в фокусе светлая подложка
    static StateListDrawable row(float dp) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x16FFFFFF);
        on.setCornerRadius(12 * dp);
        d.addState(new int[]{android.R.attr.state_activated}, on);
        d.addState(new int[]{}, new GradientDrawable());
        return d;
    }

    private static GradientDrawable pill(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(200);   // больше любой высоты: полная капсула
        return g;
    }

    // Открыть соседнее приложение на том же месте: оно встаёт без выезда, а это закрывает вызывающий. false: соседа нет на ТВ
    static boolean open(Activity a, String pkg) {
        try {
            a.startActivity(new Intent().setClassName(pkg, pkg + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("swap", true));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
