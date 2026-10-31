package app.glasstv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

// Значок погоды, нарисованный линиями и плоскими формами: солнце, луна, облака, дождь, снег, туман, гроза
final class WeatherIcon extends View {
    private int code = -1;
    private boolean day = true;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            sun = new Paint(Paint.ANTI_ALIAS_FLAG), bolt = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeatherIcon(Context c) {
        super(c);
        fill.setColor(0xF2FFFFFF);
        line.setColor(0xE6FFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        sun.setColor(0xFFFFD36B);
        sun.setStrokeCap(Paint.Cap.ROUND);
        bolt.setColor(0xFFFFD36B);
    }

    void set(int code, boolean day) {
        this.code = code;
        this.day = day;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        line.setStrokeWidth(s * 0.075f);
        sun.setStrokeWidth(s * 0.075f);
        if (code < 0) return;
        boolean clear = code == 0, partly = code == 1 || code == 2;
        if (clear) { drawSky(c, w / 2, h / 2, s * 0.24f); return; }
        if (partly) drawSky(c, w * 0.36f, h * 0.36f, s * 0.17f);
        if (code == 45 || code == 48) { drawFog(c, w, h, s); return; }
        float cy = (code >= 51) ? h * 0.42f : h * 0.55f;
        drawCloud(c, w / 2, cy, s * (partly ? 0.30f : 0.36f));
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) drawRain(c, w, h, s);
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) drawSnow(c, w, h, s);
        else if (code >= 95) drawBolt(c, w, h, s);
    }

    private void drawSky(Canvas c, float cx, float cy, float r) {
        if (day) {
            c.drawCircle(cx, cy, r, sun);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                float x1 = (float) (cx + Math.cos(a) * r * 1.45), y1 = (float) (cy + Math.sin(a) * r * 1.45);
                float x2 = (float) (cx + Math.cos(a) * r * 1.85), y2 = (float) (cy + Math.sin(a) * r * 1.85);
                c.drawLine(x1, y1, x2, y2, sun);
            }
        } else {
            // Полумесяц: круг минус смещённый круг
            Path moon = new Path();
            moon.addCircle(cx, cy, r * 1.15f, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + r * 0.6f, cy - r * 0.45f, r * 1.0f, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            Paint m = new Paint(fill);
            m.setColor(0xFFF2E6C9);
            c.drawPath(moon, m);
        }
    }

    // Облако контуром под вес светлых цифр: общий силуэт из кругов, лёгкая заливка и линия
    private void drawCloud(Canvas c, float cx, float cy, float r) {
        Path p = circle(cx - r * 0.55f, cy + r * 0.15f, r * 0.55f);
        p.op(circle(cx + r * 0.05f, cy - r * 0.2f, r * 0.75f), Path.Op.UNION);
        p.op(circle(cx + r * 0.7f, cy + r * 0.2f, r * 0.5f), Path.Op.UNION);
        Path base = new Path();
        base.addRoundRect(new RectF(cx - r * 1.1f, cy + r * 0.1f, cx + r * 1.2f, cy + r * 0.7f), r * 0.3f, r * 0.3f, Path.Direction.CW);
        p.op(base, Path.Op.UNION);
        Paint soft = new Paint(fill);
        soft.setColor(0x26FFFFFF);
        c.drawPath(p, soft);
        c.drawPath(p, line);
    }

    private static Path circle(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
    }

    private void drawRain(Canvas c, float w, float h, float s) {
        for (int i = 0; i < 3; i++) {
            float x = w * (0.33f + 0.17f * i);
            c.drawLine(x, h * 0.74f, x - s * 0.06f, h * 0.9f, line);
        }
    }

    private void drawSnow(Canvas c, float w, float h, float s) {
        for (int i = 0; i < 3; i++) c.drawCircle(w * (0.33f + 0.17f * i), h * (i == 1 ? 0.86f : 0.8f), s * 0.045f, fill);
    }

    private void drawBolt(Canvas c, float w, float h, float s) {
        Path b = new Path();
        b.moveTo(w * 0.52f, h * 0.66f);
        b.lineTo(w * 0.42f, h * 0.84f);
        b.lineTo(w * 0.51f, h * 0.84f);
        b.lineTo(w * 0.45f, h * 0.98f);
        b.lineTo(w * 0.62f, h * 0.76f);
        b.lineTo(w * 0.53f, h * 0.76f);
        b.close();
        c.drawPath(b, bolt);
    }

    private void drawFog(Canvas c, float w, float h, float s) {
        for (int i = 0; i < 3; i++) {
            float y = h * (0.35f + 0.15f * i);
            c.drawLine(w * (0.2f + 0.05f * (i % 2)), y, w * (0.8f - 0.05f * (i % 2)), y, line);
        }
    }
}
