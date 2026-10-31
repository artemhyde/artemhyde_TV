package app.glasstv.volna;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

// Подложка «стекло»: полупрозрачная заливка, светлая кромка (сверху ярче, как свет на краю стекла)
// и мягкий блик по верхней половине. Живого размытия нет: стекло лежит на уже размытом фоне
final class Glass extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius, stroke;
    private int fillColor;

    Glass(float radiusPx, float density, int fillColor) {
        this.radius = radiusPx;
        this.stroke = Math.max(1f, 0.75f * density);
        this.fillColor = fillColor;
        fill.setColor(fillColor);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
    }

    void setFill(int c) {
        if (c == fillColor) return;
        fillColor = c;
        fill.setColor(c);
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect b) {
        edge.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[]{0x66FFFFFF, 0x14FFFFFF, 0x29FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        shine.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        r.set(b);
        c.drawRoundRect(r, radius, radius, fill);
        c.drawRoundRect(r, radius, radius, shine);
        float h = stroke / 2f;
        r.inset(h, h);
        c.drawRoundRect(r, radius - h, radius - h, edge);
    }

    @Override public void setAlpha(int a) { fill.setAlpha(a); invalidateSelf(); }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
