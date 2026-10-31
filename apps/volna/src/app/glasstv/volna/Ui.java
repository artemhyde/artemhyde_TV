package app.glasstv.volna;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// Общие кирпичики интерфейса в стиле «Ночного режима»: тёмные плашки, кнопки-чипы, обложки
final class Ui {
    static final int MINT = 0xFF5ED3B4;
    static final int PANEL = 0xF2141418;

    final Context c;
    final float dp;

    Ui(Context c) {
        this.c = c;
        dp = c.getResources().getDisplayMetrics().density;
    }

    int px(float v) { return Math.round(v * dp); }

    TextView text(String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    TextView line(String s, float sp, int color, boolean bold) {
        TextView t = text(s, sp, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        if (stroke != 0) g.setStroke(px(2), stroke);
        return g;
    }

    // Кнопка-чип: в фокусе белая, выбранная подсвечена акцентом
    TextView chip(String s, float sp, int accent, View.OnClickListener l) {
        TextView t = text(s, sp, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(px(56));
        t.setPadding(px(14), 0, px(14), 0);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{0xFF141418, accent, Color.WHITE}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(Color.WHITE, 12, 0));
        bg.addState(new int[]{android.R.attr.state_selected}, round((accent & 0x00FFFFFF) | 0x24000000, 12, accent));
        bg.addState(new int[]{}, round(0x1AFFFFFF, 12, 0));
        t.setBackground(bg);
        t.setOnClickListener(l);
        t.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start());
        return t;
    }

    TextView add(LinearLayout row, TextView chip, float heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(heightDp));
        if (row.getChildCount() > 0) lp.leftMargin = px(8);
        row.addView(chip, lp);
        return chip;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    // Обложка со скруглёнными углами
    ImageView cover(float radiusDp) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(round(0xFF22222A, radiusDp, 0));
        iv.setClipToOutline(true);
        iv.setImageResource(R.drawable.wave);
        return iv;
    }

    static void setCover(ImageView iv, Bitmap bm) {
        if (bm != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bm);
        } else {
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setImageResource(R.drawable.wave);
        }
    }

    // Цвет обложки: среднее по уменьшенной картинке, чуть ярче и насыщеннее, для акцента и фона
    static int accent(Bitmap bm) {
        if (bm == null) return MINT;
        Bitmap s = Bitmap.createScaledBitmap(bm, 12, 12, true);
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int x = 0; x < 12; x++)
            for (int y = 0; y < 12; y++) {
                int p = s.getPixel(x, y);
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
            }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.4f + 0.1f);
        hsv[2] = Math.max(0.75f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    // Светлая версия цвета обложки для текста: смесь с белым, чтобы читалось на тёмном фоне
    static int light(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        r += (255 - r) * 45 / 100;
        g += (255 - g) * 45 / 100;
        b += (255 - b) * 45 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
