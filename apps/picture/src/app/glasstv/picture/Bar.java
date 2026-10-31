package app.glasstv.picture;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

// Ползунок для пульта (с 27.10 стиль общий с «Картинкой»): тонкая линия 4 dp, круглый бегунок, заполненная часть акцентом, без рамок.
// Сам ничего не слушает, значение ему задаёт панель. Для шкалы с минусом (-5..+5) заливка идёт от нуля, а ноль отмечен коротким штрихом,
// для обычной (0..12) от левого края. Для шкал из нескольких ступеней (ticks) на линии стоят точки по числу ступеней.
// Файл один и тот же в обоих приложениях, отличается только строка package.
final class Bar extends View {
    private final int min, max, accent;
    private final boolean ticks;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float shown;          // где сейчас рисуем бегунок (плавно догоняет value)
    private int value;
    private boolean lit;          // строка выбрана пультом: бегунок крупнее, заливка ярче
    private ValueAnimator anim;

    Bar(Context c, int min, int max, int value, boolean ticks, int accent) {
        super(c);
        this.min = min;
        this.max = max;
        this.value = value;
        this.shown = value;
        this.ticks = ticks;
        this.accent = accent;
        this.dp = c.getResources().getDisplayMetrics().density;
    }

    void set(int v, boolean animate) {
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

    void setLit(boolean on) {
        if (lit == on) return;
        lit = on;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas cv) {
        float r = (lit ? 9 : 7) * dp, pad = 11 * dp, w = getWidth(), cy = getHeight() / 2f, th = 2 * dp;   // линия 4 dp: полтолщины 2
        float x0 = pad, x1 = w - pad, span = x1 - x0;
        float zero = min < 0 ? x0 + span * (0 - min) / (float) (max - min) : x0;
        float tx = x0 + span * (shown - min) / (max - min);

        // Линия
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2EFFFFFF);
        rect.set(x0, cy - th, x1, cy + th);
        cv.drawRoundRect(rect, th, th, paint);

        // Заливка от нуля (или от края) до бегунка
        paint.setColor(lit ? accent : (accent & 0x00FFFFFF) | 0x99000000);
        rect.set(Math.min(zero, tx), cy - th, Math.max(zero, tx), cy + th);
        cv.drawRoundRect(rect, th, th, paint);

        // Ступени: точки на линии (только у шкал из нескольких значений)
        if (ticks) {
            for (int i = min; i <= max; i++) {
                float x = x0 + span * (i - min) / (max - min);
                paint.setColor(i * 1f <= shown && i >= Math.min(0, min) ? 0x66141418 : 0x59FFFFFF);
                cv.drawCircle(x, cy, 1.2f * dp, paint);
            }
        } else if (min < 0) {
            // Ноль на шкале с минусом: короткий штрих над линией
            paint.setColor(0x4DFFFFFF);
            rect.set(zero - 0.75f * dp, cy - 8 * dp, zero + 0.75f * dp, cy - 4 * dp);
            cv.drawRoundRect(rect, dp, dp, paint);
        }

        // Бегунок: белый круг без теней
        paint.setColor(0xFFFFFFFF);
        cv.drawCircle(tx, cy, r, paint);
    }
}
