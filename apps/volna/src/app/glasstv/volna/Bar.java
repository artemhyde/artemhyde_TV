package app.glasstv.volna;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;

// Полоска прогресса трека. Если ей разрешить фокус, это ещё и перемотка: в фокусе толще и с бегунком,
// стрелки влево и вправо мотают по 10 секунд (при удержании быстрее)
final class Bar extends View {
    interface Seek { void by(long ms); }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private float frac, focus;
    private Seek seek;

    Bar(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        track.setColor(0x33FFFFFF);
        fill.setColor(Ui.MINT);
        knob.setColor(0xFFFFFFFF);
    }

    // Сделать полосу перемоткой
    void seekable(Seek s) {
        seek = s;
        setFocusable(true);
        setOnFocusChangeListener((v, f) -> {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(focus, f ? 1f : 0f);
            a.setDuration(M.t(200));
            a.addUpdateListener(an -> { focus = (float) an.getAnimatedValue(); invalidate(); });
            a.start();
        });
    }

    void set(float f, int color) {
        f = Math.max(0, Math.min(1, f));
        if (Math.abs(f - frac) < 0.0005f && fill.getColor() == color) return;
        frac = f;
        fill.setColor(color);
        invalidate();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (seek != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            long step = e.getRepeatCount() > 3 ? 30_000 : 10_000;
            seek.by(code == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onDraw(Canvas c) {
        float th = (4 + 4 * focus) * dp, cy = getHeight() / 2f, rad = th / 2;
        r.set(0, cy - th / 2, getWidth(), cy + th / 2);
        c.drawRoundRect(r, rad, rad, track);
        float x = getWidth() * frac;
        r.set(0, cy - th / 2, x, cy + th / 2);
        if (frac > 0) c.drawRoundRect(r, rad, rad, fill);
        if (focus > 0.01f) {
            knob.setAlpha(Math.round(255 * focus));
            c.drawCircle(Math.max(rad, Math.min(getWidth() - rad, x)), cy, 8 * dp * focus, knob);
        }
    }
}
