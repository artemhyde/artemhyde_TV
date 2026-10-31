package app.glasstv.nightmode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;

// Затемнение поверх экрана. Окно меню ночного режима (hole, в координатах экрана) остаётся без вуали: служба рисует поверх всех
// окон, в том числе поверх меню, и на 80 % подписи гасли бы вместе с фильмом (контраст около 1,5 к 1). Вокруг меню эффект виден как был
final class Veil extends View {
    private final Path path = new Path();
    private final int[] loc = new int[2];
    private int color;
    private Rect hole;
    private float radius;

    Veil(Context c) { super(c); }

    void setColor(int c) {
        if (c == color) return;
        color = c;
        invalidate();
    }

    // r: границы меню (null: вырез убран, вуаль целиком), radius: скругление его углов в пикселях
    void setHole(Rect r, float radius) {
        if ((r == null ? hole == null : r.equals(hole)) && radius == this.radius) return;
        hole = r == null ? null : new Rect(r);
        this.radius = radius;
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        if (Color.alpha(color) == 0) return;
        if (hole != null) {
            // Окно вуали может стоять не в нуле экрана: сдвигаем границы меню на его начало
            getLocationOnScreen(loc);
            path.rewind();
            path.addRoundRect(hole.left - loc[0], hole.top - loc[1], hole.right - loc[0], hole.bottom - loc[1],
                    radius, radius, Path.Direction.CW);
            cv.clipOutPath(path);
        }
        cv.drawColor(color);
    }
}
