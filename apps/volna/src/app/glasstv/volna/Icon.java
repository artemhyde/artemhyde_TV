package app.glasstv.volna;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

// Круглая кнопка-значок плеера. Значки нарисованы кодом в одном стиле (без символов шрифтов и эмодзи).
// Обычная: прозрачная, в фокусе белый круг и тёмный значок. Главная (пауза/играть): всегда белый круг,
// в фокусе ещё внешнее кольцо и лёгкий рост
final class Icon extends View {
    static final int PREV = 0, NEXT = 1, PLAY = 2, PAUSE = 3, REW = 4, FWD = 5, HEART = 6, HEART_FILL = 7,
            DISLIKE = 8, MORE = 9, EXPAND = 10, CLOSE = 11;
    static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private int glyph;
    private final boolean primary;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            disc = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();
    private float focus;
    private int tint = 0xF2FFFFFF;

    Icon(Context c, int glyph, boolean primary) {
        super(c);
        this.glyph = glyph;
        this.primary = primary;
        dp = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        disc.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x99FFFFFF);
        ring.setStrokeWidth(3 * dp);
        setOnFocusChangeListener((v, f) -> {
            animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setStartDelay(0).setDuration(M.t(f ? 220 : 180))
                    .setInterpolator(EASE).start();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(focus, f ? 1f : 0f);
            a.setDuration(M.t(f ? 200 : 160));
            a.addUpdateListener(an -> { focus = (float) an.getAnimatedValue(); invalidate(); });
            a.start();
        });
    }

    void setGlyph(int g) { if (g != glyph) { glyph = g; invalidate(); } }

    // Цвет значка без фокуса (например, лайк включён: розовый)
    void setTint(int c) { if (c != tint) { tint = c; invalidate(); } }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
        int iconColor;
        if (primary) {
            // Правило одно на весь плеер: в фокусе белое. Без фокуса главная кнопка стеклянная, иначе фокус на ней не виден
            disc.setAlpha(Math.round(64 + 191 * focus));
            c.drawCircle(cx, cy, r - 3 * dp, disc);
            if (focus > 0.01f) {
                ring.setAlpha(Math.round(230 * focus));
                c.drawCircle(cx, cy, r - 1.5f * dp, ring);
            }
            iconColor = focus > 0.5f ? 0xFF111114 : 0xF2FFFFFF;
        } else {
            if (focus > 0.01f) {
                disc.setAlpha(Math.round(255 * focus));
                c.drawCircle(cx, cy, r, disc);
            }
            iconColor = focus > 0.5f ? 0xFF111114 : tint;
        }
        fill.setColor(iconColor);
        stroke.setColor(iconColor);
        float s = r * (primary ? 0.5f : 0.56f);   // половина стороны значка
        stroke.setStrokeWidth(Math.max(2 * dp, s * 0.16f));
        drawGlyph(c, cx, cy, s);
    }

    private void drawGlyph(Canvas c, float x, float y, float s) {
        p.reset();
        switch (glyph) {
            case PLAY:
                p.moveTo(x - s * 0.55f, y - s * 0.85f);
                p.lineTo(x + s * 0.85f, y);
                p.lineTo(x - s * 0.55f, y + s * 0.85f);
                p.close();
                round(c);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(x - s * 0.62f, y - s * 0.8f, x - s * 0.18f, y + s * 0.8f), s * 0.12f, s * 0.12f, fill);
                c.drawRoundRect(new RectF(x + s * 0.18f, y - s * 0.8f, x + s * 0.62f, y + s * 0.8f), s * 0.12f, s * 0.12f, fill);
                break;
            case NEXT:
            case PREV: {
                float d = glyph == NEXT ? 1 : -1;
                p.moveTo(x - d * s * 0.75f, y - s * 0.7f);
                p.lineTo(x + d * s * 0.35f, y);
                p.lineTo(x - d * s * 0.75f, y + s * 0.7f);
                p.close();
                round(c);
                float bx = x + d * s * 0.6f;
                c.drawRoundRect(new RectF(bx - s * 0.13f, y - s * 0.7f, bx + s * 0.13f, y + s * 0.7f), s * 0.1f, s * 0.1f, fill);
                break;
            }
            case FWD:
            case REW: {
                // Круговая стрелка с цифрой 10: не путается с «назад» и «дальше»
                c.save();
                if (glyph == REW) c.scale(-1, 1, x, y);
                float R = s * 0.92f;
                RectF o = new RectF(x - R, y - R, x + R, y + R);
                c.drawArc(o, 325, 300, false, stroke);
                double th = Math.toRadians(265);
                float px = (float) (x + R * Math.cos(th)), py = (float) (y + R * Math.sin(th));
                float dx = (float) -Math.sin(th), dy = (float) Math.cos(th);   // направление по часовой
                float nx = (float) Math.cos(th), ny = (float) Math.sin(th);    // от центра наружу
                float u = s * 0.42f;
                p.reset();
                p.moveTo(px + dx * u, py + dy * u);
                p.lineTo(px - dx * u * 0.25f + nx * u * 0.6f, py - dy * u * 0.25f + ny * u * 0.6f);
                p.lineTo(px - dx * u * 0.25f - nx * u * 0.6f, py - dy * u * 0.25f - ny * u * 0.6f);
                p.close();
                c.drawPath(p, fill);
                c.restore();
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(fill.getColor());
                t.setTypeface(Fonts.med18);
                t.setTextSize(s * 0.7f);
                t.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = t.getFontMetrics();
                c.drawText("10", x, y - (fm.ascent + fm.descent) / 2f + s * 0.06f, t);
                break;
            }
            case HEART:
            case HEART_FILL:
                heart(x, y + s * 0.05f, s * 0.92f);
                if (glyph == HEART_FILL) c.drawPath(p, fill); else c.drawPath(p, stroke);
                break;
            case DISLIKE: {
                // Рука большим пальцем вниз, контуром
                float u = s / 10f;
                p.moveTo(x - 7 * u, y - 7 * u);
                p.lineTo(x + 3 * u, y - 7 * u);
                p.quadTo(x + 6 * u, y - 7 * u, x + 6.5f * u, y - 4 * u);
                p.lineTo(x + 7.5f * u, y + 1 * u);
                p.quadTo(x + 8 * u, y + 4 * u, x + 5 * u, y + 4 * u);
                p.lineTo(x + 0.5f * u, y + 4 * u);
                p.lineTo(x + 1.5f * u, y + 7.5f * u);
                p.quadTo(x + 2 * u, y + 10 * u, x - 0.5f * u, y + 10 * u);
                p.lineTo(x - 4 * u, y + 3 * u);
                p.lineTo(x - 7 * u, y + 3 * u);
                p.close();
                c.drawPath(p, stroke);
                c.drawLine(x - 4 * u, y - 7 * u, x - 4 * u, y + 3 * u, stroke);
                break;
            }
            case MORE:
                for (int i = -1; i <= 1; i++) c.drawCircle(x + i * s * 0.62f, y, s * 0.16f, fill);
                break;
            case EXPAND: {
                float a = s * 0.75f, b = s * 0.2f;
                c.drawLine(x + b, y - a, x + a, y - a, stroke);
                c.drawLine(x + a, y - a, x + a, y - b, stroke);
                c.drawLine(x + a, y - a, x + b * 0.5f, y - b * 0.5f, stroke);
                c.drawLine(x - b, y + a, x - a, y + a, stroke);
                c.drawLine(x - a, y + a, x - a, y + b, stroke);
                c.drawLine(x - a, y + a, x - b * 0.5f, y + b * 0.5f, stroke);
                break;
            }
            case CLOSE: {
                float a = s * 0.58f;
                c.drawLine(x - a, y - a, x + a, y + a, stroke);
                c.drawLine(x - a, y + a, x + a, y - a, stroke);
                break;
            }
            default:
                break;
        }
    }

    // Залитая фигура со скруглёнными углами: заливка плюс обводка той же краской
    private void round(Canvas c) {
        Paint s = new Paint(stroke);
        s.setStrokeWidth(stroke.getStrokeWidth() * 0.6f);
        c.drawPath(p, fill);
        c.drawPath(p, s);
    }

    private void heart(float x, float y, float s) {
        p.moveTo(x, y + s * 0.78f);
        p.cubicTo(x - s * 1.05f, y + s * 0.05f, x - s * 0.95f, y - s * 0.95f, x - s * 0.42f, y - s * 0.85f);
        p.cubicTo(x - s * 0.18f, y - s * 0.8f, x - s * 0.05f, y - s * 0.62f, x, y - s * 0.45f);
        p.cubicTo(x + s * 0.05f, y - s * 0.62f, x + s * 0.18f, y - s * 0.8f, x + s * 0.42f, y - s * 0.85f);
        p.cubicTo(x + s * 0.95f, y - s * 0.95f, x + s * 1.05f, y + s * 0.05f, x, y + s * 0.78f);
        p.close();
    }
}
