package app.glasstv.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Раздел «Оформление» (10.11, по утверждённым макетам): живой главный экран сверху в уменьшенной карточке, снизу выбор.
// Разделы: «Стиль» (форма ряда: кружки или квадраты; материал: «Цвет» или Liquid Glass), «Обои», «Часы», «Виджеты»,
// «Ряд и папки». Стрелки выбирают и сразу показывают вариант на настоящем экране (примерка, Look.try*), OK оставляет,
// «Назад» значит готово: примерка без OK снимается.
// Карточка это сам главный экран: корень «Дома» уменьшается на пружине и скругляется, ничего не перерисовывается заново.
// Пока он едет, корень лежит готовой картинкой видеокарты (слой), в покое слоя нет, и текст в карточке чёткий.
// Фон за карточкой: те же обои, размытые и притушенные один раз в маленькую картинку. Ни одного живого размытия
final class Studio {
    static final String[] TABS = {"Стиль", "Обои", "Часы", "Виджеты", "Ряд и папки"};
    static final int T_STYLE = 0, T_WALL = 1, T_CLOCK = 2, T_WIDGETS = 3, T_ROW = 4;
    static final int Z_TABS = 0, Z_TRAY = 1, Z_ROW = 2;

    // Карточка: масштаб главного экрана и её место в точках сетки 960x540
    static final float S = 0.6f;
    static final float STAGE_TOP = 60;
    static final float RADIUS = 15;      // скругление карточки на экране
    static final float THUMB_W = 106, THUMB_H = 60, THUMB_R = 9, GAP = 14;

    final HomeActivity a;
    final float dp;
    final FrameLayout screen, root;
    final Handler h = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private StageBg bg;
    private FrameLayout front;
    private TabBar tabs;
    private FrameLayout trayBox;   // в нём ряд вариантов раздела; при смене раздела старый ряд уходит, новый приходит
    private LinearLayout tray;
    private TextView hint, live;
    private Spring open;
    private float progress;
    private boolean closing, shown;
    int tab = T_WALL, zone = Z_TRAY;
    private final List<Opt> opts = new ArrayList<>();
    private int focus;
    private Runnable afterClose;

    Studio(HomeActivity a, FrameLayout screen, FrameLayout root) {
        this.a = a;
        this.screen = screen;
        this.root = root;
        this.dp = a.dp;
    }

    int px(float v) { return Math.round(v * dp); }

    boolean isOpen() { return shown; }

    // ===== Вход и выход =====

    void open(int startTab) {
        if (shown) { if (startTab >= 0) selectTab(startTab, true); return; }
        shown = true;
        closing = false;
        Look.clearTries();
        tab = startTab >= 0 ? startTab : T_WALL;
        zone = Z_TRAY;
        build();
        selectTab(tab, false);
        // Карточка уменьшается без промежуточного слоя: слой во весь экран этот ТВ смешивает на каждом кадре дороже, чем
        // рисует сам главный экран (замер 10.11: вход со слоем 32 мс на кадр, без него 23)
        root.setPivotX(root.getWidth() / 2f);
        root.setPivotY(root.getHeight() / 2f);
        root.setOutlineProvider(outline);
        root.setClipToOutline(true);
        // Тень карточки системная, по её контуру: видеоядро рисует только полосу вокруг карточки, а не слой во весь размер
        root.setElevation(18 * dp);
        root.setForeground(rim);
        if (open == null) {
            open = new Spring(0f, this::apply).tune(1f, 0.46f);
            open.eps = 0.0008f;
        }
        // Кромка карточки появляется, когда карточка встала: её смена на каждом кадре перерисовывала бы весь главный экран
        open.onRest = () -> {
            if (!closing) rim.setAlpha(255);
            else finishClose();
        };
        open.tune(1f, 0.46f);
        open.to(1f);
        a.amb.onWall = this::wallChanged;
        a.amb.setCalm(true);
        refreshBackground();
        // Плашка «Так будет на экране» подсказывает при входе и уходит: в углу карточки бывает подпись фото
        h.removeCallbacks(hideLive);
        h.postDelayed(hideLive, 3200);
    }

    private final Runnable hideLive = () -> { if (live != null) live.animate().alpha(0f).setDuration(M.t(600)).withEndAction(() -> { if (live != null) live.setVisibility(View.GONE); }).start(); };

    // Готово: примерка снимается, главный экран возвращается на весь экран тем же путём
    void close(Runnable then) {
        if (!shown || closing) { if (then != null) then.run(); return; }
        closing = true;
        afterClose = then;
        rim.setAlpha(0);
        if (zone == Z_ROW) leaveRow();
        a.closeSheetsNow();
        revertTries();
        a.previewLockNow();
        open.tune(1f, 0.40f);
        open.to(0f);
    }

    // Сразу, без перехода (ТВ выключили, «Домой»): экран должен стать обычным в этом же кадре
    void closeNow() {
        if (!shown) return;
        a.closeSheetsNow();
        if (zone == Z_ROW) leaveRow();
        revertTries();
        a.previewLockNow();
        if (open != null) open.snap(0f);
        apply(0f);
        finishClose();
    }

    private void finishClose() {
        shown = false;
        closing = false;
        root.setClipToOutline(false);
        root.setElevation(0f);
        root.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        root.setForeground(null);
        root.setScaleX(1f);
        root.setScaleY(1f);
        root.setTranslationY(0f);
        if (front != null) screen.removeView(front);
        if (bg != null) screen.removeView(bg);
        front = null;
        bg = null;
        opts.clear();
        a.amb.onWall = null;
        a.amb.setCalm(false);
        a.studioClosed();
        Runnable t = afterClose;
        afterClose = null;
        if (t != null) t.run();
    }

    // Панель закрыла экран непрозрачным снимком: фон и выбор «Оформления» не рисуются (прозрачность 0)
    void cover(float al) {
        if (front != null) front.setAlpha(al);
        if (bg != null) bg.setVisibility(al > 0f ? View.VISIBLE : View.INVISIBLE);
    }

