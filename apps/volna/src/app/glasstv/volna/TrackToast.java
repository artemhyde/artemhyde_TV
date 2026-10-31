package app.glasstv.volna;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// Плашка «Сейчас играет» справа вверху: обложка, название, откуда трек.
// Обычный режим: всплывает на 4 секунды при смене трека и при выходе из «Волны».
// Режим «всегда»: висит, пока играет музыка, и прячется на паузе и когда открыт экран «Волны»
final class TrackToast {
    private static View view;
    private static ImageView cover;
    private static TextView title, sub, src;
    private static boolean pinned;   // сейчас показана в режиме «всегда»
    private static final Handler h = new Handler(Looper.getMainLooper());

    static boolean always(Engine e) { return e.ui.getBoolean("toastAlways", false); }

    static void setAlways(Engine e, boolean on) {
        e.ui.edit().putBoolean("toastAlways", on).apply();
    }

    // Всплыть с текущим треком (в режиме «всегда» остаётся висеть)
    static void show(Context c, Engine e) {
        if (Engine.homeVisible) return;   // на главном экране трек показывает сам «Дом»
        try {
            if (view == null) build(c);
            fill(e);
            view.animate().cancel();
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(250).start();
            h.removeCallbacks(hide);
            pinned = always(e);
            if (!pinned) h.postDelayed(hide, 4000);
        } catch (Exception ex) {
            remove(c);   // нет права «поверх окон»
        }
    }

    // Привести плашку в соответствие с состоянием музыки (зовётся на каждое изменение)
    static void sync(Context c, Engine e) {
        boolean want = always(e) && e.driving && e.playing() && Engine.uiVisible == 0 && !Engine.homeVisible && e.curId != null;
        if (want) {
            if (!pinned || view == null || view.getVisibility() != View.VISIBLE) show(c, e);
            else fill(e);
        } else if (pinned) {
            pinned = false;
            hide.run();
        }
    }

    private static void fill(Engine e) {
        if (view == null) return;
        Ui.setCover(cover, e.curArt);
        src.setText(e.badge.isEmpty() ? "Сейчас играет" : "Сейчас играет · " + e.badge);
        title.setText(e.curTitle);
        sub.setText(e.curArtist);
    }

    private static void build(Context c) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        Ui u = new Ui(c);
        LinearLayout box = u.row();
        box.setPadding(u.px(10), u.px(10), u.px(22), u.px(10));
        box.setBackground(new Glass(u.px(20), c.getResources().getDisplayMetrics().density, 0xEB17171C));
        cover = u.cover(12);
        box.addView(cover, new LinearLayout.LayoutParams(u.px(54), u.px(54)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(u.px(14), 0, 0, 0);
        Fonts.init(c);
        src = Fonts.text(c, Fonts.CAPTION, Fonts.med18, Ui.MINT);
        title = Fonts.text(c, Fonts.BODY + 1, Fonts.med18, 0xFFFFFFFF);
        sub = Fonts.text(c, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_72);
        for (TextView t : new TextView[]{src, title, sub}) { t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        title.setPadding(0, u.px(4), 0, u.px(3));
        title.setMaxWidth(u.px(420));
        sub.setMaxWidth(u.px(420));
        src.setMaxWidth(u.px(420));
        col.addView(src);
        col.addView(title);
        col.addView(sub);
        // Подсказка, как открыть управление: кнопка Premier на пульте
        TextView hint = Fonts.text(c, 12, Fonts.reg18, Fonts.WHITE_60);
        hint.setText("Кнопка Premier: управление музыкой");
        hint.setPadding(0, u.px(6), 0, 0);
        col.addView(hint);
        box.addView(col);
        box.setAlpha(0f);
        // Без NOT_TOUCHABLE: иначе система делает такое окно полупрозрачным
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = u.px(36);
        lp.y = u.px(30);
        wm.addView(box, lp);
        view = box;
    }

    // Обложка приходит чуть позже названия: обновляем, пока плашка на экране
    static void refreshArt(Engine e) {
        if (view != null && view.getVisibility() == View.VISIBLE) Ui.setCover(cover, e.curArt);
    }

    private static final Runnable hide = () -> {
        if (view != null) view.animate().alpha(0f).setDuration(600)
                .withEndAction(() -> { if (view != null && !pinned) view.setVisibility(View.GONE); }).start();
    };

    // Спрятать сразу, в каком бы режиме плашка ни висела
    static void hideNow() {
        pinned = false;
        h.removeCallbacks(hide);
        hide.run();
    }

    static void remove(Context c) {
        try { if (view != null) c.getSystemService(WindowManager.class).removeView(view); } catch (Exception ignored) { }
        view = null;
        pinned = false;
    }
}
