package app.glasstv.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Стеклянная панель поверх экрана (10.11): открытая папка (как на iPhone: вырастает из своей плитки и уходит в неё),
// выбор приложений с отметками, выбор названия папки, вопрос «да или нет», своё фото с телефона.
// Панель это стеклянная карточка с заголовком и сеткой ячеек: значок приложения с подписью или надпись-кнопка.
// Фон под ней: размытый снимок экрана (папка на главном экране) или затемнение (поверх «Оформления»).
// Стекло карточки, тень и значки рисуются один раз в картинки; в кадре двигаются только масштаб, сдвиг и прозрачность.
// Пульт: стрелки по ячейкам, OK выбирает, удержание OK открывает меню ячейки (если оно есть), «Назад» закрывает
final class Sheet extends FrameLayout {
    static final class Item {
        final String key, label;
        String sub, glyph;
        Drawable icon;
        Bitmap art;
        boolean check, checked, chip, add, dim;
        Item(String key, String label) { this.key = key; this.label = label; }
        Item icon(Drawable d) { icon = d; return this; }
        Item checkable(boolean on) { check = true; checked = on; return this; }
        Item chip() { chip = true; return this; }
        Item add() { add = true; glyph = "plus"; return this; }
    }

    interface Act { void run(Sheet s, Item it, int index); }

    private static final ExecutorService ICONS = Executors.newSingleThreadExecutor();
    static final float RADIUS = 30;

    final HomeActivity a;
    final float dp;
    final List<Item> items;
    final int cols;
    final boolean round;
    final float cellW, iconSize;
    Act onPick, onLong;
    Runnable onBack, onGone;
    View content;          // своё содержимое над сеткой (код QR и шаги)
    RectF origin;          // плитка, из которой вырастает панель (точки экрана); null: из середины
    Bitmap backdropBmp;    // размытый снимок экрана под панелью; null: затемнение
    float dimAlpha = 0.62f;
    boolean instant;       // без появления: панель заменяет такую же на том же месте (папку обновили)
    String folderId;       // открытая папка: её панель обновляется, когда папку меняют
    View typing;           // поле ввода в панели: пока оно в фокусе, буквы и стрелки идут ему

    private final Handler h = new Handler(Looper.getMainLooper());
    private View backdrop;
    private FrameLayout holder, card, viewport;
    private LinearLayout grid;
    private TextView titleV;
    TextView hintV;
    private final List<Cell> cells = new ArrayList<>();
    private int focus;
    private Spring open, scroll;
    private boolean leaving, longFired;
    private int downAt = -1;
    private int cardW, cardH;
    String title, hint;

    Sheet(HomeActivity a, String title, String hint, List<Item> items, int cols, boolean round, float cellW, float iconSize) {
        super(a);
        this.a = a;
        this.dp = a.dp;
        this.title = title;
        this.hint = hint;
        this.items = items;
        this.cols = Math.max(1, cols);
        this.round = round;
        this.cellW = cellW;
        this.iconSize = iconSize;
        setClipChildren(false);
        setClipToPadding(false);
    }

    int px(float v) { return Math.round(v * dp); }

    // ===== Показать и убрать =====

