package app.glasstv.home;

import android.graphics.Paint;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

// Сетка экрана 960x540 dp: поля по 56 dp, 12 колонок по 56 dp с зазором 16 dp, текст не ниже 500 dp.
// Всё положение элементов считается отсюда, чтобы края и базовые линии совпадали
final class Grid {
    static final int W = 960, H = 540;
    static final int MARGIN = 56, COL = 56, GAP = 16, STEP = COL + GAP;
    static final int RIGHT = W - MARGIN;          // правый край текста, 904
    static final int BOTTOM = 500;                 // нижняя базовая линия любого текста
    static final int TILE_W = 200, TILE_H = 112;   // одна плитка на весь экран: 3 колонки, 16:9
    static final int ROW_Y = 276;                  // верх ряда плиток
    static final int SUGGEST_Y = 420, SUGGEST_H = 64;

    static int span(int cols) { return cols * COL + (cols - 1) * GAP; }

    static int col(int i) { return MARGIN + i * STEP; }

    // Поставить текст так, чтобы его базовая линия легла ровно на baseline (dp).
    // gravity START: x левый край; END: x правый край
    static FrameLayout.LayoutParams at(TextView t, float dp, int x, int baseline, boolean end, int widthDp) {
        Paint.FontMetrics fm = t.getPaint().getFontMetrics();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthDp > 0 ? Math.round(widthDp * dp) : -2, -2,
                Gravity.TOP | (end ? Gravity.END : Gravity.START));
        lp.topMargin = Math.round(baseline * dp + fm.ascent);
        if (end) lp.rightMargin = Math.round((W - x) * dp); else lp.leftMargin = Math.round(x * dp);
        return lp;
    }

    // По центру экрана: базовая линия ровно на baseline (стиль «Vision»)
    static FrameLayout.LayoutParams center(TextView t, float dp, int baseline) {
        Paint.FontMetrics fm = t.getPaint().getFontMetrics();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(baseline * dp + fm.ascent);
        return lp;
    }

    // Текст по центру, растущий вверх от последней базовой линии (стих в «Vision»)
    static FrameLayout.LayoutParams aboveCenter(TextView t, float dp, int lastBaseline, int widthDp) {
        Paint.FontMetrics fm = t.getPaint().getFontMetrics();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(widthDp * dp), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(H * dp - lastBaseline * dp - fm.descent);
        return lp;
    }

    // Текст, растущий вверх от последней базовой линии (стих): низ блока = базовая линия + нижний вынос
    static FrameLayout.LayoutParams above(TextView t, float dp, int x, int lastBaseline, int widthDp) {
        Paint.FontMetrics fm = t.getPaint().getFontMetrics();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(widthDp * dp), -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.round(x * dp);
        lp.bottomMargin = Math.round(H * dp - lastBaseline * dp - fm.descent);
        return lp;
    }
}
