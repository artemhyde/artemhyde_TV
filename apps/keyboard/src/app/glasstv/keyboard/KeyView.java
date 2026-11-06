package app.glasstv.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

// Вся клавиатура это один вид: на слабом видеоядре десятки отдельных кнопок дороже, чем один рисунок. Курсор перескакивает сразу,
// без анимации: на пульте важнее отклик. Стиль как у полос «Звук» и «Картинка»: графит, тонкая кромка, белый фокус
final class KeyView extends View {
    private static final float SIDE_DP = 26, UNIT_DP = 38, HGAP_DP = 4, VGAP_DP = 3, TOP_DP = 8, STRIP_DP = 36, QUICK_DP = 26, ROW_DP = 30, BOTTOM_DP = 10;
    private static final int ACCENT = 0xFF8FB8FF, DARK = 0xFF16161C, CARET = 0xFFFFD54A;

    interface Source {
        Layouts.Key[][] rows();
        int focusRow();      // Kbd.STRIP: курсор на строке с текстом
        int focusIndex();
        boolean pressed();
        boolean upperCase();
        boolean focusUpper();   // буква под курсором зажата: наберётся заглавной
        boolean shiftOn();
        boolean capsOn();
        String label(Layouts.Key k);
        boolean dim(Layouts.Key k);
        boolean languageRu();
        boolean hasLanguage();
        boolean onClear();      // на строке с текстом курсор на крестике
        boolean clearFont();    // пароль, адрес, почта: «I», «l», «1», «O» и «0» должны различаться
        // Строка с текстом
        String before();
        String selected();
        String after();
        String chip();
        String placeholder();
        String note();
        String hint();
    }

    private final Source src;
    private final float dp, sp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Shader shader;
    private float shaderH;

    KeyView(Context c, Source src) {
        super(c);
        this.src = src;
        dp = c.getResources().getDisplayMetrics().density;
        sp = c.getResources().getDisplayMetrics().scaledDensity;
        Typeface t;
        try { t = Typeface.createFromAsset(c.getAssets(), "fonts/Inter_18pt-Medium.ttf"); }
        catch (Exception e) { t = Typeface.DEFAULT; }
        text.setTypeface(t);
    }

    private float rowHeight(Layouts.Key[] row) { return (row.length > 0 && row[0].quick ? QUICK_DP : ROW_DP) * dp; }

