package app.glasstv.guard;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

// Один раз после обновления до 1.2.0 (Ping.askNow): отправлять ли автору технические отчёты. Фокус на «Да», «Назад» без
// ответа: спросим завтра. Изменить потом можно на экране «Сторож». Автообновление от ответа не зависит
public class Ask extends Activity {
    private float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Typeface regular = Typeface.createFromAsset(getAssets(), "fonts/Inter_18pt-Regular.ttf");
        Typeface semibold = Typeface.createFromAsset(getAssets(), "fonts/Inter_18pt-SemiBold.ttf");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xCC000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(48), px(40), px(48), px(36));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(28));
        bg.setColor(0xFF1C1C1F);
        card.setBackground(bg);

        TextView title = text("Отправлять автору технические отчёты?", 26, 0xFFF5F5F7, semibold);
        card.addView(title);
        TextView body = text("Раз в сутки: модель телевизора, версия Android и HYDE, ошибки обновлений. Без личных данных: "
                + "ни аккаунтов, ни сетей, ни того, что вы смотрите. Изменить можно на экране «Сторож». Обновления "
                + "HYDE от ответа не зависят.", 17, 0xB3F5F5F7, regular);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, px(14), 0, px(28));
        card.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        View yes = button(buttons, "Да", semibold, v -> answer(true));
        button(buttons, "Нет", semibold, v -> answer(false));
        card.addView(buttons);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(720), -2, Gravity.CENTER);
        root.addView(card, lp);
        setContentView(root);
        yes.requestFocus();
    }

    private void answer(boolean on) {
        GuardService g = GuardService.running;
        if (g != null && g.upd != null) {
            g.upd.ping.set(on, null, "вопрос на экране ТВ");
            if (on) g.updSoon(Hyde.CHAIN_MS);
        } else {
            getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean(Ping.P_ON, on).putBoolean(Ping.P_ASKED, true).apply();
            GuardService.start(this);
        }
        finish();
    }

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View button(LinearLayout parent, String s, Typeface tf, View.OnClickListener l) {
        TextView t = text(s, 18, 0xFFF5F5F7, tf);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(36), px(14), px(36), px(14));
        t.setMinWidth(px(140));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(14));
        on.setColor(0xFFF5F5F7);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(px(14));
        off.setColor(0x1FFFFFFF);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, off);
        t.setBackground(sl);
        t.setOnFocusChangeListener((v, f) -> ((TextView) v).setTextColor(f ? 0xFF0B0B0E : 0xFFF5F5F7));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (parent.getChildCount() > 0) lp.leftMargin = px(14);
        parent.addView(t, lp);
        return t;
    }
}
