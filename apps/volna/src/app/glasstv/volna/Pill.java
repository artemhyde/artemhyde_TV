package app.glasstv.volna;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

// Кнопка-таблетка для режимов и настроек: выбранная светлее и с тонкой линией, в фокусе белая с тёмным текстом
final class Pill extends TextView {
    private final GradientDrawable bg = new GradientDrawable();
    private final float dp;
    private boolean on;

    Pill(Context c, String text, OnClickListener l) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setText(text);
        setTextSize(Fonts.CAPTION + 1);
        setTypeface(Fonts.med18);
        setIncludeFontPadding(false);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setPadding(px(16), 0, px(16), 0);
        bg.setCornerRadius(px(18));
        setBackground(bg);
        setFocusable(true);
        setClickable(true);
        setOnClickListener(l);
        setOnFocusChangeListener((v, f) -> {
            paint();
            animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setStartDelay(0).setDuration(M.t(f ? 220 : 180))
                    .setInterpolator(Icon.EASE).start();
        });
        paint();
    }

    private int px(float v) { return Math.round(v * dp); }

    void setOn(boolean v) { if (on != v) { on = v; paint(); } }

    private int tint;

    // Выбранная таблетка своим цветом (второй вкус), чтобы сразу было видно, чей вкус играет
    void setTint(int c) { if (tint != c) { tint = c; paint(); } }

    private void paint() {
        boolean f = isFocused();
        // Выбранное: просто светлее, без рамки. Фокус: белая заливка. Так их не спутать
        bg.setColor(f ? 0xFFFFFFFF : on && tint != 0 ? (tint & 0x00FFFFFF) | 0x8C000000 : on ? 0x38FFFFFF : 0x14FFFFFF);
        bg.setStroke(0, 0x00000000);
        setTextColor(f ? 0xFF111114 : on ? Fonts.WHITE : Fonts.WHITE_72);
    }
}