    void show(FrameLayout parent) {
        build();
        parent.addView(this, new FrameLayout.LayoutParams(-1, -1));
        open = new Spring(0f, this::apply).tune(0.88f, 0.42f);
        open.eps = 0.0008f;
        open.onRest = () -> { if (leaving) gone(); };
        // Старт после раскладки, до первого кадра: иначе карточка на миг видна целиком
        getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                if (instant) open.snap(1f);
                else { apply(0f); open.to(1f); }
                return true;
            }
        });
        if (typing == null) focusCell(firstFocus(), false);
    }

    void dismiss(boolean now) {
        if (leaving) return;
        leaving = true;
        if (now || open == null) { gone(); return; }
        open.tune(1f, 0.34f);
        open.to(0f);
    }

    private void gone() {
        cover(false);
        if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
        if (onGone != null) { Runnable g = onGone; onGone = null; g.run(); }
    }

    boolean isLeaving() { return leaving; }

    int focusAt = -1;      // с какой ячейки начать (нынешний выбор в панели виджета); -1: как обычно

    private int firstFocus() {
        if (focusAt >= 0 && focusAt < items.size()) return focusAt;
        for (int i = 0; i < items.size(); i++) if (items.get(i).checked && !items.get(i).check) return i;
        return 0;
    }

    // Ход появления p: карточка растёт из плитки (или из середины), фон темнеет, ячейки проявляются следом
    private boolean covering;

    // Размытый снимок проявляется за первую треть хода и дальше закрывает экран целиком: всё, что под ним (главный экран,
    // «Оформление»), в это время не рисуется. Иначе видеоядро ТВ рисовало бы два экрана на каждом кадре (замер 10.11:
    // открытая панель 22 мс работы видеоядра на кадр, треть кадров с рывком)
    private void cover(boolean on) {
        if (covering == on || backdropBmp == null) return;
        covering = on;
        a.coverUnder(on);
    }

    private void apply(float p) {
        if (holder == null) return;
        float q = Math.max(0f, Math.min(1f, p));
        if (backdrop != null) backdrop.setAlpha(backdropBmp != null ? Math.min(1f, q * 3f) : q);
        cover(q >= 0.34f);
        float hw = holder.getWidth(), hh = holder.getHeight();
        if (hw <= 0) return;
        float cx = holder.getLeft() + hw / 2f, cy = holder.getTop() + hh / 2f;
        if (origin != null) {
            float s0 = Math.max(0.12f, Math.min(origin.width() / Math.max(1f, cardW), origin.height() / Math.max(1f, cardH)));
            float s = s0 + (1f - s0) * p;
            holder.setScaleX(s);
            holder.setScaleY(s);
            holder.setTranslationX((origin.centerX() - cx) * (1f - p));
            holder.setTranslationY((origin.centerY() - cy) * (1f - p));
            holder.setAlpha(Math.min(1f, q * 3f));
        } else {
            float s = 0.92f + 0.08f * p;
            holder.setScaleX(s);
            holder.setScaleY(s);
            holder.setTranslationY((1f - p) * 14 * dp);
            holder.setAlpha(q);
        }
        float inner = Math.max(0f, Math.min(1f, (p - 0.35f) / 0.65f));
        if (viewport != null) viewport.setAlpha(inner);
        if (content != null) content.setAlpha(inner);
        titleV.setAlpha(inner);
        if (hintV != null) hintV.setAlpha(inner);
    }

    // ===== Вид =====

    private float cellH() {
        if (items.isEmpty()) return 0;
        boolean chips = items.get(0).chip;
        return chips ? 46 : iconSize + 10 + 20 + (hasSub() ? 16 : 0);
    }

    private boolean hasSub() {
        for (Item it : items) if (it.sub != null && !it.sub.isEmpty()) return true;
        return false;
    }

    private void build() {
        if (backdropBmp != null) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageBitmap(backdropBmp);
            backdrop = iv;
        } else {
            backdrop = new View(a);
            backdrop.setBackgroundColor(Color.argb(Math.round(255 * dimAlpha), 4, 4, 8));
        }
        backdrop.setAlpha(0f);
        addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        float padH = 34, padTop = 28, gapX = 14, gapY = 18;
        int rows = items.isEmpty() ? 0 : (items.size() + cols - 1) / cols;
        float gridW = cols * cellW + (cols - 1) * gapX;
        float contentW = items.isEmpty() ? 0 : gridW + 24;
        if (content != null) {
            content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            contentW = Math.max(contentW, content.getMeasuredWidth() / dp + 24);
        }
        cardW = px(Math.max(contentW, 360) + 2 * (padH - 12));
        float rowH = cellH();
        // Видно целое число строк (не больше трёх рядов значков): полустрока внизу выглядела бы обрезанной
        int maxRows = Math.max(1, (int) Math.floor((330 + gapY) / (rowH + gapY)));
        int shownRows = Math.min(rows, maxRows);
        float visibleGrid = shownRows * rowH + Math.max(0, shownRows - 1) * gapY;

        holder = new Studio.Flat(a);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        card = new FrameLayout(a);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        LinearLayout col = new Studio.FlatRow(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(px(padH - 12), px(padTop), px(padH - 12), px(24));
        int textPad = px(12);
        titleV = Fonts.text(a, 24, Fonts.semi18 != null ? Fonts.semi18 : Fonts.med24, 0xFFFFFFFF);
        titleV.setText(title);
        titleV.setLetterSpacing(-0.015f);
        titleV.setFontFeatureSettings("");
        titleV.setSingleLine(true);
        titleV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleV.setPadding(textPad, 0, textPad, 0);
        col.addView(titleV, new LinearLayout.LayoutParams(-1, -2));
        if (content != null) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = px(18);
            cl.leftMargin = cl.rightMargin = textPad;
            col.addView(content, cl);
        }
        if (!items.isEmpty()) {
            viewport = new Studio.Flat(a);
            viewport.setClipChildren(true);
            grid = new Studio.FlatRow(a);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setClipChildren(false);
            grid.setClipToPadding(false);
            // Запас со всех сторон: ячейка в фокусе растёт, кольцо не должно обрезаться
            grid.setPadding(px(12), px(8), px(12), px(8));
            LinearLayout row = null;
            for (int i = 0; i < items.size(); i++) {
                if (i % cols == 0) {
                    row = new Studio.FlatRow(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setClipChildren(false);
                    row.setClipToPadding(false);
                    LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, px(rowH));
                    if (i > 0) rl.topMargin = px(gapY);
                    grid.addView(row, rl);
                }
                Cell c = new Cell(items.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(cellW), px(rowH));
                if (row.getChildCount() > 0) lp.leftMargin = px(gapX);
                row.addView(c, lp);
                cells.add(c);
            }
            viewport.addView(grid, new FrameLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(px(gridW + 24), px(visibleGrid + 16));
            vl.topMargin = px(content != null ? 14 : 16);
            vl.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(viewport, vl);
            scroll = new Spring(0f, v -> grid.setTranslationY(-v)).tune(1f, 0.38f);
            scroll.eps = 0.4f;
        }
        if (hint != null && !hint.isEmpty()) {
            hintV = Fonts.text(a, 13, Fonts.reg18, 0x8CFFFFFF);
            hintV.setText(hint);
            hintV.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.topMargin = px(14);
            col.addView(hintV, hl);
        }
        card.addView(col, new FrameLayout.LayoutParams(cardW, -2));
        col.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cardH = col.getMeasuredHeight();
        card.setBackground(new android.graphics.drawable.BitmapDrawable(a.getResources(), glassCard(cardW, cardH)));
        card.setClipToOutline(backdropBmp != null);
        // Тень карточки системная, по её скруглённому контуру: видеоядро рисует только полосу вокруг. Картинка тени во весь
        // размер карточки стоила этому ТВ около 10 мс на кадре появления (замер 10.11)
        final float rad = RADIUS * dp;
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
        });
        card.setElevation(24 * dp);
        int m = px(60);
        holder.addView(card, new FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER));
        holder.setAlpha(0f);
        addView(holder, new FrameLayout.LayoutParams(cardW + 2 * m, cardH + 2 * m, Gravity.CENTER));
        for (Cell c : cells) c.loadArt();
    }

    // Стекло карточки: светлая вуаль сверху вниз, кромка 1 точка, светлая грань сверху. Фон под ней уже размыт
    // Над размытым снимком карточка непрозрачная: в неё заранее подложен кусок того же снимка, что лежит под ней, а углы
    // скругляет обрезка по контуру. Выглядит как стекло (фон под ним неподвижен), а видеоядру ТВ не нужно смешивать
    // карточку с фоном на каждом кадре (замер 10.11: 32-битная картинка во весь размер стоит около 10 мс)
    private Bitmap glassCard(int w, int hh) {
        boolean solid = backdropBmp != null;
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, hh), solid ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = RADIUS * dp;
        RectF rr = new RectF(0, 0, w, hh);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (solid) {
            // Кусок снимка под карточкой: карточка стоит посередине экрана
            int sw = a.screen.getWidth() > 0 ? a.screen.getWidth() : Math.round(Grid.W * dp), sh = a.screen.getHeight() > 0 ? a.screen.getHeight() : Math.round(Grid.H * dp);
            float kx = backdropBmp.getWidth() / (float) sw, ky = backdropBmp.getHeight() / (float) sh;
            float l = (sw - w) / 2f, t = (sh - hh) / 2f;
            android.graphics.Rect src = new android.graphics.Rect(Math.round(l * kx), Math.round(t * ky), Math.round((l + w) * kx), Math.round((t + hh) * ky));
            c.drawBitmap(backdropBmp, src, new RectF(0, 0, w, hh), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            rr.inset(-r, -r);   // заливки ниже без своего скругления: углы срежет контур
        }
        p.setShader(new LinearGradient(0, 0, 0, hh, new int[]{0x33FFFFFF, 0x1CFFFFFF, 0x14FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(rr, r, r, p);
        p.setShader(null);
        // Тёмная подложка под вуалью: белый текст читается и на светлом фоне
        p.setColor(0x2E0A0A12);
        c.drawRoundRect(rr, r, r, p);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, dp));
        rim.setShader(new LinearGradient(0, 0, w * 0.4f, hh, new int[]{0x8CFFFFFF, 0x2EFFFFFF, 0x1FFFFFFF, 0x4DFFFFFF},
                new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        RectF ri = new RectF(0, 0, w, hh);
        ri.inset(dp / 2f, dp / 2f);
        c.drawRoundRect(ri, r, r, rim);
        return Tile.fixed(b);
    }

    private Bitmap cardShadow(int w, int hh) {
        float q = 0.25f;
        int m = px(60);
        int bw = Math.round((w + 2 * m) * q), bh = Math.round((hh + 2 * m) * q);
        Bitmap b = Bitmap.createBitmap(Math.max(1, bw), Math.max(1, bh), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x8C000000);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, 26 * dp * q), BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(m * q + 8 * dp * q, m * q, (m + w) * q - 8 * dp * q, (m + hh) * q), RADIUS * dp * q, RADIUS * dp * q, p);
        return Tile.fixed(b);
    }

    // ===== Ячейки =====

    void setChecked(int i, boolean on) {
        if (i < 0 || i >= cells.size()) return;
        items.get(i).checked = on;
        cells.get(i).check.to(on ? 1f : 0f);
    }

    Item focused() { return focus >= 0 && focus < items.size() ? items.get(focus) : null; }

    int focusIndex() { return focus; }

    private void focusCell(int i, boolean animated) {
        if (cells.isEmpty()) return;
        i = Math.max(0, Math.min(cells.size() - 1, i));
        if (focus < cells.size() && focus != i) cells.get(focus).setFocus(false, animated);
        focus = i;
        cells.get(i).setFocus(true, animated);
        // Строка в фокусе должна быть видна: сетка едет на пружине
        if (viewport != null && grid != null) {
            float rowH = cellH() * dp, gapY = 18 * dp;
            int row = i / cols;
            float top = row * (rowH + gapY), bottom = top + rowH + 16 * dp;
            if (row == 0) top = 0;
            float vh = viewport.getLayoutParams().height, cur = scroll.target;
            float to = cur;
            if (top - cur < 0) to = top;
            else if (bottom - cur > vh) to = bottom - vh;
            if (to != cur) { if (animated) scroll.to(to); else scroll.snap(to); }
        }
    }

    final class Cell extends View {
        final Item it;
        final Spring f, check, pop;
        float fAmt, cAmt;
        Bitmap art;
        final Paint pa = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final android.text.TextPaint sp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

        Cell(Item it) {
            super(a);
            this.it = it;
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            tp.setTypeface(Fonts.med18);
            tp.setTextSize((it.chip ? 17 : 15) * dp);
            tp.setTextAlign(Paint.Align.CENTER);
            sp.setTypeface(Fonts.reg18);
            sp.setTextSize(12 * dp);
            sp.setTextAlign(Paint.Align.CENTER);
            sp.setColor(0x80FFFFFF);
            f = new Spring(0f, v -> { fAmt = v; float s = 1f + (it.chip ? 0.05f : 0.1f) * v; setScaleX(s); setScaleY(s); invalidate(); }).tune(0.82f, 0.3f);
            f.eps = 0.002f;
            cAmt = it.checked ? 1f : 0f;
            check = new Spring(cAmt, v -> { cAmt = v; invalidate(); }).tune(0.55f, 0.3f);
            check.eps = 0.003f;
            pop = new Spring(1f, v -> { setScaleX(v * (1f + (it.chip ? 0.05f : 0.1f) * fAmt)); setScaleY(v * (1f + (it.chip ? 0.05f : 0.1f) * fAmt)); }).tune(0.5f, 0.26f);
            pop.eps = 0.001f;
        }

        void setFocus(boolean on, boolean animated) { if (animated) f.to(on ? 1f : 0f); else f.snap(on ? 1f : 0f); }

        void press() { pop.value = 1f; pop.velocity = -1.4f; pop.to(1f); }

        // Значок готовится в фоне: приложение со своим слоем (адаптивный значок) в круг или скруглённый квадрат
        void loadArt() {
            if (it.chip) return;
            final int s = px(iconSize);
            final Item item = it;
            ICONS.execute(() -> {
                Bitmap b = item.art != null ? item.art : item.add || item.glyph != null && item.icon == null ? glassIcon(s, item.glyph) : appIcon(item.icon, s, round);
                h.post(() -> { art = b; invalidate(); });
            });
        }

        @Override public boolean hasOverlappingRendering() { return false; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            int lab = blend(0xC7FFFFFF, 0xFFFFFFFF, fAmt);
            if (it.chip) {
                RectF r = new RectF(2 * dp, 2 * dp, w - 2 * dp, hh - 2 * dp);
                float rad = r.height() / 2f;
                fill.setColor(fAmt > 0.5f ? blend(0x24FFFFFF, 0xF2FFFFFF, (fAmt - 0.5f) * 2f) : blend(0x1FFFFFFF, 0x24FFFFFF, fAmt * 2f));
                c.drawRoundRect(r, rad, rad, fill);
                ring.setStrokeWidth(Math.max(1f, 0.75f * dp));
                ring.setAlpha(Math.round(255 * 0.2f * (1f - fAmt)));
                c.drawRoundRect(r, rad, rad, ring);
                tp.setColor(fAmt > 0.5f ? blend(0xFFFFFFFF, 0xFF141416, (fAmt - 0.5f) * 2f) : 0xF2FFFFFF);
                Paint.FontMetrics fm = tp.getFontMetrics();
                CharSequence t = android.text.TextUtils.ellipsize(it.label, tp, w - 28 * dp, android.text.TextUtils.TruncateAt.END);
                c.drawText(t, 0, t.length(), w / 2f, hh / 2f - (fm.ascent + fm.descent) / 2f, tp);
                if (it.check && cAmt > 0.01f) drawCheck(c, w - 18 * dp, hh / 2f, cAmt);
                return;
            }
            float s = iconSize * dp, x = (w - s) / 2f, y = 4 * dp;
            if (art != null) c.drawBitmap(art, null, new RectF(x, y, x + s, y + s), pa);
            if (it.dim) { fill.setColor(0x66000000); if (round) c.drawCircle(x + s / 2f, y + s / 2f, s / 2f, fill); else c.drawRoundRect(new RectF(x, y, x + s, y + s), s * 0.26f, s * 0.26f, fill); }
            if (fAmt > 0.01f) {
                ring.setStrokeWidth(2.2f * dp);
                ring.setAlpha(Math.round(240 * fAmt));
                float o = 3.2f * dp;
                if (round) c.drawCircle(x + s / 2f, y + s / 2f, s / 2f + o, ring);
                else c.drawRoundRect(new RectF(x - o, y - o, x + s + o, y + s + o), s * 0.26f + o, s * 0.26f + o, ring);
            }
            if (it.check && cAmt > 0.01f) drawCheck(c, x + s - 4 * dp, y + 4 * dp, cAmt);
            tp.setColor(lab);
            CharSequence t = android.text.TextUtils.ellipsize(it.label, tp, w - 4 * dp, android.text.TextUtils.TruncateAt.END);
            float base = y + s + 10 * dp + 15 * dp;
            c.drawText(t, 0, t.length(), w / 2f, base, tp);
            if (it.sub != null && !it.sub.isEmpty()) {
                CharSequence st = android.text.TextUtils.ellipsize(it.sub, sp, w - 4 * dp, android.text.TextUtils.TruncateAt.END);
                c.drawText(st, 0, st.length(), w / 2f, base + 16 * dp, sp);
            }
        }

        private void drawCheck(Canvas c, float cx, float cy, float k) {
            float cr = 9 * dp;
            c.save();
            c.scale(k, k, cx, cy);
            fill.setColor(Fonts.ACCENT);
            fill.setShadowLayer(3 * dp, 0, dp, 0x66000000);
            c.drawCircle(cx, cy, cr, fill);
            fill.clearShadowLayer();
            Glyph.draw(c, "check", cx, cy, cr * 1.5f, 0xFF0E1A16, 2.8f);
            c.restore();
        }
    }

    static int blend(int a, int b, float t) { return Studio.blend(a, b, t); }

    // Значок приложения в круге (кружки) или скруглённом квадрате (квадраты): слои адаптивного значка целиком
    static Bitmap appIcon(Drawable icon, int s, boolean round) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, s), Math.max(1, s), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path shape = new Path();
        RectF r = new RectF(0, 0, s, s);
        if (round) shape.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW);
        else shape.addRoundRect(r, s * 0.26f, s * 0.26f, Path.Direction.CW);
        c.save();
        c.clipPath(shape);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2A2A30);
        c.drawRect(r, p);
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            float k = s * 108f / 72f, o = (k - s) / 2f;
            Rect lb = new Rect(Math.round(-o), Math.round(-o), Math.round(-o + k), Math.round(-o + k));
            for (Drawable d : new Drawable[]{ad.getBackground(), ad.getForeground()}) {
                if (d == null) continue;
                d.setBounds(lb);
                d.draw(c);
            }
        } else if (icon != null) {
            float k = s * (round ? 1.12f : 1.04f), o = (k - s) / 2f;
            icon.setBounds(Math.round(-o), Math.round(-o), Math.round(-o + k), Math.round(-o + k));
            icon.draw(c);
        }
        // Стекло поверх: светлее сверху, чуть темнее снизу, светлая нить по верхнему краю
        p.setShader(new LinearGradient(0, 0, 0, s, new int[]{0x38FFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x1F000000},
                new float[]{0f, 0.42f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        c.restore();
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, s / 96f));
        rim.setColor(0x38FFFFFF);
        float in = rim.getStrokeWidth() / 2f;
        if (round) c.drawCircle(s / 2f, s / 2f, s / 2f - in, rim);
        else c.drawRoundRect(new RectF(in, in, s - in, s - in), s * 0.26f, s * 0.26f, rim);
        return Tile.fixed(b);
    }

    // Пустая стеклянная форма со знаком (ячейка «Добавить»)
    Bitmap glassIcon(int s, String glyph) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, s), Math.max(1, s), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, s, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        RectF r = new RectF(0, 0, s, s);
        if (round) c.drawCircle(s / 2f, s / 2f, s / 2f, p); else c.drawRoundRect(r, s * 0.26f, s * 0.26f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s / 64f));
        p.setColor(0x52FFFFFF);
        float in = p.getStrokeWidth() / 2f;
        if (round) c.drawCircle(s / 2f, s / 2f, s / 2f - in, p); else c.drawRoundRect(new RectF(in, in, s - in, s - in), s * 0.26f, s * 0.26f, p);
        if (glyph != null) Glyph.draw(c, glyph, s / 2f, s / 2f, s * 0.42f, 0xF2FFFFFF, 1.8f);
        return Tile.fixed(b);
    }

    // ===== Клавиши =====

    private final Runnable longPress = () -> {
        if (downAt < 0 || downAt != focus || onLong == null || leaving) return;
        longFired = true;
        Item it = focused();
        if (it != null) onLong.run(this, it, focus);
    };

    boolean key(KeyEvent e) {
        if (leaving) return true;
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Поле ввода в фокусе: всё, кроме «Назад» и «вниз», уходит ему (буквы, стрелки по тексту, OK клавиатуры)
        if (typing != null && typing.hasFocus() && kc != KeyEvent.KEYCODE_BACK) {
            if (kc != KeyEvent.KEYCODE_DPAD_DOWN || cells.isEmpty()) return false;
            if (down) {
                typing.clearFocus();
                a.hideKeyboard(typing);
                focusCell(0, true);
            }
            return true;
        }
        if (typing != null && kc == KeyEvent.KEYCODE_DPAD_UP && focus / cols == 0) {
            if (down) {
                if (focus < cells.size()) cells.get(focus).setFocus(false, true);
                typing.requestFocus();
            }
            return true;
        }
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
        a.restartIdle();
        if (kc == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) { if (onBack != null) onBack.run(); else dismiss(false); }
            return true;
        }
        if (ok) {
            if (down && e.getRepeatCount() == 0) {
                downAt = focus;
                longFired = false;
                h.removeCallbacks(longPress);
                if (onLong != null) h.postDelayed(longPress, HomeActivity.LONG_MS);
            } else if (!down) {
                h.removeCallbacks(longPress);
                boolean mine = downAt == focus;
                downAt = -1;
                if (longFired) { longFired = false; return true; }
                if (mine && !cells.isEmpty() && onPick != null) {
                    cells.get(focus).press();
                    Item it = focused();
                    if (it != null) onPick.run(this, it, focus);
                } else if (cells.isEmpty() && onPick != null) onPick.run(this, null, -1);
            }
            return true;
        }
        if (!down || cells.isEmpty()) return true;
        int d = 0;
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) d = focus % cols == 0 ? 0 : -1;
        else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) d = focus % cols == cols - 1 || focus + 1 >= cells.size() ? 0 : 1;
        else if (kc == KeyEvent.KEYCODE_DPAD_UP) d = focus - cols >= 0 ? -cols : 0;
        else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) d = focus + cols < cells.size() ? cols
                : (focus / cols < (cells.size() - 1) / cols ? cells.size() - 1 - focus : 0);
        if (d != 0) focusCell(focus + d, true);
        return true;
    }
}
