package app.glasstv.nightmode;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

// Сквозной ползунок «Яркость» для пульта: влево темнее, вправо светлее, шаг 1, при удержании кнопки быстрее.
// Удержание останавливается на нуле: дальше (в красную зону или обратно) только новым нажатием.
// Вверх и вниз уходят на соседние кнопки. Шкала одна на всю ширину, у отметки «0» разрыв: правее неё настоящая подсветка
// панели (белая заливка от нуля), левее красная зона затемнения (красная заливка от нуля влево, бегунок розовеет).
// Там, где подсветкой управлять нельзя, отметки «0» нет, шкала вся из затемнения (zero = -1) и заливается слева до бегунка
final class Bar extends View {
    interface Listener { void onPos(int pos); }

    static final int RED = 0xFFE5604F;        // мягкий тёмно-красный: заливка красной зоны
    static final int RED_KNOB = 0xFFFFD2C7;

    private final int steps, zero;
    private final int[] markAt;
    private final String[] markText;
    private final String zoneText;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int value;
    private float shown;          // где сейчас рисуем бегунок (плавно догоняет value)
    private ValueAnimator anim;
    private Listener listener;

    // steps: всего шагов; zero: шаг отметки «0» (-1: шкала без красной зоны);
    // markAt/markText: подписи под шкалой (шаг и текст); zoneText: надпись под красной зоной (или null)
    Bar(Context c, int steps, int zero, int[] markAt, String[] markText, String zoneText) {
        super(c);
        this.steps = steps;
        this.zero = zero;
        this.markAt = markAt;
        this.markText = markText;
        this.zoneText = zoneText;
        this.dp = c.getResources().getDisplayMetrics().density;
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, c.getResources().getDisplayMetrics()));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
        setClickable(true);       // OK на пульте вызывает щелчок: меню по нему закрывается, выбранное остаётся
    }

    void setListener(Listener l) { listener = l; }

    int pos() { return value; }

    void setPos(int v, boolean animate) {
        if (v == value && animate) return;
        value = v;
        if (anim != null) anim.cancel();
        if (!animate) {
            shown = v;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(shown, v);
        anim.setDuration(110);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> { shown = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { nudge(-1, e.getRepeatCount()); return true; }    // на краю тоже «съедаем»: курсор не прыгает
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { nudge(1, e.getRepeatCount()); return true; }
        return super.onKeyDown(code, e);
    }

    private void nudge(int dir, int repeat) {
        int n = Math.max(0, Math.min(steps, value + dir * (repeat < 6 ? 1 : repeat < 16 ? 2 : 5)));
        if (repeat > 0 && zero >= 0 && (value - zero) * (n - zero) <= 0) n = zero;
        if (n == value) return;
        setPos(n, true);
        if (listener != null) listener.onPos(n);
    }

    @Override protected void onFocusChanged(boolean gained, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gained, dir, prev);
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        // Ширину задаёт меню (MATCH_PARENT): сам по себе ползунок не растягивает его на весь экран
        setMeasuredDimension(resolveSize(Math.round(200 * dp), w), resolveSize(Math.round(62 * dp), h));
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    // Подпись по центру под точкой, но не за краем ползунка
    private void mark(Canvas cv, String s, float x, float y, float w) {
        float half = labelPaint.measureText(s) / 2 + 4 * dp;
        cv.drawText(s, Math.max(half, Math.min(w - half, x)), y, labelPaint);
    }

    @Override protected void onDraw(Canvas cv) {
        boolean lit = isFocused();
        boolean red = zero >= 0 && value < zero;     // бегунок в красной зоне
        float w = getWidth(), h = getHeight();

        // Строка в фокусе: светлая подложка с кромкой, как в панели «Звук»
        if (lit) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x1FFFFFFF);
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, 14 * dp, 14 * dp, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp);
            paint.setColor(0x4DFFFFFF);
            rect.set(dp / 2, dp / 2, w - dp / 2, h - dp / 2);
            cv.drawRoundRect(rect, 14 * dp, 14 * dp, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        float x0 = 30 * dp, x1 = w - 30 * dp, span = x1 - x0, cy = 24 * dp, th = 4 * dp, gap = 6 * dp;
        float xz = zero >= 0 ? x0 + span * zero / steps : -1;
        float redEnd = zero >= 0 ? xz - gap : x0;       // правый конец красной части дорожки
        float whiteStart = zero >= 0 ? xz + gap : x0;   // левый конец белой
        float tx = x0 + span * shown / steps;

        // Дорожка: слева красная зона (чуть заметнее, когда бегунок в ней), за отметкой «0» белая
        if (zero >= 0) {
            paint.setColor(red ? 0x59E5604F : 0x33E5604F);
            rect.set(x0, cy - th, redEnd, cy + th);
            cv.drawRoundRect(rect, th, th, paint);
        }
        paint.setColor(0x33FFFFFF);
        rect.set(whiteStart, cy - th, x1, cy + th);
        cv.drawRoundRect(rect, th, th, paint);

        // Заливка. С подсветкой от нуля: вправо белая, влево красная. Без подсветки слева до бегунка
        if (zero < 0) {
            if (tx > x0) {
                paint.setColor(lit ? 0xFFFFFFFF : 0xCCFFFFFF);
                rect.set(x0, cy - th, tx, cy + th);
                cv.drawRoundRect(rect, th, th, paint);
            }
        } else if (tx > whiteStart) {
            paint.setColor(lit ? 0xFFFFFFFF : 0xCCFFFFFF);
            rect.set(whiteStart, cy - th, tx, cy + th);
            cv.drawRoundRect(rect, th, th, paint);
        } else if (tx < redEnd) {
            paint.setColor(lit ? RED : 0xCCE5604F);
            rect.set(tx, cy - th, redEnd, cy + th);
            cv.drawRoundRect(rect, th, th, paint);
        }

        // Деления: точка каждые 10 шагов, на нуле чёрточка
        for (int i = 0; i <= steps; i += 10) {
            if (i == zero) continue;
            paint.setColor(zero >= 0 && i < zero ? 0x80E5604F : 0x59FFFFFF);
            cv.drawCircle(x0 + span * i / steps, cy + 16 * dp, 1.5f * dp, paint);
        }
        if (zero >= 0) {
            paint.setColor(0xCCFFFFFF);
            rect.set(xz - dp, cy - 12 * dp, xz + dp, cy + 12 * dp);
            cv.drawRoundRect(rect, dp, dp, paint);
        }

        // Подписи под шкалой
        float ly = cy + 33 * dp;
        labelPaint.setColor(0xB3FFFFFF);
        for (int i = 0; i < markAt.length; i++) mark(cv, markText[i], x0 + span * markAt[i] / steps, ly, w);
        if (zoneText != null && zero >= 0) {
            labelPaint.setColor(red ? 0xE6FF8F84 : 0x99FF8F84);
            cv.drawText(zoneText, (x0 + xz) / 2, ly, labelPaint);
        }

        // Бегунок: без теней, видеоядро слабое. В красной зоне розовеет
        float r = (lit ? 13 : 10) * dp;
        if (lit) {
            paint.setColor(red ? 0x40E5604F : 0x33FFFFFF);
            cv.drawCircle(tx, cy, r + 6 * dp, paint);
        }
        paint.setColor(red ? RED_KNOB : 0xFFFFFFFF);
        cv.drawCircle(tx, cy, r, paint);
    }
}