    // Ход перехода p (0 на весь экран, 1 карточка): масштаб, сдвиг, скругление, фон и рамки
    private void apply(float p) {
        progress = p;
        float s = 1f + (S - 1f) * p;
        float cy = (STAGE_TOP + Grid.H * S / 2f) * dp;
        root.setScaleX(s);
        root.setScaleY(s);
        root.setTranslationY(p * (cy - root.getHeight() / 2f));
        root.invalidateOutline();
        float q = Math.max(0f, Math.min(1f, p));
        if (bg != null) { bg.setAlpha(q); bg.invalidate(); }
        if (front != null) {
            float t = Math.max(0f, Math.min(1f, (p - 0.35f) / 0.65f));
            tabs.setAlpha(t);
            tabs.setTranslationY(-(1f - t) * 18 * dp);
            trayBox.setAlpha(t);
            trayBox.setTranslationY((1f - t) * 26 * dp);
            hint.setAlpha(t);
            if (live.getVisibility() == View.VISIBLE && !closing) live.setAlpha(t * t);
            else if (closing) live.setAlpha(Math.min(live.getAlpha(), t));
        }
    }

    private final ViewOutlineProvider outline = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            float r = progress * RADIUS * dp / S;
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.max(0f, r));
        }
    };

    // Тонкая светлая кромка карточки (1 точка экрана) и блик сверху: рисуется поверх корня в его же масштабе
    private final Drawable rim = new Drawable() {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int alpha = 0;
        @Override public void draw(Canvas c) {
            if (alpha == 0) return;
            float k = 1f / Math.max(0.01f, root.getScaleX());
            float r = progress * RADIUS * dp / S;
            RectF b = new RectF(getBounds());
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * k);
            p.setColor(Color.argb(Math.round(alpha * 0.16f), 255, 255, 255));
            b.inset(0.6f * k, 0.6f * k);
            c.drawRoundRect(b, r, r, p);
        }
        @Override public void setAlpha(int al) { if (alpha != al) { alpha = al; invalidateSelf(); } }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    };

    // ===== Вид =====

    private void build() {
        bg = new StageBg();
        bg.setAlpha(0f);
        screen.addView(bg, 0, new FrameLayout.LayoutParams(-1, -1));

        front = new Flat(a);
        front.setClipChildren(false);
        front.setClipToPadding(false);
        screen.addView(front, new FrameLayout.LayoutParams(-1, -1));

        tabs = new TabBar();
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, px(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = px(12);
        front.addView(tabs, tl);

        live = Fonts.text(a, 12, Fonts.med18, 0xE6FFFFFF);
        live.setText("Так будет на экране");
        live.setCompoundDrawablePadding(px(6));
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Fonts.ACCENT);
        dot.setSize(px(6), px(6));
        live.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        android.graphics.drawable.GradientDrawable lb = new android.graphics.drawable.GradientDrawable();
        lb.setColor(0x52000000);
        lb.setCornerRadius(px(12));
        live.setBackground(lb);
        live.setPadding(px(9), px(4), px(10), px(4));
        live.setAlpha(0f);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ll.rightMargin = px((Grid.W - Grid.W * S) / 2f + 10);
        ll.topMargin = px(STAGE_TOP + Grid.H * S - 10 - 22);
        front.addView(live, ll);

        trayBox = new Flat(a);
        trayBox.setClipChildren(false);
        trayBox.setClipToPadding(false);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        rl.topMargin = px(STAGE_TOP + Grid.H * S + 14);
        front.addView(trayBox, rl);

        hint = Fonts.text(a, 13, Fonts.reg18, 0x80FFFFFF);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hl.bottomMargin = px(9);
        front.addView(hint, hl);
        apply(0f);
    }

    // Фон за карточкой: тёмный цвет тех же обоев, сплошной. Замер на TCL 10.11 (примерка обоев, медиана кадра): с размытой
    // картинкой обоев 28 мс, с градиентом 27–57 мс, со сплошным цветом 11 мс. Этому видеоядру дорого всё, что смешивается
    // с тем, что под ним, во весь экран; сплошная заливка почти бесплатна. Обои меняются: цвет перетекает
    private void refreshBackground() {
        final Bitmap src = a.amb.shownBitmap();
        if (src == null || bg == null) return;
        final StageBg target = bg;
        WORK.execute(() -> {
            Bitmap b = Blur.of(src, 6, 0.42f);
            if (b == null) return;
            int col = average(b, 0, 0, b.getWidth(), b.getHeight());
            h.post(() -> { if (bg == target) target.show(col); });
        });
    }

    private static int average(Bitmap b, int l, int t, int r, int btm) {
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int y = t; y < btm; y += 2) for (int x = l; x < r; x += 2) {
            int c = b.getPixel(x, y);
            sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; n++;
        }
        if (n == 0) return 0xFF0B0B0E;
        // Ещё темнее и чуть спокойнее: фон не должен спорить с карточкой
        float k = 0.62f;
        return 0xFF000000 | ((int) (sr / n * k) << 16) | ((int) (sg / n * k) << 8) | (int) (sb / n * k);
    }

    final class StageBg extends View {
        int cur = 0xFF0E0E12, from = cur, to = cur;
        android.animation.ValueAnimator fade;

        StageBg() { super(a); }

        void show(int col) {
            from = cur;
            to = col;
            if (fade != null) fade.cancel();
            fade = android.animation.ValueAnimator.ofFloat(0f, 1f);
            fade.setDuration(M.t(450));
            fade.addUpdateListener(an -> { cur = blend(from, to, (float) an.getAnimatedValue()); invalidate(); });
            fade.start();
        }

        @Override public boolean hasOverlappingRendering() { return false; }

        @Override protected void onDraw(Canvas c) { c.drawColor(cur); }
    }

    private final Runnable bgLater = this::refreshBackground;

    private void wallChanged() {
        // Фон за карточкой перетекает, только когда примерка остановилась: при быстром переборе обоев два полноэкранных
        // слоя на каждом кадре этому ТВ дороги
        h.removeCallbacks(bgLater);
        h.postDelayed(bgLater, 650);
        if (tab == T_WALL) refreshThumbs();
        a.wallChanged();
    }

    // ===== Разделы =====

    void selectTab(int t, boolean animated) {
        int was = tab;
        if (shown && opts.size() > 0 && t != was) revertTries();
        tab = t;
        tabs.select(t, animated);
        int dir = t > was ? 1 : t < was ? -1 : 0;
        buildTray(animated ? dir : 0);
        // Часы видны крупно на экране блокировки: в разделе «Часы» карточка показывает его, в остальных главный с рядом
        a.previewLock(t == T_CLOCK);
        updateHint();
    }

    private void buildTray(int dir) {
        opts.clear();
        final LinearLayout was = tray;
        tray = new FlatRow(a);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tray.setClipChildren(false);
        tray.setClipToPadding(false);
        switch (tab) {
            case T_STYLE: buildStyle(); break;
            case T_WALL: buildWalls(); break;
            case T_CLOCK: buildClocks(); break;
            case T_WIDGETS: buildWidgets(); break;
            default: buildRow(); break;
        }
        int i = 0;
        for (Opt o : opts) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(THUMB_W), -2);
            if (i > 0) lp.leftMargin = px(GAP + (o.gapBefore ? 26 : 0));
            tray.addView(o, lp);
            i++;
        }
        trayBox.addView(tray, new FrameLayout.LayoutParams(-1, -2));
        // Прежний ряд уходит в сторону раздела, новый приходит с другой стороны, варианты лесенкой
        if (was != null) {
            was.animate().cancel();
            if (dir == 0) trayBox.removeView(was);
            else was.animate().alpha(0f).translationX(-dir * 48 * dp).setStartDelay(0).setDuration(M.t(120)).setInterpolator(Ambient.EASE)
                    .withEndAction(() -> trayBox.removeView(was)).start();
        }
        if (dir != 0) {
            int k = 0;
            for (Opt o : opts) {
                o.setAlpha(0f);
                o.setTranslationX(dir * 48 * dp);
                o.animate().alpha(1f).translationX(0).setStartDelay(M.t(70 + 24L * k)).setDuration(M.t(340)).setInterpolator(Ambient.EASE).start();
                k++;
            }
        }
        focus = Math.max(0, Math.min(opts.size() - 1, savedIndex()));
        for (int k = 0; k < opts.size(); k++) opts.get(k).setFocusAmount(zone == Z_TRAY && k == focus, false);
    }

    // Какой вариант сейчас выбран (на нём встаёт фокус при входе в раздел)
    private int savedIndex() {
        for (int k = 0; k < opts.size(); k++) if (opts.get(k).selected && !opts.get(k).toggle) return k;
        return 0;
    }

    private void updateHint() {
        if (hint == null) return;
        String[] parts;
        if (zone == Z_TABS) parts = new String[]{"←  →", " разделы  ·  ", "вниз", " выбор  ·  ", "Назад", " готово"};
        else if (zone == Z_ROW) parts = a.moving != null
                ? new String[]{"←  →", " переставить  ·  ", "OK", " положить"}
                : new String[]{"←  →", " плитка  ·  ", "OK", " взять  ·  ", "Назад", " к выбору"};
        else if (tab == T_WIDGETS) parts = new String[]{"←  →", " выбор  ·  ", "OK", " настроить  ·  ", "Назад", " готово"};
        else if (tab == T_ROW) parts = new String[]{"←  →", " выбор  ·  ", "OK", " сделать  ·  ", "Назад", " готово"};
        else parts = new String[]{"←  →", " выбор, сразу видно на экране  ·  ", "OK", " оставить  ·  ", "Назад", " готово"};
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            int st = b.length();
            b.append(parts[i]);
            if (i % 2 == 0) b.setSpan(new ForegroundColorSpan(0xD9FFFFFF), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        hint.setText(b);
    }

    // ----- Стиль -----

    private void buildStyle() {
        String st = Style.get(a), mt = Look.savedMaterial(a);
        opts.add(new Opt("Кружки", "Vision, по центру", Style.V1.equals(st), false, Opt.GROUP_FORM, Style.V1, w -> Thumbs.form(true, w)));
        opts.add(new Opt("Квадраты", "Графит, слева", Style.GRAPHITE.equals(st), false, Opt.GROUP_FORM, Style.GRAPHITE, w -> Thumbs.form(false, w)));
        Opt c = new Opt("Цвет", "значки в цвете", Look.COLOR.equals(mt), false, Opt.GROUP_MATERIAL, Look.COLOR, w -> Thumbs.material(false, w));
        c.gapBefore = true;
        opts.add(c);
        opts.add(new Opt("Liquid Glass", "прозрачное стекло", Look.LIQUID.equals(mt), false, Opt.GROUP_MATERIAL, Look.LIQUID, w -> Thumbs.material(true, w)));
    }

    // ----- Обои -----

    private void buildWalls() {
        String w = Look.savedWall(a);
        for (String k : Look.WALLS) {
            String sub = Look.PHOTO.equals(k) ? "утро, день, вечер" : Look.OWN.equals(k) ? (Walls.hasOwn(a) ? "OK: заменить" : "с телефона") : "";
            Opt o = new Opt(Look.wallTitle(k), sub, k.equals(w), false, Opt.GROUP_WALL, k, null);
            if (Look.OWN.equals(k) && !Walls.hasOwn(a)) o.glyph = "plus";
            opts.add(o);
        }
        refreshThumbs();
    }

    // Миниатюры фото читаются в фоне: фото дня и своё фото уменьшенными, градиенты рисуются сразу
    private void refreshThumbs() {
        final int tw = px(THUMB_W), th = px(THUMB_H);
        for (final Opt o : new ArrayList<>(opts)) {
            if (o.group != Opt.GROUP_WALL) continue;
            final String k = o.value;
            if (Look.OWN.equals(k) && !Walls.hasOwn(a)) continue;
            WORK.execute(() -> {
                Bitmap b = Walls.thumb(a, Data.get(a), k, tw, th);
                h.post(() -> { if (b != null) { o.glyph = null; o.setImage(b); } });
            });
        }
    }

    // ----- Часы -----

    private void buildClocks() {
        String ck = Look.savedClock(a);
        String time = new java.text.SimpleDateFormat("H:mm", java.util.Locale.forLanguageTag("ru")).format(new java.util.Date());
        opts.add(new Opt("Тонкие", "как было", Look.THIN.equals(ck), false, Opt.GROUP_CLOCK, Look.THIN, w -> Thumbs.clock(Look.THIN, time, w, null)));
        opts.add(new Opt("Плотные", "жирнее и чётче", Look.BOLD.equals(ck), false, Opt.GROUP_CLOCK, Look.BOLD, w -> Thumbs.clock(Look.BOLD, time, w, null)));
        final Bitmap photo = a.amb.shownBitmap();
        opts.add(new Opt("Стекло", "Liquid Glass", Look.GLASS.equals(ck), false, Opt.GROUP_CLOCK, Look.GLASS, w -> Thumbs.clock(Look.GLASS, time, w, photo)));
    }

    // ----- Виджеты -----

    // 10.11: OK на виджете открывает его настройку (Widgets): город, банк или крипта, цитаты или Библия, приложение музыки,
    // записки с телефона. Отметка у виджета: он сейчас на экране, подпись: что выбрано
    private static final String[] WIDGETS = {"weather", "rates", "verse", "music", "notes"};

    private void buildWidgets() {
        String[][] look = {{"Погода", "cloud"}, {"Курсы", "coin"}, {"Цитата", "quote"}, {"Музыка", "note"}, {"Записка", "pen"}};
        for (int i = 0; i < WIDGETS.length; i++) {
            String v = WIDGETS[i];
            opts.add(new Opt(look[i][0], widgetSub(v), widgetOn(v), true, Opt.GROUP_WIDGET, v, null).glyphed(look[i][1]));
        }
    }

    private boolean widgetOn(String v) {
        switch (v) {
            case "weather": return Setup.weatherOn(a);
            case "rates": return Setup.ratesOn(a);
            case "verse": return Setup.versesOn(a);
            case "music": return !"off".equals(Setup.music(a)) && Setup.musicApp(a) != null;
            case "notes": return Notes.on(a);
            default: return false;
        }
    }

    private String widgetSub(String v) {
        if (!widgetOn(v)) return "music".equals(v) && !"off".equals(Setup.music(a)) ? "нет приложения" : "не показывать";
        switch (v) {
            case "weather": String city = Setup.city(a); return city.isEmpty() ? "по месту" : city;
            case "rates": return Rates.CRYPTO.equals(Setup.rates(a)) ? "криптовалюта" : bankShort(Setup.ratesSource(a));
            case "verse": return Setup.versesTitle(a);
            case "music": String m = Setup.musicApp(a); return Setup.VOLNA.equals(m) ? "«Волна»" : a.appLabel(m);
            case "notes": return Notes.has(a) ? "на экране" : "с телефона";
            default: return "";
        }
    }

    // Настройку виджета поменяли: отметка и подпись варианта, экран и данные
    private void widgetDone(Opt o) {
        if (a.isDestroyed()) return;
        // Записка не трогает погоду и курсы: только экран блокировки
        if ("notes".equals(o.value)) a.amb.noteChanged();
        else a.widgetsChanged("weather".equals(o.value) || "rates".equals(o.value));
        if (!shown) return;
        boolean on = widgetOn(o.value);
        o.setSelected(on, true);
        o.setOn(on);
        o.setSub(widgetSub(o.value));
        o.press();
        if ("verse".equals(o.value) || "notes".equals(o.value)) a.previewLock(true);
    }

    // Короткая подпись банка под вариантом «Курсы»: по стране, из которой он
    static String bankShort(String src) {
        if (src == null) return "";
        switch (src) {
            case Rates.NBRB: return "Беларусь";
            case Rates.CBR: return "Россия";
            case Rates.NBU: return "Украина";
            case Rates.NBK: return "Казахстан";
            case Rates.ECB: return "евро";
            default: return "по стране";
        }
    }

    // ----- Ряд и папки -----

    private void buildRow() {
        opts.add(new Opt("Новая папка", "приложения вместе", false, false, Opt.GROUP_ACTION, "folder", null).glyphed("folder"));
        opts.add(new Opt("Добавить", "приложение в ряд", false, false, Opt.GROUP_ACTION, "add", null).glyphed("plus"));
        opts.add(new Opt("Переставить", "плитки ряда", false, false, Opt.GROUP_ACTION, "move", null).glyphed("move"));
        opts.add(new Opt("Вернуть ряд", "как был", false, false, Opt.GROUP_ACTION, "reset", null).glyphed("undo"));
    }

    // ===== Примерка, выбор, откат =====

    private final Runnable heavyTry = this::applyHeavyTry;

    // Стрелкой встали на вариант: показать его на экране (без записи)
    private void tryOpt(Opt o) {
        switch (o.group) {
            case Opt.GROUP_WALL:
                if (Look.OWN.equals(o.value) && !Walls.hasOwn(a)) return;
                Look.tryWall = o.value.equals(Look.savedWall(a)) ? null : o.value;
                a.amb.showWall(450);
                break;
            case Opt.GROUP_CLOCK:
                String was = Look.clock(a);
                Look.tryClock = o.value.equals(Look.savedClock(a)) ? null : o.value;
                if (!was.equals(Look.clock(a))) a.amb.applyClock(true);
                break;
            case Opt.GROUP_FORM:
            case Opt.GROUP_MATERIAL:
                h.removeCallbacks(heavyTry);
                h.postDelayed(heavyTry, 200);   // ряд перестраивается целиком: только когда стрелка остановилась
                break;
            case Opt.GROUP_WIDGET:
                // Цитата и записка живут на экране блокировки: встали на них, карточка показывает блокировку
                a.previewLock("verse".equals(o.value) || "notes".equals(o.value));
                break;
            default:
                break;
        }
    }

    // Примеряется только вариант в фокусе: форма или материал, вторая ось остаётся как выбрана
    private void applyHeavyTry() {
        if (!shown || tab != T_STYLE) return;
        Opt o = zone == Z_TRAY && focus < opts.size() ? opts.get(focus) : null;
        String st = o != null && o.group == Opt.GROUP_FORM ? o.value : Style.get(a);
        String mt = o != null && o.group == Opt.GROUP_MATERIAL ? o.value : Look.savedMaterial(a);
        String ts = st.equals(Style.get(a)) ? null : st, tm = mt.equals(Look.savedMaterial(a)) ? null : mt;
        boolean sc = !same(ts, Look.tryStyle), mc = !same(tm, Look.tryMaterial);
        Look.tryStyle = ts;
        Look.tryMaterial = tm;
        if (sc) a.restyle();
        else if (mc) a.rebuildDock();
    }

    private static boolean same(String x, String y) { return x == null ? y == null : x.equals(y); }

    // Снять примерку раздела: экран возвращается к выбранному
    private void revertTries() {
        h.removeCallbacks(heavyTry);
        boolean wall = Look.tryWall != null, clock = Look.tryClock != null, style = Look.tryStyle != null, mat = Look.tryMaterial != null;
        Look.clearTries();
        if (wall) a.amb.showWall(450);
        if (clock) a.amb.applyClock(true);
        if (style) a.restyle();
        else if (mat) a.rebuildDock();
    }

    // OK на варианте
    private void commit(Opt o) {
        switch (o.group) {
            case Opt.GROUP_WALL:
                if (Look.OWN.equals(o.value) && (!Walls.hasOwn(a) || Look.OWN.equals(Look.savedWall(a)))) { a.openUpload(); return; }
                Look.saveWall(a, o.value);
                Look.tryWall = null;
                a.amb.showWall(450);
                select(o);
                break;
            case Opt.GROUP_CLOCK:
                Look.saveClock(a, o.value);
                Look.tryClock = null;
                a.amb.applyClock(true);
                select(o);
                break;
            case Opt.GROUP_FORM:
                h.removeCallbacks(heavyTry);
                Style.set(a, o.value);
                Look.tryStyle = null;
                a.restyle();
                select(o);
                break;
            case Opt.GROUP_MATERIAL:
                h.removeCallbacks(heavyTry);
                String was = Look.material(a);
                Look.saveMaterial(a, o.value);
                Look.tryMaterial = null;
                if (!was.equals(Look.material(a))) a.rebuildDock();
                select(o);
                break;
            case Opt.GROUP_WIDGET:
                Widgets.open(a, o.value, () -> widgetDone(o));
                break;
            case Opt.GROUP_ACTION:
                action(o.value);
                break;
            default:
                break;
        }
    }

    private void select(Opt o) {
        for (Opt x : opts) if (x.group == o.group) x.setSelected(x == o, true);
    }

    private void action(String v) {
        switch (v) {
            case "folder": a.newFolderFlow(); break;
            case "add": a.addToRowFlow(); break;
            case "move": enterRow(); break;
            case "reset": a.resetRowFlow(); break;
            default: break;
        }
    }

    // ===== Перестановка в карточке =====

    private void enterRow() {
        zone = Z_ROW;
        for (Opt o : opts) o.setFocusAmount(false, true);
        a.rowPickStart();
        updateHint();
    }

    private void leaveRow() {
        if (a.moving != null) a.endMove();
        a.rowPickEnd();
        zone = Z_TRAY;
        if (focus < opts.size()) opts.get(focus).setFocusAmount(true, true);
        updateHint();
    }

    // ===== Клавиши =====

    boolean key(KeyEvent e) {
        int kc = e.getKeyCode();
        if (closing) return true;
        if (zone == Z_ROW) return rowKey(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (kc == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) close(null);
            return true;
        }
        if (!down) return true;
        a.restartIdle();
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (zone == Z_TABS) {
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int d = kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                int t = tab + d;
                if (t < 0 || t >= TABS.length) { tabs.bump(d); return true; }
                selectTab(t, true);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_DOWN || (ok && e.getRepeatCount() == 0)) {
                zone = Z_TRAY;
                tabs.setFocused(false);
                if (focus < opts.size()) { opts.get(focus).setFocusAmount(true, true); tryOpt(opts.get(focus)); }
                updateHint();
            }
            return true;
        }
        // Выбор внизу
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int d = kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            int f = focus + d;
            if (f < 0 || f >= opts.size()) {
                if (e.getRepeatCount() == 0 && focus < opts.size()) opts.get(focus).bump(d);
                return true;
            }
            opts.get(focus).setFocusAmount(false, true);
            focus = f;
            opts.get(focus).setFocusAmount(true, true);
            tryOpt(opts.get(focus));
            return true;
        }
        if (kc == KeyEvent.KEYCODE_DPAD_UP) {
            zone = Z_TABS;
            if (focus < opts.size()) opts.get(focus).setFocusAmount(false, true);
            if (tab == T_WIDGETS) a.previewLock(false);
            tabs.setFocused(true);
            updateHint();
            return true;
        }
        if (ok && e.getRepeatCount() == 0 && focus < opts.size()) {
            Opt o = opts.get(focus);
            o.press();
            commit(o);
            return true;
        }
        return true;
    }

    private boolean rowKey(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (a.moving != null) {
            boolean r = a.moveKey(e);
            if (a.moving == null) updateHint();   // положили: снова выбор плитки
            return r;
        }
        if (!down) return true;
        a.restartIdle();
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (e.getRepeatCount() == 0) leaveRow();
            return true;
        }
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            a.rowPickStep(kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (ok && e.getRepeatCount() == 0) {
            a.rowPickTake();
            updateHint();
        }
        return true;
    }

    // Короткое сообщение вместо подсказки внизу (папка создана, ряд вернулся): в карточке оно вышло бы мелким
    void toast(String s) {
        if (hint == null) return;
        h.removeCallbacks(restoreHint);
        hint.setText(s);
        hint.setTextColor(0xF2FFFFFF);
        h.postDelayed(restoreHint, 2600);
    }

    private final Runnable restoreHint = () -> { if (hint != null) { hint.setTextColor(0x80FFFFFF); updateHint(); } };

    // Своё фото пришло с телефона: в «Обоях» оно выбрано, на нём фокус
    void ownPhotoArrived() {
        if (!shown || tab != T_WALL) return;
        buildTray(0);
        for (int k = 0; k < opts.size(); k++) if (Look.OWN.equals(opts.get(k).value)) {
            if (focus < opts.size()) opts.get(focus).setFocusAmount(false, true);
            focus = k;
            opts.get(k).setFocusAmount(zone == Z_TRAY, true);
            opts.get(k).press();
        }
    }

    // Записка пришла с телефона, пока открыт раздел: подпись виджета «Записка» и короткое сообщение
    void notesChanged() {
        if (!shown) return;
        if (Notes.has(a)) toast("Записка пришла: она на экране блокировки");
        if (tab != T_WIDGETS) return;
        for (Opt o : opts) if ("notes".equals(o.value)) {
            boolean on = widgetOn("notes");
            o.setSelected(on, true);
            o.setOn(on);
            o.setSub(widgetSub("notes"));
        }
    }

    // Вернулись на «Дом» после экрана настроек (город для погоды): обновить подписи раздела
    void resumed() {
        if (!shown) return;
        if (tab == T_WIDGETS || tab == T_WALL) buildTray(0);
        if (zone == Z_TRAY && focus < opts.size()) opts.get(focus).setFocusAmount(true, false);
    }

    // ===== Вкладки сверху =====

    // Группы без промежуточного буфера при прозрачности (как Ambient.Flat): их части не лежат друг на друге, а этот ТВ
    // за каждый такой буфер платит целым проходом по экрану (замер 10.11: переход разделов 44 мс на кадр, шаги 14 мс)
    static final class Flat extends FrameLayout {
        Flat(android.content.Context c) { super(c); }
        @Override public boolean hasOverlappingRendering() { return false; }
    }

    static final class FlatRow extends LinearLayout {
        FlatRow(android.content.Context c) { super(c); }
        @Override public boolean hasOverlappingRendering() { return false; }
    }

    final class TabBar extends FrameLayout {
        final TextView[] label = new TextView[TABS.length];
        final Spring sx, sw, lift;
        final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF pr = new RectF();
        float pillX, pillW;
        boolean focused;
        final LinearLayout row;

        TabBar() {
            super(a);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            android.graphics.drawable.GradientDrawable b = new android.graphics.drawable.GradientDrawable();
            b.setColor(0x1AFFFFFF);
            b.setCornerRadius(px(20));
            b.setStroke(Math.max(1, px(0.75f)), 0x1FFFFFFF);
            setBackground(b);
            setPadding(px(3), px(3), px(3), px(3));
            pill.setColor(0xF0FFFFFF);
            pill.setShadowLayer(4 * dp, 0, 1.5f * dp, 0x40000000);
            row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < TABS.length; i++) {
                TextView t = Fonts.text(a, 15, Fonts.med18, 0xA3FFFFFF);
                t.setText(TABS[i]);
                t.setGravity(Gravity.CENTER);
                t.setPadding(px(15), 0, px(15), 0);
                t.setFontFeatureSettings("");
                row.addView(t, new LinearLayout.LayoutParams(-2, px(34)));
                label[i] = t;
            }
            addView(row, new FrameLayout.LayoutParams(-2, px(34)));
            // Белая плашка выбранного раздела едет на пружине, подписи меняют цвет по тому, насколько она их закрыла
            sx = new Spring(0f, v -> { pillX = v; recolor(); invalidate(); }).tune(0.86f, 0.32f);
            sx.eps = 0.3f;
            sw = new Spring(0f, v -> { pillW = v; recolor(); invalidate(); }).tune(0.86f, 0.32f);
            sw.eps = 0.3f;
            lift = new Spring(1f, v -> { setScaleX(v); setScaleY(v); }).tune(0.7f, 0.3f);
            lift.eps = 0.0005f;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (pillW <= 0) return;
            float l = getPaddingLeft() + pillX, t = getPaddingTop();
            pr.set(l, t, l + pillW, t + px(34));
            c.drawRoundRect(pr, px(17), px(17), pill);
        }

        void select(int i, boolean animated) {
            Runnable go = () -> {
                TextView t = label[i];
                float x = t.getLeft(), w = t.getWidth();
                if (animated && pillW > 0) { sx.to(x); sw.to(w); } else { sx.snap(x); sw.snap(w); }
                recolor();
            };
            if (label[i].getWidth() == 0) post(go); else go.run();
        }

        // Цвет подписи по тому, насколько её закрывает белая плашка: на плашке тёмная, вне её светлая
        void recolor() {
            float l = pillX, r = l + pillW;
            for (TextView t : label) {
                float tl = t.getLeft(), tr = t.getRight();
                float cover = Math.max(0f, Math.min(r, tr) - Math.max(l, tl)) / Math.max(1f, tr - tl);
                int c = blend(0xA3FFFFFF, 0xFF141416, cover);
                if (t.getCurrentTextColor() != c) t.setTextColor(c);
            }
        }

        void setFocused(boolean f) {
            focused = f;
            lift.to(f ? 1.06f : 1f);
            ((android.graphics.drawable.GradientDrawable) getBackground()).setColor(f ? 0x33FFFFFF : 0x1AFFFFFF);
        }

        void bump(int dir) {
            Spring s = new Spring(0f, this::setTranslationX).tune(0.5f, 0.3f);
            s.eps = 0.3f;
            s.velocity = dir * 260 * dp;
            s.to(0f);
        }

        @Override public boolean hasOverlappingRendering() { return false; }
    }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    // ===== Вариант выбора: миниатюра, подпись, отметка =====

    interface Art { Bitmap make(int w); }

    final class Opt extends LinearLayout {
        static final int GROUP_FORM = 1, GROUP_MATERIAL = 2, GROUP_WALL = 3, GROUP_CLOCK = 4, GROUP_WIDGET = 5, GROUP_ACTION = 6;
        final String value;
        final int group;
        final boolean toggle;
        boolean selected, gapBefore, on = true;
        String glyph;
        final Thumb thumb;
        final TextView label, sub;
        final Spring f, check, pop;

        Opt(String title, String subText, boolean selected, boolean toggle, int group, String value, Art art) {
            super(a);
            this.value = value;
            this.group = group;
            this.toggle = toggle;
            this.selected = selected;
            this.on = !toggle || selected;
            setOrientation(VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
            setGravity(Gravity.CENTER_HORIZONTAL);
            thumb = new Thumb();
            LayoutParams tl = new LayoutParams(px(THUMB_W), px(THUMB_H));
            tl.topMargin = px(6);
            addView(thumb, tl);
            label = Fonts.text(a, 15, Fonts.med18, 0xA3FFFFFF);
            label.setText(title);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setSingleLine(true);
            label.setFontFeatureSettings("");
            LayoutParams ll = new LayoutParams(px(THUMB_W + GAP), -2);
            ll.topMargin = px(10);
            addView(label, ll);
            sub = Fonts.text(a, 12, Fonts.reg18, 0x6BFFFFFF);
            sub.setText(subText);
            sub.setGravity(Gravity.CENTER_HORIZONTAL);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setFontFeatureSettings("");
            LayoutParams sl = new LayoutParams(px(THUMB_W + GAP), -2);
            sl.topMargin = px(2);
            addView(sub, sl);
            if (subText == null || subText.isEmpty()) sub.setVisibility(INVISIBLE);
            f = new Spring(0f, v -> { thumb.focusAmt = v; float s = 1f + 0.08f * v; thumb.setScaleX(s); thumb.setScaleY(s);
                label.setTranslationY(3 * dp * v); label.setTextColor(blend(0xA3FFFFFF, 0xF0FFFFFF, v)); thumb.invalidate(); }).tune(0.82f, 0.3f);
            f.eps = 0.002f;
            thumb.checkAmt = selected ? 1f : 0f;
            check = new Spring(selected ? 1f : 0f, v -> { thumb.checkAmt = v; thumb.invalidate(); }).tune(0.55f, 0.32f);
            check.eps = 0.003f;
            pop = new Spring(1f, v -> { setScaleX(v); setScaleY(v); }).tune(0.5f, 0.28f);
            pop.eps = 0.0008f;
            if (art != null) {
                // Рисунок варианта (форма, материал, часы): маленький, рисуется в фоне
                final int w = px(THUMB_W);
                WORK.execute(() -> { Bitmap b = art.make(w); h.post(() -> setImage(b)); });
            }
        }

        Opt glyphed(String g) { glyph = g; thumb.invalidate(); return this; }

        @Override public boolean hasOverlappingRendering() { return false; }

        void setImage(Bitmap b) { thumb.setImage(b); }

        void setFocusAmount(boolean on, boolean animated) {
            if (animated) f.to(on ? 1f : 0f); else f.snap(on ? 1f : 0f);
        }

        void setSelected(boolean s, boolean animated) {
            if (selected == s) return;
            selected = s;
            if (animated) {
                if (s) check.velocity = 6f;
                check.to(s ? 1f : 0f);
            } else check.snap(s ? 1f : 0f);
        }

        void setOn(boolean o) { on = o; thumb.invalidate(); }

        void setSub(String s) {
            sub.setText(s);
            sub.setVisibility(s == null || s.isEmpty() ? INVISIBLE : VISIBLE);
        }

        // Нажатие OK: вариант на миг проседает и отпружинивает
        void press() {
            pop.value = getScaleX();
            pop.velocity = -1.6f;
            pop.to(1f);
        }

        // Край ряда: вариант чуть подаётся в сторону и возвращается
        void bump(int dir) {
            Spring s = new Spring(0f, this::setTranslationX).tune(0.5f, 0.3f);
            s.eps = 0.3f;
            s.velocity = dir * 220 * dp;
            s.to(0f);
        }

        final class Thumb extends View {
            float focusAmt, checkAmt;
            Bitmap img;
            BitmapShader shader;
            final Paint pImg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint pShadow = new Paint(Paint.FILTER_BITMAP_FLAG);
            final RectF r = new RectF();
            final Matrix m = new Matrix();

            Thumb() {
                super(a);
                pRing.setStyle(Paint.Style.STROKE);
                pRing.setColor(Color.WHITE);
                pFill.setColor(0x14FFFFFF);
            }

            void setImage(Bitmap b) {
                img = b;
                shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                invalidate();
            }

            @Override public boolean hasOverlappingRendering() { return false; }

            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), hh = getHeight(), rad = THUMB_R * dp;
                // Тень только у варианта в фокусе: на тёмном фоне у остальных её не видно, а каждая стоит видеоядру
                Bitmap sh = focusAmt < 0.01f ? null : thumbShadow(getWidth(), getHeight());
                if (sh != null) {
                    pShadow.setAlpha(Math.round(255 * focusAmt));
                    c.drawBitmap(sh, null, new RectF(-14 * dp, -8 * dp, w + 14 * dp, hh + 22 * dp), pShadow);
                }
                r.set(0, 0, w, hh);
                if (shader != null) {
                    m.setScale(w / img.getWidth(), hh / img.getHeight());
                    shader.setLocalMatrix(m);
                    pImg.setShader(shader);
                    c.drawRoundRect(r, rad, rad, pImg);
                } else {
                    pFill.setColor(focusAmt > 0.01f ? blend(0x14FFFFFF, 0x24FFFFFF, focusAmt) : 0x14FFFFFF);
                    c.drawRoundRect(r, rad, rad, pFill);
                }
                // Тонкая светлая кромка у всех; в фокусе толстая белая рамка
                pRing.setStrokeWidth(Math.max(1f, 0.75f * dp));
                pRing.setAlpha(Math.round(255 * 0.16f * (1f - focusAmt)));
                if (focusAmt < 0.99f) c.drawRoundRect(inset(r, 0.4f * dp), rad, rad, pRing);
                if (glyph != null) Glyph.draw(c, glyph, w / 2f, hh / 2f, 25 * dp, on ? 0xF2FFFFFF : 0x73FFFFFF, 1.7f);
                if (focusAmt > 0.01f) {
                    pRing.setStrokeWidth(1.8f * dp);
                    pRing.setAlpha(Math.round(245 * focusAmt));
                    c.drawRoundRect(inset(r, -0.9f * dp), rad + 0.9f * dp, rad + 0.9f * dp, pRing);
                }
                if (checkAmt > 0.01f) {
                    float cr = 6.5f * dp, cx = w - 5 * dp - cr, cy = 5 * dp + cr;
                    c.save();
                    c.scale(checkAmt, checkAmt, cx, cy);
                    pFill.setColor(0xFFFFFFFF);
                    pFill.setShadowLayer(3 * dp, 0, dp, 0x59000000);
                    c.drawCircle(cx, cy, cr, pFill);
                    pFill.clearShadowLayer();
                    Glyph.draw(c, "check", cx, cy, cr * 1.55f, 0xFF141416, 2.6f);
                    c.restore();
                }
            }
        }
    }

    private static RectF inset(RectF r, float d) { RectF o = new RectF(r); o.inset(d, d); return o; }

    // Тень миниатюры: одна на размер, маленькая и размытая
    private Bitmap thumbShadowBmp;
    private int thumbShadowW;

    private Bitmap thumbShadow(int w, int hh) {
        if (thumbShadowBmp != null && thumbShadowW == w) return thumbShadowBmp;
        float q = 0.5f;
        int bw = Math.round((w + 28 * dp) * q), bh = Math.round((hh + 30 * dp) * q);
        Bitmap b = Bitmap.createBitmap(Math.max(1, bw), Math.max(1, bh), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x73000000);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, 7 * dp * q), BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(14 * dp * q, 12 * dp * q, (w + 14 * dp) * q, (hh + 12 * dp) * q), THUMB_R * dp * q, THUMB_R * dp * q, p);
        thumbShadowBmp = Tile.fixed(b);
        thumbShadowW = w;
        return thumbShadowBmp;
    }

    // ===== Рисунки вариантов «Стиля» и «Часов» =====

    static final class Thumbs {
        private Thumbs() { }

        private static Bitmap canvasBg(int w, int h, int c1, int c2) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            return b;
        }

        // Кружки или квадраты: три стеклянные фигуры ряда, средняя в фокусе
        static Bitmap form(boolean round, int w) {
            int hh = Math.round(w * THUMB_H / THUMB_W);
            Bitmap b = canvasBg(w, hh, 0xFF2A2F45, 0xFF151722);
            Canvas c = new Canvas(b);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            float unit = w / 106f;
            float cy = hh * 0.56f;
            for (int i = -1; i <= 1; i++) {
                boolean f = i == 0;
                float s = (round ? 23 : 20) * unit * (f ? 1.18f : 1f);
                float cx = w / 2f + i * (round ? 29 : 31) * unit;
                fill.setShader(new LinearGradient(0, cy - s, 0, cy + s, f ? 0x59FFFFFF : 0x33FFFFFF, f ? 0x24FFFFFF : 0x14FFFFFF, Shader.TileMode.CLAMP));
                ring.setStrokeWidth((f ? 1.6f : 1f) * unit);
                ring.setColor(f ? 0xE6FFFFFF : 0x59FFFFFF);
                if (round) {
                    c.drawCircle(cx, cy, s / 2f + 2 * unit, fill);
                    c.drawCircle(cx, cy, s / 2f + 2 * unit, ring);
                } else {
                    RectF r = new RectF(cx - s * 0.75f, cy - s * 0.42f, cx + s * 0.75f, cy + s * 0.42f);
                    c.drawRoundRect(r, 5 * unit, 5 * unit, fill);
                    c.drawRoundRect(r, 5 * unit, 5 * unit, ring);
                }
            }
            return Tile.fixed(b);
        }

        // «Цвет»: значок в цвете на дымчатом шаре; Liquid Glass: прозрачный шар с белым знаком
        static Bitmap material(boolean liquid, int w) {
            int hh = Math.round(w * THUMB_H / THUMB_W);
            Bitmap b = liquid ? canvasBg(w, hh, 0xFF5A7D95, 0xFF2C3A52) : canvasBg(w, hh, 0xFF3A3346, 0xFF1A1820);
            Canvas c = new Canvas(b);
            float unit = w / 106f, cx = w / 2f, cy = hh / 2f, rr = 21 * unit;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (liquid) {
                // Пятна света за стеклом, чтобы прозрачность читалась
                p.setShader(new RadialGradient(cx - 18 * unit, cy - 10 * unit, 34 * unit, 0x99FFD6A5, 0x00FFD6A5, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, hh, p);
                p.setShader(new RadialGradient(cx, cy, rr, new int[]{0x29FFFFFF, 0x14FFFFFF, 0x38FFFFFF}, new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rr, p);
                p.setShader(new RadialGradient(cx - rr * 0.4f, cy - rr * 0.64f, rr * 0.9f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rr, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.3f * unit);
                p.setColor(0xB3FFFFFF);
                c.drawCircle(cx, cy, rr - 0.6f * unit, p);
                Glyph.draw(c, "play", cx, cy, 20 * unit, 0xF2FFFFFF, 1.6f);
            } else {
                p.setShader(new LinearGradient(0, cy - rr, 0, cy + rr, 0xC0504C5E, 0xD0201E28, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rr, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(unit);
                p.setColor(0x59FFFFFF);
                c.drawCircle(cx, cy, rr - 0.5f * unit, p);
                p.setStyle(Paint.Style.FILL);
                float s = 16 * unit;
                RectF ic = new RectF(cx - s, cy - s * 0.72f, cx + s, cy + s * 0.72f);
                p.setColor(0xFFE8303A);
                c.drawRoundRect(ic, 6 * unit, 6 * unit, p);
                Glyph.draw(c, "play", cx, cy, 15 * unit, 0xFFFFFFFF, 1.6f);
            }
            return Tile.fixed(b);
        }

        // Часы «20:08» нужным видом; стекло поверх фото
        static Bitmap clock(String kind, String time, int w, Bitmap photo) {
            int hh = Math.round(w * THUMB_H / THUMB_W);
            Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            if (photo != null) {
                int bw = photo.getWidth(), bh = photo.getHeight();
                android.graphics.Rect src = new android.graphics.Rect(bw / 4, 0, bw * 3 / 4, bh * 9 / 16);
                c.drawBitmap(photo, src, new RectF(0, 0, w, hh), new Paint(Paint.FILTER_BITMAP_FLAG));
                c.drawColor(0x33000000);
            } else {
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, hh, 0xFF34323D, 0xFF1D1C23, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, hh, p);
            }
            float size = hh * 0.52f;
            if (Look.GLASS.equals(kind)) {
                Bitmap g = GlassClock.preview(time, size);
                if (g != null) c.drawBitmap(g, (w - g.getWidth()) / 2f, (hh - g.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
            } else {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                boolean bold = Look.BOLD.equals(kind);
                t.setTypeface(bold && Fonts.semi18 != null ? Fonts.semi18 : Fonts.light28);
                t.setTextSize(size);
                t.setLetterSpacing(bold ? -0.035f : -0.02f);
                t.setColor(0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                android.graphics.Rect tb = new android.graphics.Rect();
                t.getTextBounds(time, 0, time.length(), tb);
                c.drawText(time, w / 2f, hh / 2f + tb.height() / 2f, t);
            }
            return Tile.fixed(b);
        }
    }
}
