package app.glasstv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// Маленькая стеклянная капсула под рядом плиток: «Продолжить  YouTube», «Играет  трек · исполнитель».
// Не спорит с плитками: тонкая, графитовая, в фокусе светлее и с яркой кромкой-градиентом, как у плиток «Графита».
// В теме «Объём» (Theme) капсула светлая, а в фокусе белая рамка 2 dp
final class Suggest extends LinearLayout {
    private final float dp;
    private float radius;
    private boolean compact;
    private int maxDp = 360;
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ImageView icon;
    private final TextView text;
    private final Glass glass;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float focus;

    Suggest(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        radius = 22 * dp;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(px(10), 0, px(18), 0);
        setFocusable(true);
        setClickable(true);
        setWillNotDraw(false);
        glass = new Glass(radius, dp);
        setBackground(glass);
        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(icon, new LayoutParams(px(30), px(30)));
        text = Fonts.text(c, Fonts.BODY, Fonts.med18, Fonts.WHITE_92);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(px(360));
        text.setPadding(px(10), 0, 0, 0);
        addView(text);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Theme.RING_W * dp);   // «Графит»: 2,2 точки, как кромка плитки в фокусе; «Объём»: белая рамка 4 точки
        if (Theme.RING == null) ring.setColor(0xFFFFFFFF);
        setOnFocusChangeListener((v, f) -> focus(f));
    }

    private int px(float v) { return Math.round(v * dp); }

    // Пояснение приглушённое, название ярче: одна строка вместо двух
    void set(String label, String title, Drawable image) {
        android.text.SpannableString s = new android.text.SpannableString(label + "  " + title);
        s.setSpan(new android.text.style.ForegroundColorSpan(Fonts.WHITE_60), 0, label.length(), 0);
        text.setText(s);
        icon.setImageDrawable(image);
    }

    // То же с хвостом («осталось 42 мин»): хвост приглушённый и всегда на виду, а длинное название ужимается многоточием
    void set(String label, String title, String tail, Drawable image) {
        if (tail == null || tail.isEmpty()) { set(label, title, image); return; }
        android.text.TextPaint tp = text.getPaint();
        String head = label + "  ", end = " · " + tail;
        float room = text.getMaxWidth() - text.getPaddingLeft() - text.getPaddingRight() - tp.measureText(head) - tp.measureText(end);
        CharSequence t = TextUtils.ellipsize(title, tp, Math.max(room, 40 * dp), TextUtils.TruncateAt.END);
        android.text.SpannableString s = new android.text.SpannableString(head + t + end);
        s.setSpan(new android.text.style.ForegroundColorSpan(Fonts.WHITE_60), 0, label.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(Fonts.WHITE_60), head.length() + t.length(), s.length(), 0);
        text.setText(s);
        icon.setImageDrawable(image);
    }

    // Шире обычных 360 dp: у «Продолжить» в подписи ещё и «осталось N мин»
    void wide(int maxDp) { this.maxDp = maxDp; text.setMaxWidth(px(compact ? Math.round(maxDp * 0.86f) : maxDp)); }

    // «Vision» (05.11): капсула ниже и тише, 34 dp вместо 44, значок 22 dp, текст 14: не тянет на себя внимание
    void compact(boolean on) {
        compact = on;
        radius = (on ? Style.CAPS_H / 2f : 22) * dp;
        glass.radius(radius);
        LayoutParams il = (LayoutParams) icon.getLayoutParams();
        il.width = il.height = px(on ? 22 : 30);
        icon.setLayoutParams(il);
        text.setTextSize(on ? 14 : Fonts.BODY);
        text.setPadding(px(on ? 8 : 10), 0, 0, 0);
        wide(maxDp);
        pads();
    }

    private void pads() { setPadding(px(compact ? 7 : 10), 0, px(compact ? 14 : 18), 0); }



    @Override public boolean hasOverlappingRendering() { return false; }

    // Кромка фокуса: ярче сверху слева, к середине почти гаснет, снизу справа снова светлее (числа кромки плитки)
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0 && Theme.RING != null) ring.setShader(Tile.css(150, w, h, Theme.RING, Theme.RING_POS));
    }

    private float shown = 1f;
    private android.animation.ValueAnimator fadeAnim;

    // Пока фокус на ряду плиток, капсулы приглушены (около 45 %) и не спорят с ним; в фокусе проявляются полностью.
    // Прозрачность ставится стеклу, значку и тексту, а не всей капсуле: её прозрачностью управляет каскад разблокировки
    void fade(float to) {
        if (fadeAnim != null) fadeAnim.cancel();
        fadeAnim = android.animation.ValueAnimator.ofFloat(shown, to);
        fadeAnim.setDuration(M.t(to > shown ? 260 : 380));
        fadeAnim.setInterpolator(Tile.EASE);
        fadeAnim.addUpdateListener(an -> {
            shown = (float) an.getAnimatedValue();
            glass.setAlpha(Math.round(255 * shown));
            icon.setAlpha(shown);
            text.setAlpha(shown);
        });
        fadeAnim.start();
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (focus > 0.01f) {
            float h = ring.getStrokeWidth() / 2f;
            r.set(h, h, getWidth() - h, getHeight() - h);
            ring.setAlpha(Math.round(Theme.RING_ALPHA * focus));
            c.drawRoundRect(r, radius - h, radius - h, ring);
        }
    }

    private void focus(boolean f) {
        animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setStartDelay(0)
                .setDuration(M.t(f ? 260 : 200)).setInterpolator(Tile.EASE).start();
        glass.setLit(f);
        text.setTextColor(f ? Fonts.WHITE : Fonts.WHITE_92);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(focus, f ? 1f : 0f);
        a.setDuration(M.t(200));
        a.addUpdateListener(an -> { focus = (float) an.getAnimatedValue(); invalidate(); });
        a.start();
    }
}