    private float panelHeight() {
        float h = (TOP_DP + STRIP_DP + 6 + BOTTOM_DP) * dp;
        Layouts.Key[][] rows = src.rows();
        for (Layouts.Key[] row : rows) h += rowHeight(row) + VGAP_DP * dp;
        return h - VGAP_DP * dp;
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(panelHeight()));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (shader == null || shaderH != h) {
            shader = new LinearGradient(0, 0, 0, h, 0xFF302E38, 0xFF1E1C24, Shader.TileMode.CLAMP);
            shaderH = h;
        }
        // Панель прижата к низу, скруглены только верхние углы
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);   // прозрачность цвета действует и на градиент: иначе сквозь панель проступает страница
        fill.setShader(shader);
        r.set(0, 0, w, h + 30 * dp);
        cv.drawRoundRect(r, 22 * dp, 22 * dp, fill);
        fill.setShader(null);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Math.max(1, dp));
        fill.setColor(0x2EFFFFFF);
        cv.drawRoundRect(r, 22 * dp, 22 * dp, fill);
        fill.setStyle(Paint.Style.FILL);

        float unit = UNIT_DP * dp, left = (w - 24 * unit) / 2 + 2 * dp, y = TOP_DP * dp;
        drawStrip(cv, left, y, 24 * unit - HGAP_DP * dp);
        y += (STRIP_DP + 6) * dp;

        Layouts.Key[][] rows = src.rows();
        for (int i = 0; i < rows.length; i++) {
            float rh = rowHeight(rows[i]);
            for (int j = 0; j < rows[i].length; j++) {
                Layouts.Key k = rows[i][j];
                r.set(left + k.x * unit, y, left + (k.x + k.w) * unit - HGAP_DP * dp, y + rh);
                drawKey(cv, k, i == src.focusRow() && j == src.focusIndex());
            }
            y += rh + VGAP_DP * dp;
        }
    }

    private void drawKey(Canvas cv, Layouts.Key k, boolean focus) {
        boolean service = k.kind != Layouts.TEXT && k.kind != Layouts.SPACE;
        int bg;
        if (focus) bg = src.pressed() ? 0xFFC9CAD2 : 0xFFFFFFFF;
        else if (k.kind == Layouts.ENTER) bg = 0x598FB8FF;
        else if (k.kind == Layouts.SHIFT && (src.shiftOn() || src.capsOn())) bg = 0x388FB8FF;
        else if (k.quick) bg = 0x0DFFFFFF;
        else if (service) bg = 0x24FFFFFF;
        else bg = 0x14FFFFFF;
        fill.setColor(bg);
        cv.drawRoundRect(r, 8 * dp, 8 * dp, fill);

        if (k.kind == Layouts.LANG && src.hasLanguage()) { drawLanguage(cv, focus); return; }

        String s = src.label(k);
        if (k.isLetter() && (src.upperCase() || (focus && src.focusUpper()))) s = s.toUpperCase(java.util.Locale.ROOT);
        float size = k.quick ? (s.length() > 3 ? 14 : 16) : s.length() <= 1 ? 20 : s.length() <= 3 ? 16 : 14;
        text.setTextSize(size * sp);
        text.setTextAlign(Paint.Align.CENTER);
        int color;
        if (focus) color = DARK;
        else if (src.dim(k)) color = 0x4DFFFFFF;
        else if (k.kind == Layouts.SHIFT && (src.shiftOn() || src.capsOn())) color = ACCENT;
        else if (k.quick) color = 0xB8FFFFFF;
        else color = 0xEBFFFFFF;
        text.setColor(color);
        cv.drawText(s, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text);
        // Все заглавные: полоска под стрелкой
        if (k.kind == Layouts.SHIFT && src.capsOn()) {
            fill.setColor(focus ? DARK : ACCENT);
            cv.drawRect(r.centerX() - 8 * dp, r.bottom - 6 * dp, r.centerX() + 8 * dp, r.bottom - 4 * dp, fill);
        }
    }

    // Кнопка языка показывает оба языка, включённый ярче: видно и что сейчас, и куда переключится
    private void drawLanguage(Canvas cv, boolean focus) {
        text.setTextSize(15 * sp);
        text.setTextAlign(Paint.Align.LEFT);
        String a = "РУС", sep = " · ", b = "ENG";
        float total = text.measureText(a + sep + b), x = r.centerX() - total / 2, y = r.centerY() - (text.descent() + text.ascent()) / 2;
        boolean ru = src.languageRu();
        int on = focus ? DARK : 0xFFFFFFFF, off = focus ? 0x8016161C : 0x66FFFFFF;
        text.setColor(ru ? on : off);
        cv.drawText(a, x, y, text);
        x += text.measureText(a);
        text.setColor(off);
        cv.drawText(sep, x, y, text);
        x += text.measureText(sep);
        text.setColor(ru ? off : on);
        cv.drawText(b, x, y, text);
    }

    // Строка с текстом: всё набранное видно здесь, даже когда поле приложения закрыто клавиатурой
    private void drawStrip(Canvas cv, float x, float y, float width) {
        float h = STRIP_DP * dp;
        boolean onStrip = src.focusRow() == Kbd.STRIP, onClear = onStrip && src.onClear(), focus = onStrip && !onClear;
        fill.setColor(0x0FFFFFFF);
        r.set(x, y, x + width, y + h);
        cv.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        if (focus) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * dp);
            fill.setColor(0xFFFFFFFF);
            cv.drawRoundRect(r, 10 * dp, 10 * dp, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        // Чип: на каком языке сейчас пишем (или что это за страница)
        String chip = src.chip();
        text.setTextSize(14 * sp);
        text.setTextAlign(Paint.Align.CENTER);
        float cw = Math.max(64 * dp, text.measureText(chip) + 16 * dp);
        fill.setColor(0x1FFFFFFF);
        r.set(x + 6 * dp, y + 6 * dp, x + 6 * dp + cw, y + h - 6 * dp);
        cv.drawRoundRect(r, 6 * dp, 6 * dp, fill);
        text.setColor(0xFFFFFFFF);
        cv.drawText(chip, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text);

        float textLeft = r.right + 10 * dp, textRight = x + width - 10 * dp;
        String before = src.before(), selected = src.selected(), after = src.after();
        boolean empty = before.isEmpty() && selected.isEmpty() && after.isEmpty();

        // Кнопка в конце строки: словом, а не крестиком, крестик читается как «закрыть»
        if (!empty) {
            float cx = x + width - 8 * dp;
            text.setTextSize(14 * sp);
            String clear = "Стереть всё";
            fill.setColor(onClear ? 0xFFFFFFFF : 0x1FFFFFFF);
            r.set(cx - text.measureText(clear) - 16 * dp, y + 6 * dp, cx, y + h - 6 * dp);
            cv.drawRoundRect(r, 6 * dp, 6 * dp, fill);
            text.setColor(onClear ? DARK : 0xFFFFFFFF);
            cv.drawText(clear, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text);
            textRight = r.left - 10 * dp;
        }
        text.setTextAlign(Paint.Align.LEFT);

        String note = src.note();
        if (!note.isEmpty()) {
            text.setTextSize(14 * sp);
            float nw = text.measureText(note);
            text.setColor(0xCCFFFFFF);
            cv.drawText(note, textRight - nw, y + h / 2 - (text.descent() + text.ascent()) / 2, text);
            textRight -= nw + 14 * dp;
        }

        // Подсказка про удержания, только пока ей хватает места
        String hint = src.hint();
        if (note.isEmpty() && !hint.isEmpty()) {
            text.setTextSize(14 * sp);
            float hw = text.measureText(hint);
            text.setTextSize(20 * sp);
            String shown = empty ? src.placeholder() : before + selected + after;
            if (text.measureText(shown) + hw + 40 * dp < textRight - textLeft) {
                text.setTextSize(14 * sp);
                text.setColor(0xB3FFFFFF);
                cv.drawText(hint, textRight - hw, y + h / 2 - (text.descent() + text.ascent()) / 2, text);
                textRight -= hw + 14 * dp;
            }
        }

        text.setTextSize(20 * sp);
        float baseline = y + h / 2 - (text.descent() + text.ascent()) / 2;
        int save = cv.save();
        cv.clipRect(textLeft, y, textRight, y + h);
        if (empty) {
            text.setColor(0x73FFFFFF);
            cv.drawText(src.placeholder(), textLeft + 6 * dp, baseline, text);
            fill.setColor(CARET);
            cv.drawRect(textLeft, y + 8 * dp, textLeft + 3 * dp, y + h - 8 * dp, fill);
            cv.restoreToCount(save);
            return;
        }
        text.setFontFeatureSettings(src.clearFont() ? "zero, cv05, cv08" : null);
        float room = textRight - textLeft;
        float wb = text.measureText(before), ws = text.measureText(selected);
        // Курсор всегда в окне и не правее 70 % строки, пока справа ещё есть текст
        float limit = after.isEmpty() ? room - 6 * dp : room * 0.7f;
        float shift = Math.max(0, wb + ws - limit);
        float px = textLeft - shift;
        text.setColor(0xFFFFFFFF);
        cv.drawText(before, px, baseline, text);
        px += wb;
        if (!selected.isEmpty()) {
            fill.setColor(0x598FB8FF);
            cv.drawRect(px, y + 6 * dp, px + ws, y + h - 6 * dp, fill);
            text.setColor(0xFFFFFFFF);
            cv.drawText(selected, px, baseline, text);
            px += ws;
        } else {
            fill.setColor(CARET);
            cv.drawRect(px, y + 8 * dp, px + 3 * dp, y + h - 8 * dp, fill);
            px += 4 * dp;
        }
        text.setColor(0xFFFFFFFF);
        cv.drawText(after, px, baseline, text);
        boolean cutRight = px + text.measureText(after) > textRight;
        text.setFontFeatureSettings(null);
        cv.restoreToCount(save);
        // Строка не влезла: многоточие с той стороны, где обрезано, чтобы адрес не казался законченным
        text.setTextSize(14 * sp);
        text.setColor(0x99FFFFFF);
        if (shift > 0) cv.drawText("…", textLeft - 12 * dp, baseline, text);
        if (cutRight) cv.drawText("…", textRight + dp, baseline, text);
    }
}
