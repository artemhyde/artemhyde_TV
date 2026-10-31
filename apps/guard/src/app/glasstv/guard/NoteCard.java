package app.glasstv.guard;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

// Записка поверх любого экрана: слева сверху, мягко выезжает, через 12 секунд тает
final class NoteCard {
    private final Context c;
    private final float dp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout view;
    private TextView text;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    NoteCard(Context c) {
        this.c = c;
        dp = c.getResources().getDisplayMetrics().density;
    }

    private int px(float v) { return Math.round(v * dp); }

    void show(String s) {
        try {
            if (view == null) build();
            text.setText(s);
            h.removeCallbacks(hide);
            view.animate().cancel();
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setTranslationY(-px(16));
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).translationY(0).setDuration(500).setInterpolator(EASE).start();
            h.postDelayed(hide, 12_000);
        } catch (Exception e) {
            view = null;   // нет права «поверх окон»
        }
    }

    private final Runnable hide = () -> {
        if (view != null) view.animate().alpha(0f).translationY(-px(10)).setDuration(600).setInterpolator(EASE)
                .withEndAction(() -> { if (view != null) view.setVisibility(View.GONE); }).start();
    };

    private void build() {
        Typeface regular = Typeface.createFromAsset(c.getAssets(), "fonts/Inter_18pt-Regular.ttf");
        Typeface medium = Typeface.createFromAsset(c.getAssets(), "fonts/Inter_18pt-Medium.ttf");
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(px(26), px(18), px(26), px(20));
        view.setBackground(new Glass(px(22), dp, 0xF017171C));
        view.setElevation(px(12));
        TextView label = new TextView(c);
        label.setText("ЗАПИСКА");
        label.setTextSize(12);
        label.setLetterSpacing(0.12f);
        label.setTextColor(0xFF5ED3B4);
        label.setTypeface(medium);
        label.setIncludeFontPadding(false);
        view.addView(label);
        text = new TextView(c);
        text.setTextSize(24);
        text.setTextColor(0xFFF5F5F7);
        text.setTypeface(regular);
        text.setLineSpacing(0, 1.2f);
        text.setMaxWidth(px(560));
        text.setIncludeFontPadding(false);
        text.setPadding(0, px(10), 0, 0);
        view.addView(text);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Слева сверху: справа живёт плашка «Волны», по центру снизу субтитры фильмов
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = px(40);
        lp.y = px(32);
        c.getSystemService(WindowManager.class).addView(view, lp);
    }
}
