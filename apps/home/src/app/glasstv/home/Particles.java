package app.glasstv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

// Частицы на фоне, как на главном экране PS5: светлая пыль, мягкие круги боке и редкие искры
// медленно плывут вверх, покачиваются и мерцают. Дальние (крупные боке) движутся медленнее, отсюда глубина.
// Рисуются готовыми спрайтами, каждый кадр экрана (postInvalidateOnAnimation), непрерывно и во время переходов:
// 18 спрайтов стоят видеоядру меньше миллисекунды, а замирание на нажатиях выглядело как подвисание (27.10)
final class Particles extends View {
    private static final int DOT = 0, BOKEH = 1, GLINT = 2;

    private final int n;
    private final int[] kind;
    private final float[] x0, y0, size, speed, sway, swayW, swayP, alpha, twW, twP, twDepth;
    private final Bitmap[] sprite = new Bitmap[3];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private long clock, last;
    private boolean running;

    Particles(Context c) {
        super(c);
        float dp = c.getResources().getDisplayMetrics().density;
        sprite[DOT] = disc(Math.round(8 * dp), new int[]{0xFFFFF6EC, 0x99FFF3E6, 0x00FFF3E6}, new float[]{0f, 0.45f, 1f});
        sprite[BOKEH] = disc(Math.round(48 * dp), new int[]{0x8CFFF1E2, 0x80FFF1E2, 0x99FFF1E2, 0x00FFF1E2}, new float[]{0f, 0.72f, 0.86f, 1f});
        sprite[GLINT] = glint(Math.round(24 * dp));
        // Расклад всегда один и тот же: экран узнаётся, а не мелькает случайностью
        Random r = new Random(20261026);
        int dots = 10, bokeh = 6, glints = 2;
        n = dots + bokeh + glints;
        kind = new int[n];
        x0 = new float[n]; y0 = new float[n]; size = new float[n]; speed = new float[n];
        sway = new float[n]; swayW = new float[n]; swayP = new float[n];
        alpha = new float[n]; twW = new float[n]; twP = new float[n]; twDepth = new float[n];
        for (int i = 0; i < n; i++) {
            int k = i < dots ? DOT : i < dots + bokeh ? BOKEH : GLINT;
            kind[i] = k;
            x0[i] = r.nextFloat();
            y0[i] = r.nextFloat();
            swayP[i] = r.nextFloat() * 6.283f;
            twP[i] = r.nextFloat() * 6.283f;
            if (k == DOT) {
                size[i] = (1f + r.nextFloat() * 1.5f) * dp;
                speed[i] = (5 + r.nextFloat() * 8) * dp / 2f;
                sway[i] = (4 + r.nextFloat() * 8) * dp / 2f;
                swayW[i] = period(9 + r.nextFloat() * 7);
                alpha[i] = 0.22f + r.nextFloat() * 0.33f;
                twW[i] = period(3 + r.nextFloat() * 4);
                twDepth[i] = 0.45f;
            } else if (k == BOKEH) {
                size[i] = (12 + r.nextFloat() * 34) * dp;
                speed[i] = (2 + r.nextFloat() * 4) * dp / 2f;
                sway[i] = (8 + r.nextFloat() * 16) * dp / 2f;
                swayW[i] = period(14 + r.nextFloat() * 10);
                alpha[i] = 0.05f + r.nextFloat() * 0.07f;
                twW[i] = period(8 + r.nextFloat() * 6);
                twDepth[i] = 0.3f;
            } else {
                size[i] = (7 + r.nextFloat() * 6) * dp;
                speed[i] = (3 + r.nextFloat() * 4) * dp / 2f;
                sway[i] = (3 + r.nextFloat() * 5) * dp / 2f;
                swayW[i] = period(12 + r.nextFloat() * 8);
                alpha[i] = 0.3f + r.nextFloat() * 0.25f;
                twW[i] = period(4 + r.nextFloat() * 5);
                twDepth[i] = 0.85f;
            }
        }
        setAlpha(0f);
    }

    private static float period(float s) { return 6.283f / (s * 1000f); }

    // Пусть частицы появятся (разблокировка) или погаснут (возврат к фото)
    void show(boolean on) { level(on ? 1f : LOCK, on ? 300 : 0); }

    static final float LOCK = 0.55f;   // на экране блокировки и в заставке частицы тише: фото главное

    // Плавно к нужной яркости; частицы живут всегда, пока экран открыт (и на блокировке тоже, 27.10)
    void level(float a, long delayMs) {
        animate().cancel();
        start();
        animate().alpha(a).setStartDelay(M.t(delayMs)).setDuration(M.t(1400)).setInterpolator(Ambient.EASE).start();
    }

    void start() {
        if (running) return;
        running = true;
        last = SystemClock.uptimeMillis();
        invalidate();
    }

    void stop() {
        running = false;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        if (w == 0) return;
        if (running) {
            long now = SystemClock.uptimeMillis();
            clock += Math.min(now - last, 100);   // после паузы окна без скачка
            last = now;
            postInvalidateOnAnimation();
        }
        float t = clock;
        for (int i = 0; i < n; i++) {
            float s = size[i], span = hh + 2 * s;
            // Плывут вверх и заходят снизу заново
            float y = y0[i] * span - (t / 1000f) * speed[i];
            y = ((y % span) + span) % span - s;
            float x = x0[i] * w + sway[i] * (float) Math.sin(t * swayW[i] + swayP[i]);
            float tw = 1f - twDepth[i] * 0.5f * (1f + (float) Math.sin(t * twW[i] + twP[i]));
            paint.setAlpha(Math.round(255 * alpha[i] * tw));
            dst.set(x - s, y - s, x + s, y + s);
            c.drawBitmap(sprite[kind[i]], null, dst, paint);
        }
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // Круг с мягким краем: точка пыли или боке (у боке чуть ярче ободок, как у настоящего пятна света)
    private static Bitmap disc(int d, int[] colors, float[] pos) {
        Bitmap b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(d / 2f, d / 2f, d / 2f, colors, pos, Shader.TileMode.CLAMP));
        c.drawCircle(d / 2f, d / 2f, d / 2f, p);
        return b;
    }

    // Искра: мягкий ореол и тонкая четырёхлучевая звёздочка
    private static Bitmap glint(int d) {
        Bitmap b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float m = d / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(m, m, m * 0.6f, new int[]{0x99FFF4E8, 0x00FFF4E8}, null, Shader.TileMode.CLAMP));
        c.drawCircle(m, m, m * 0.6f, p);
        p.setShader(null);
        p.setColor(0xE6FFFFFF);
        float l = m * 0.98f, wd = m * 0.09f;
        Path star = new Path();
        star.moveTo(m, m - l); star.lineTo(m + wd, m); star.lineTo(m, m + l); star.lineTo(m - wd, m); star.close();
        star.moveTo(m - l, m); star.lineTo(m, m + wd); star.lineTo(m + l, m); star.lineTo(m, m - wd); star.close();
        c.drawPath(star, p);
        c.drawCircle(m, m, m * 0.12f, p);
        return b;
    }
}
