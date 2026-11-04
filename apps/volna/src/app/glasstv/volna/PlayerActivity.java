package app.glasstv.volna;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// Полноэкранный плеер (с плитки). Сетка 960x540 dp, поля 56 dp.
// Слева: обложка, название, перемотка, ряд кнопок как в любом плеере, ниже отдельно режимы и «Вкус».
// Справа: «Очередь» (что было и что дальше), «Мои треки» (весь вкус, выбрать и включить), «Текст» песни
public class PlayerActivity extends Activity implements Engine.Listener {
    static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    static final PathInterpolator SHEET = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    static final int SECOND_ACCENT = 0xFFE58FB5;   // цвет второго вкуса
    Engine e;
    float dp;
    FrameLayout root;
    ImageView bg, cover;
    View tint;
    TextView modeLabel, badge, upNext, title, artist, tPos, tDur, hint, hintR;
    Bar bar;
    Icon like, prev, rew, play, fwd, next, dislike, more;
    Pill myPill, newPill, tastePill, audioPill, phonesPill, toastPill;
    LinearLayout modesRow, extrasRow;
    Pill tabQueue, tabLib, tabText;
    TrackList queue, library;
    ScrollView lyricsScroll;
    LinearLayout lyricsBox;
    final List<TextView> lyricViews = new ArrayList<>();
    Lyrics lyrics;
    String lyricsFor = "";
    int lyricIdx = -2;
    boolean lyricsDone;
    int accent = Ui.MINT;
    Bitmap shownArt;
    int tab;   // 0 очередь, 1 мои треки, 2 текст
    final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        e = Engine.get(this);
        Fonts.init(this);
        dp = getResources().getDisplayMetrics().density;
        e.connect();
        if (!e.driving) e.start(e.mode);
        else if (!e.playing()) e.toggle();

        // Прозрачность экрана при входе и при смене вкуса ложится на каждый элемент, без слоя во весь экран
        root = new FrameLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        // Фон один слой во весь экран: размытая обложка, цвет и затемнение запечены в маленькую картинку в фоновом потоке.
        // Четыре полноэкранных слоя (цвет, картинка, оттенок, тень) видеоядро ТВ не тянуло: кадр выходил 19 мс и больше
        getWindow().setBackgroundDrawable(null);
        root.setBackgroundColor(0xFF0B0B0E);
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        buildLeft();
        buildRight();
        setContentView(root);
        wireFocus();
        // Обе волны подбираются заранее: смена режима сразу играет первый трек новой волны
        e.brain.refillIfLow(Brain.MY);
        e.brain.refillIfLow(Brain.NEW);

        // Вход: экран проявляется, обложка чуть подрастает
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(M.t(360)).setInterpolator(EASE).start();
        cover.setScaleX(0.94f);
        cover.setScaleY(0.94f);
        cover.animate().scaleX(1f).scaleY(1f).setDuration(M.t(560)).setInterpolator(SHEET).start();
        play.post(() -> play.requestFocusFromTouch());
    }

    int px(float v) { return Math.round(v * dp); }

    FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h), Gravity.TOP | Gravity.START);
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    void buildLeft() {
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF1A1A20);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * dp); }
        });
        root.addView(cover, at(56, 48, 184, 184));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        modeLabel = Fonts.text(this, Fonts.CAPTION, Fonts.med18, accent);
        modeLabel.setLetterSpacing(0.06f);
        meta.addView(modeLabel);
        badge = Fonts.text(this, Fonts.BODY, Fonts.reg18, Fonts.WHITE_72);
        badge.setMaxLines(2);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = px(12);
        meta.addView(badge, bl);
        // «Далее» не дублируем: очередь видна справа
        upNext = Fonts.text(this, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        upNext.setVisibility(View.GONE);
        meta.addView(upNext);
        root.addView(meta, at(264, 50, 240, 180));

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setClipChildren(false);
        stack.setClipToPadding(false);
        title = Fonts.text(this, Fonts.TITLE, Fonts.med24, Fonts.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAutoSizeTextTypeUniformWithConfiguration(22, 32, 1, TypedValue.COMPLEX_UNIT_SP);
        stack.addView(title, new LinearLayout.LayoutParams(-1, px(40)));
        artist = Fonts.text(this, Fonts.LEAD, Fonts.reg24, Fonts.WHITE_72);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = px(4);
        stack.addView(artist, al);
        bar = new Bar(this);
        bar.seekable(ms -> e.seekBy(ms));
        LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-1, px(18));
        brl.topMargin = px(10);
        stack.addView(bar, brl);
        LinearLayout times = new LinearLayout(this);
        tPos = Fonts.text(this, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        tDur = Fonts.text(this, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        times.addView(tPos, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(tDur);
        stack.addView(times, new LinearLayout.LayoutParams(-1, -2));

        // Ряд управления: лайк, назад, −10 с, пауза, +10 с, дальше, «не моё»
        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER);
        transport.setClipChildren(false);
        like = icon(transport, Icon.HEART, 44, false, v -> { e.like(); refresh(false); });
        prev = icon(transport, Icon.PREV, 48, false, v -> e.prev());
        rew = icon(transport, Icon.REW, 48, false, v -> e.seekBy(-10_000));
        play = icon(transport, Icon.PAUSE, 60, true, v -> e.toggle());
        fwd = icon(transport, Icon.FWD, 48, false, v -> e.seekBy(10_000));
        next = icon(transport, Icon.NEXT, 48, false, v -> e.next());
        dislike = icon(transport, Icon.DISLIKE, 44, false, v -> e.dislike());
        ((LinearLayout.LayoutParams) dislike.getLayoutParams()).leftMargin = px(28);   // «не моё» подальше от «дальше»
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(-1, px(64));
        trl.topMargin = px(6);
        stack.addView(transport, trl);
        hint = Fonts.text(this, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_72);
        hint.setGravity(Gravity.CENTER);
        stack.addView(hint, new LinearLayout.LayoutParams(-1, px(18)));

        // Режимы отдельно: какая волна, чей вкус, и «ещё» с редкими настройками
        FrameLayout modes = new FrameLayout(this);
        modes.setClipChildren(false);
        modesRow = new LinearLayout(this);
        modesRow.setClipChildren(false);
        myPill = pill(modesRow, "Моя волна", v -> switchMode(Brain.MY));
        newPill = pill(modesRow, "Новое для вас", v -> switchMode(Brain.NEW));
        tastePill = pill(modesRow, "", v -> switchTaste());
        // «Вкус» не режим волны, а чей вкус: отдельно от режимов
        ((LinearLayout.LayoutParams) tastePill.getLayoutParams()).leftMargin = px(20);
        more = new Icon(this, Icon.MORE, false);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(px(36), px(36));
        ml.leftMargin = px(8);
        modesRow.addView(more, ml);
        more.setOnClickListener(v -> showExtras(true));
        modes.addView(modesRow, new FrameLayout.LayoutParams(-1, px(36)));
        extrasRow = new LinearLayout(this);
        extrasRow.setClipChildren(false);
        audioPill = pill(extrasRow, "Только аудио", v -> startActivity(new Intent(this, BlackoutActivity.class)));
        phonesPill = pill(extrasRow, "Наушники", v -> openBluetooth());
        toastPill = pill(extrasRow, "", v -> { TrackToast.setAlways(e, !TrackToast.always(e)); refresh(false); });
        extrasRow.setVisibility(View.GONE);
        modes.addView(extrasRow, new FrameLayout.LayoutParams(-1, px(36)));
        LinearLayout.LayoutParams mrl = new LinearLayout.LayoutParams(-1, px(36));
        mrl.topMargin = px(10);
        mrl.topMargin = px(8);
        stack.addView(modes, mrl);

        root.addView(stack, at(56, 250, 448, -2));
    }

    void buildRight() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setClipChildren(false);
        tabQueue = pill(tabs, "Очередь", v -> showTab(0));
        tabLib = pill(tabs, "Мои треки", v -> showTab(1));
        tabText = pill(tabs, "Текст", v -> showTab(2));
        root.addView(tabs, at(552, 48, 300, 36));
        // Крестик: выключить музыку и закрыть «Волну» одним нажатием
        Icon close = new Icon(this, Icon.CLOSE, false);
        close.setOnClickListener(v -> { e.stop(); finish(); });
        root.addView(close, at(868, 48, 36, 36));

        FrameLayout box = new FrameLayout(this);
        queue = new TrackList(this, it -> { if (it.kind != TrackList.NOW) e.play(it.t, true); });
        // Выбранный трек играет, вкладка остаётся та же: список под курсором не пропадает
        library = new TrackList(this, it -> e.play(it.t, true));
        library.setVisibility(View.GONE);
        // Курсор поехал по строкам: подсказка под списком следит за ним («играет» или «включить»)
        android.widget.AdapterView.OnItemSelectedListener sel = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { updateHints(getCurrentFocus()); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        };
        queue.setOnItemSelectedListener(sel);
        library.setOnItemSelectedListener(sel);
        lyricsScroll = new ScrollView(this);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setVerticalFadingEdgeEnabled(true);
        lyricsScroll.setFadingEdgeLength(px(48));
        // Текст песни листается пультом: вниз с вкладки «Текст» встаёт на него, вверх и вниз листают
        lyricsScroll.setFocusable(true);
        GradientDrawable lf = new GradientDrawable();
        lf.setCornerRadius(px(16));
        lf.setStroke(px(2), 0x99FFFFFF);
        android.graphics.drawable.StateListDrawable ls = new android.graphics.drawable.StateListDrawable();
        ls.addState(new int[]{android.R.attr.state_focused}, lf);
        ls.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        lyricsScroll.setForeground(ls);
        lyricsBox = new LinearLayout(this);
        lyricsBox.setOrientation(LinearLayout.VERTICAL);
        lyricsBox.setPadding(px(8), px(80), px(8), px(160));
        lyricsScroll.addView(lyricsBox);
        lyricsScroll.setVisibility(View.GONE);
        box.addView(queue, new FrameLayout.LayoutParams(-1, -1));
        box.addView(library, new FrameLayout.LayoutParams(-1, -1));
        box.addView(lyricsScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(box, at(552, 96, 352, 404));
        // Подсказка под списком: что будет по OK там, где стоит фокус справа
        hintR = Fonts.text(this, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        hintR.setSingleLine(true);
        hintR.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(hintR, at(560, 506, 344, 26));
        showTab(0);
    }

    Icon icon(LinearLayout row, int glyph, int sizeDp, boolean primary, View.OnClickListener l) {
        Icon i = new Icon(this, glyph, primary);
        i.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp));
        if (row.getChildCount() > 0) lp.leftMargin = px(12);
        row.addView(i, lp);
        return i;
    }

    Pill pill(LinearLayout row, String text, View.OnClickListener l) {
        Pill p = new Pill(this, text, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(36));
        if (row.getChildCount() > 0) lp.leftMargin = px(8);
        row.addView(p, lp);
        return p;
    }

    void showExtras(boolean on) {
        View out = on ? modesRow : extrasRow, in = on ? extrasRow : modesRow;
        out.animate().alpha(0f).setDuration(M.t(160)).withEndAction(() -> out.setVisibility(View.GONE)).start();
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(M.t(220)).start();
        (on ? audioPill : more).requestFocus();
    }

    void showTab(int t) {
        tab = t;
        tabQueue.setOn(t == 0);
        tabLib.setOn(t == 1);
        tabText.setOn(t == 2);
        View[] views = {queue, library, lyricsScroll};
        for (int i = 0; i < 3; i++) {
            View v = views[i];
            if (i == t) {
                v.setAlpha(0f);
                v.setTranslationY(px(12));
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).translationY(0).setDuration(M.t(320)).setInterpolator(EASE).start();
            } else {
                v.setVisibility(View.GONE);
            }
        }
        if (t == 1) fillLibrary();
    }

    void fillLibrary() {
        List<TrackList.Item> items = new ArrayList<>();
        String now = e.shownId();
        for (Track t : e.store.library()) items.add(new TrackList.Item(t, t.id.equals(now) ? TrackList.NOW : TrackList.LIB));
        String keep = library.hasFocus() ? library.selectedId() : null;
        int top = library.selectedTop();
        library.set(items, accent);
        int i = keep == null ? -1 : library.indexOf(keep);
        if (i >= 0) library.setSelectionFromTop(i, top);
    }

    String queueSig = "";

    // Очередь пересобирается, только когда в ней что-то поменялось: Aura сообщает о себе по нескольку раз в секунду,
    // и пересборка списка на каждое сообщение давала подлагивания и прыжки прокрутки
    void fillQueue() {
        List<TrackList.Item> items = new ArrayList<>();
        StringBuilder sig = new StringBuilder().append(accent).append('|');
        List<Track> past = e.history();
        for (int i = Math.max(0, past.size() - 3); i < past.size(); i++) items.add(new TrackList.Item(past.get(i), TrackList.PAST));
        if (e.shownId() != null) {
            Track now = new Track(e.shownId(), e.shownTitle(), e.shownArtist(), e.shownDur());
            items.add(new TrackList.Item(now, TrackList.NOW));
        }
        if (e.driving) for (Track t : e.brain.upcoming(e.mode, 10)) items.add(new TrackList.Item(t, TrackList.NEXT));
        for (TrackList.Item it : items) sig.append(it.kind).append(it.t.id).append(it.t.title).append(';');
        if (sig.toString().equals(queueSig)) return;
        queueSig = sig.toString();
        String keep = queue.hasFocus() ? queue.selectedId() : null;
        int top = queue.selectedTop();
        queue.set(items, accent);
        int i = keep == null ? -1 : queue.indexOf(keep);
        if (i >= 0) queue.setSelectionFromTop(i, top);
        else if (!queue.hasFocus()) {
            int now = queue.indexOfNow();
            if (now >= 0) queue.setSelectionFromTop(now, px(64));
        }
    }

    // ===== Пульт (04.11): что нарисовано в той стороне, туда и идём =====
    // Слева сверху вниз: полоса перемотки, ряд кнопок, ряд режимов. Справа: вкладки с крестиком, под ними список (или текст).
    // Вверх с кнопок на перемотку, вниз обратно на ту же кнопку; между кнопками и режимами таблица «что под чем»,
    // а обратно на кнопку, с которой спустились. «Вправо» с края левой части в строку списка на той же высоте.
    // «Влево» из списка за одно нажатие на кнопку, с которой пришли, «Назад» из списка к вкладке. Где ничего нет, стоим

    View lastLeft, lastTransport;

    void wireFocus() {
        lastLeft = play;
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now != null && inLeft(now)) lastLeft = now;
            if (inTransport(now)) lastTransport = now;
            updateHints(now);
            // Вкладка открывается, когда на неё встал фокус, как на ТВ принято: OK для этого не нужен
            final int t = now == tabQueue ? 0 : now == tabLib ? 1 : now == tabText ? 2 : -1;
            if (t >= 0 && t != tab) h.postDelayed(() -> { if (tabPill() != now && now.isFocused()) showTab(t); }, 120);
        });
    }

    boolean inLeft(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null)
            if (p == like.getParent() || p == modesRow || p == extrasRow) return true;
        return false;
    }

    TrackList list() { return tab == 1 ? library : queue; }

    Pill tabPill() { return tab == 0 ? tabQueue : tab == 1 ? tabLib : tabText; }

    boolean inList(View v) { return v != null && (v == queue || v == library); }

    boolean inTransport(View v) { return v != null && v.getParent() == like.getParent(); }

    boolean inPills(View v) { return v != null && (v.getParent() == modesRow || v.getParent() == extrasRow); }

    boolean isTab(View v) { return v == tabQueue || v == tabLib || v == tabText; }

    // Что под кнопкой в ряду режимов
    View below(View t) {
        boolean ex = extrasRow.getVisibility() == View.VISIBLE;
        if (t == like || t == prev) return ex ? audioPill : myPill;
        if (t == rew || t == play) return ex ? phonesPill : newPill;
        if (t == fwd || t == next) return ex ? toastPill : tastePill;
        return ex ? toastPill : more;
    }

    // Что над таблеткой: кнопка, с которой спустились, иначе кнопка над ней по месту
    View above(View p) {
        if (lastTransport != null && below(lastTransport) == p) return lastTransport;
        if (p == myPill || p == audioPill) return prev;
        if (p == newPill || p == phonesPill) return play;
        if (p == tastePill || p == toastPill) return next;
        return dislike;
    }

    // В список сверху: на верхнюю видимую строку, она прямо под вкладками
    void enterTop() {
        if (tab == 2) { lyricsScroll.requestFocus(); return; }
        TrackList l = list();
        if (l.getCount() == 0) return;   // пусто: фокус остаётся, где был
        View c = l.getChildAt(0);
        int first = l.getFirstVisiblePosition(), top = c == null ? 0 : c.getTop();
        l.requestFocus();
        l.setSelectionFromTop(first, top);
    }

    // В список сбоку: строка на той же высоте, что и элемент слева, «по прямой»
    void enterAt(View from) {
        if (tab == 2) { lyricsScroll.requestFocus(); return; }
        TrackList l = list();
        if (l.getCount() == 0 || l.getChildCount() == 0) return;
        int[] a = new int[2], b = new int[2];
        from.getLocationOnScreen(a);
        l.getLocationOnScreen(b);
        int y = a[1] + from.getHeight() / 2 - b[1];
        int best = -1, top = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < l.getChildCount(); i++) {
            View c = l.getChildAt(i);
            int d = y < c.getTop() ? c.getTop() - y : y > c.getBottom() ? y - c.getBottom() : 0;
            if (d < bestD) { bestD = d; best = l.getFirstVisiblePosition() + i; top = c.getTop(); }
        }
        l.requestFocus();
        l.setSelectionFromTop(best, top);
    }

    void backToLeft() { (lastLeft != null && lastLeft.isShown() ? lastLeft : play).requestFocus(); }

    // Подсказки: слева под кнопками, справа под списком. Что будет по OK там, где стоит фокус
    void updateHints(View v) {
        set(hint, leftHint(v));
        set(hintR, rightHint(v));
    }

    String leftHint(View v) {
        if (v == bar) return "◀ ▶ перемотка, вниз к кнопкам";
        if (v == like) return e.liked() ? "Убрать лайк" : "Нравится";
        if (v == prev) return "Предыдущий трек";
        if (v == rew) return "Назад на 10 секунд";
        if (v == play) return e.playing() ? "Пауза · вверх перемотка" : "Играть · вверх перемотка";
        if (v == fwd) return "Вперёд на 10 секунд";
        if (v == next) return "Следующий трек";
        if (v == dislike) return "Не моё: убрать похожее";
        if (v == myPill) return "OK: включить «Мою волну»";
        if (v == newPill) return "OK: включить «Новое для вас»";
        if (v == tastePill) return "OK: вкус " + e.profileName(e.store.profile.isEmpty() ? Store.SECOND : Store.MAIN);
        if (v == more) return "OK: ещё настройки";
        if (v == audioPill) return "OK: погасить экран, музыка играет";
        if (v == phonesPill) return "OK: подключить наушники";
        if (v == toastPill) return "OK: когда показывать название трека";
        return "";
    }

    String rightHint(View v) {
        if (v == queue || v == library) return (((TrackList) v).selectedIsNow() ? "Играет сейчас" : "OK: включить этот трек") + " · Назад: к вкладкам";
        if (v == lyricsScroll) return "▲ ▼ листать текст · Назад: к вкладкам";
        if (v == tabText) return "Вниз: к тексту песни";
        if (isTab(v)) return "Вниз: в список";
        if (v != null && v.getParent() == root && v instanceof Icon) return "OK: выключить музыку и закрыть";
        return "";
    }

    // У TCL экран Bluetooth живёт в «Пультах и аксессуарах»; на чужой прошивке открываем общий
    void openBluetooth() {
        Intent[] tries = {
                new Intent().setComponent(new android.content.ComponentName("com.android.tv.settings",
                        "com.tcl.settings.accessories.AccessoriesActivity")),
                new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                new Intent(android.provider.Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { }
        }
    }

    void switchMode(int m) {
        if (m != e.mode || !e.driving) e.start(m);
        refresh(false);
    }

    // Смена вкуса как смена страницы: экран тает, меняется весь сразу (трек, очередь, цвет) и проявляется
    void switchTaste() {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(M.t(140)).setInterpolator(EASE).withEndAction(() -> {
            e.setProfile(e.store.profile.isEmpty() ? Store.SECOND : Store.MAIN);
            refresh(true);
            if (tab == 1) fillLibrary();
            root.animate().alpha(1f).setDuration(M.t(280)).setInterpolator(EASE).start();
        }).start();
    }

    boolean shownSecond;
    String shownFor = "";

    void refresh(boolean newTrack) {
        if (newTrack) loadLyrics();
        // У второго вкуса плеер розовый: свой цвет акцента и розовая дымка поверх размытой обложки
        boolean second = !e.store.profile.isEmpty();
        Bitmap art = e.shownArt();
        String id = e.shownId() == null ? "" : e.shownId();
        // Обложку, фон и цвет «Волна» берёт сама в лучшем качестве (Thumbs.large): картинка Aura мелкая и приходит поздно.
        // Она нужна, только если своей так и не нашлось
        boolean changed = !id.equals(shownFor);
        if (changed || second != shownSecond || (big == null && art != shownArt)) {
            shownArt = art;
            shownSecond = second;
            shownFor = id;
            if (changed) showArt(id);
            else paintArt(false);
            loadLyrics();
            if (changed && tab == 1) fillLibrary();
        }
        Track nxt = e.driving ? e.brain.peek(e.mode) : null;
        if (nxt != null) Thumbs.prefetch(this, nxt.id, nxt.thumb);
        modeLabel.setTextColor(Ui.light(accent));
        set(modeLabel, e.driving ? Brain.MODE_NAMES[e.mode].toUpperCase() : "AURA MUSIC");
        String b = e.shownBadge();
        set(badge, b.isEmpty() ? (e.playing() ? "Играет" : "На паузе") : b.replace("Для видосов", "Для видео"));
        set(title, e.shownId() == null ? "Сейчас начнётся" : e.shownTitle());
        set(artist, !e.status.isEmpty() ? e.status : e.shownArtist());
        Track nx = e.driving ? e.brain.peek(e.mode) : null;
        set(upNext, nx == null ? "" : "Далее: " + nx.title + " · " + nx.artist);
        play.setGlyph(e.playing() ? Icon.PAUSE : Icon.PLAY);
        boolean liked = e.liked();
        like.setGlyph(liked ? Icon.HEART_FILL : Icon.HEART);
        like.setTint(liked ? 0xFFFF6B81 : 0xF2FFFFFF);
        myPill.setOn(e.driving && e.mode == Brain.MY);
        newPill.setOn(e.driving && e.mode == Brain.NEW);
        set(tastePill, "Вкус: " + e.profileName(e.store.profile));
        tastePill.setOn(!e.store.profile.isEmpty());
        tastePill.setTint(e.store.profile.isEmpty() ? 0 : SECOND_ACCENT);
        boolean pin = TrackToast.always(e);
        set(toastPill, pin ? "Плашка: всегда" : "Плашка: при смене");
        toastPill.setOn(pin);
        set(tDur, e.shownDur() > 0 ? Ui.time(e.shownDur()) : "");
        fillQueue();
        tick.run();
        if (hintR != null) updateHints(getCurrentFocus());   // «Пауза» и «Играть», «Нравится» и «Убрать лайк» меняются сами
    }

    Thumbs.Art big;          // большая обложка показанного трека
    static final java.util.concurrent.ExecutorService PAINT = java.util.concurrent.Executors.newSingleThreadExecutor();
    int bgSeq;

    void showArt(String id) {
        big = null;
        if (id.isEmpty()) { paintArt(true); return; }
        Thumbs.Art ready = Thumbs.ready(id);
        if (ready != null) { big = ready; paintArt(true); return; }
        Thumbs.large(this, id, e.shownThumb(), a -> {
            if (!id.equals(shownFor) || isFinishing()) return;
            big = a;
            paintArt(true);
        });
    }

    // Обложка и фон меняются наплывом, цвет фона перетекает в цвет новой обложки. Размытие и цвет уже посчитаны в фоне
    void paintArt(boolean fade) {
        boolean second = !e.store.profile.isEmpty();
        Bitmap coverBm = big != null ? big.cover : shownArt;
        Bitmap blurBm = big != null ? big.blur : null;
        int artColor = big != null ? big.accent : shownArt != null ? Ui.accent(shownArt) : Ui.MINT;
        if (coverBm == null) {
            cover.setTag(null);
            Ui.setCover(cover, null);
        } else {
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            crossfade(cover, coverBm, fade);
        }
        accent = second ? SECOND_ACCENT : artColor;
        final int tintC = second ? 0x4DE58FB5 : (accent & 0x00FFFFFF) | 0x33000000;
        final int deepC = deep(second ? SECOND_ACCENT : artColor);
        final int seq = ++bgSeq;
        PAINT.execute(() -> {
            Bitmap composed = compose(blurBm, deepC, tintC);
            h.post(() -> { if (seq == bgSeq && !isFinishing()) crossfade(bg, composed, fade); });
        });
        queueSig = "";
        fillQueue();
    }

    // Фон целиком в маленькой картинке: глубокий цвет обложки, сама обложка в размытии, её оттенок и затемнение к краям под текст
    static Bitmap compose(Bitmap blur, int deepC, int tintC) {
        Bitmap out = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(deepC);
        if (blur != null) {
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            int w = blur.getWidth(), hh = blur.getHeight();
            // Как CENTER_CROP с запасом 10 %: края размытия не видны
            float k = Math.max(240f / w, 135f / hh) * 1.1f;
            float dw = w * k, dh = hh * k;
            c.drawBitmap(blur, null, new android.graphics.RectF((240 - dw) / 2, (135 - dh) / 2, (240 + dw) / 2, (135 + dh) / 2), p);
        }
        c.drawColor(tintC);
        android.graphics.Paint g = new android.graphics.Paint();
        g.setShader(new android.graphics.LinearGradient(0, 0, 240, 0, new int[]{0xA6000000, 0x8C000000, 0xB3000000}, null,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 240, 135, g);
        return out;
    }

    // Глубокий тёмный цвет обложки для самого дальнего слоя фона
    static int deep(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = 0.16f;
        hsv[1] = Math.min(0.85f, hsv[1]);
        return android.graphics.Color.HSVToColor(hsv);
    }

    void crossfade(ImageView iv, Bitmap b, boolean fade) {
        android.graphics.drawable.Drawable old = iv.getDrawable();
        if (b == null) { iv.setImageDrawable(null); return; }
        android.graphics.drawable.Drawable nu = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        if (old instanceof android.graphics.drawable.TransitionDrawable)
            old = ((android.graphics.drawable.TransitionDrawable) old).getDrawable(1);
        if (!fade || !(old instanceof android.graphics.drawable.BitmapDrawable)) {
            iv.setImageDrawable(nu);
            if (fade) { iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(M.t(320)).setInterpolator(EASE).start(); }
            return;
        }
        android.graphics.drawable.TransitionDrawable td = new android.graphics.drawable.TransitionDrawable(
                new android.graphics.drawable.Drawable[]{old, nu});
        td.setCrossFadeEnabled(true);
        iv.setImageDrawable(td);
        td.startTransition((int) M.t(360));
    }

    static void set(android.widget.TextView v, String s) { if (!s.contentEquals(v.getText())) v.setText(s); }

    void loadLyrics() {
        final String id = e.curId == null ? "" : e.curId;
        if (id.equals(lyricsFor)) return;
        lyricsFor = id;
        lyrics = null;
        lyricsDone = false;
        lyricIdx = -2;
        showLyrics();
        if (id.isEmpty()) return;
        final String t = e.curTitle, a = e.curArtist;
        final long d = e.curDur;
        new Thread(() -> {
            Lyrics l = Lyrics.fetch(t, a, d);
            h.post(() -> {
                if (!id.equals(lyricsFor)) return;
                lyrics = l;
                lyricsDone = true;
                showLyrics();
                // Сервер текстов не ответил: ещё одна попытка через 20 секунд, пока играет тот же трек
                if (l == null) h.postDelayed(() -> { if (id.equals(lyricsFor) && lyrics == null) { lyricsFor = ""; loadLyrics(); } }, 20_000);
            });
        }, "lyrics").start();
    }

    void showLyrics() {
        lyricsBox.removeAllViews();
        lyricViews.clear();
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND) {
            String msg = lyricsFor.isEmpty() ? "" : !lyricsDone ? "Ищу текст песни…"
                    : lyrics == Lyrics.NOT_FOUND ? "Текста этой песни в базе нет" : "Текст не загрузился, нет связи";
            TextView t = Fonts.text(this, Fonts.BODY, Fonts.reg18, Fonts.WHITE_60);
            t.setText(msg);
            lyricsBox.addView(t);
            lyricViews.add(t);
            return;
        }
        for (Lyrics.Line l : lyrics.lines) {
            TextView t = Fonts.text(this, Fonts.LEAD, Fonts.med24, Fonts.WHITE);
            t.setText(l.s);
            t.setLineHeight(px(28));
            t.setAlpha(lyrics.synced ? 0.4f : 0.85f);
            t.setPadding(0, px(6), 0, px(6));
            Typo.balanced(t);
            lyricsBox.addView(t);
            lyricViews.add(t);
        }
        lyricsScroll.scrollTo(0, 0);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            // Пока Aura ищет новый трек, полоса уже с начала, а не на месте прошлого
            long pos = e.pending() || e.waiting() ? 0 : e.position();
            bar.set(e.shownDur() > 0 ? pos / (float) e.shownDur() : 0, accent);
            set(tPos, Ui.time(pos));
            if (lyrics != null && lyrics != Lyrics.NOT_FOUND && lyrics.synced) {
                int idx = lyrics.indexAt(pos);
                if (idx != lyricIdx && idx < lyricViews.size()) {
                    // Размер строк не меняется: только яркость, поэтому соседние строки не прыгают
                    if (lyricIdx >= 0 && lyricIdx < lyricViews.size())
                        lyricViews.get(lyricIdx).animate().alpha(0.4f).setDuration(M.t(350)).start();
                    lyricIdx = idx;
                    if (idx >= 0) {
                        TextView cur = lyricViews.get(idx);
                        cur.animate().alpha(1f).setDuration(M.t(350)).start();
                        lyricsScroll.smoothScrollTo(0, Math.max(0, cur.getTop() - lyricsScroll.getHeight() / 3));
                    }
                }
            }
            h.postDelayed(this, 250);
        }
    };

    @Override public void onEngine(boolean newTrack) { refresh(newTrack); }

    // Плитку нажали снова, когда плеер уже был открыт раньше: музыка тоже должна включиться
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (!e.driving) e.start(e.mode);
        else if (!e.playing()) e.toggle();
    }

    @Override protected void onResume() {
        super.onResume();
        Engine.uiVisible++;
        TrackToast.sync(this, e);   // свой экран открыт: плашка о треке не нужна
        e.addListener(this);
        refresh(true);
    }

    @Override protected void onPause() {
        Engine.uiVisible--;
        Engine.get(this).uiHidden();
        e.removeListener(this);
        h.removeCallbacksAndMessages(null);
        super.onPause();
    }

    // «Назад» из редких настроек возвращает к режимам, а не закрывает плеер
    @Override public void onBackPressed() {
        if (extrasRow.getVisibility() == View.VISIBLE) showExtras(false);
        else super.onBackPressed();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        View f = getCurrentFocus();
        int code = ev.getKeyCode();
        // «Назад» в списке и в тексте не закрывает плеер, а поднимает к вкладке. Отпускание той же кнопки тоже наше
        if (code == KeyEvent.KEYCODE_BACK && (inList(f) || f == lyricsScroll)) {
            if (ev.getAction() == KeyEvent.ACTION_UP) tabPill().requestFocus();
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            boolean ex = extrasRow.getVisibility() == View.VISIBLE;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (f == bar) return true;                                          // выше только название
                    if (inTransport(f)) { bar.requestFocus(); return true; }             // над кнопками перемотка
                    if (inPills(f)) { above(f).requestFocus(); return true; }
                    if (inList(f)) {
                        if (((TrackList) f).getSelectedItemPosition() <= 0) tabPill().requestFocus();
                        else break;
                        return true;
                    }
                    if (f == lyricsScroll) {
                        if (lyricsScroll.getScrollY() <= 0) tabText.requestFocus();
                        else lyricsScroll.smoothScrollBy(0, -px(140));
                        return true;
                    }
                    if (isTab(f) || (f != null && f.getParent() == root)) return true;  // над вкладками и крестиком ничего
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (f == bar) { (lastTransport != null ? lastTransport : play).requestFocus(); return true; }
                    if (inTransport(f)) { below(f).requestFocus(); return true; }
                    if (inPills(f)) return true;                                         // ниже ничего нет
                    if (isTab(f)) {
                        int t = f == tabQueue ? 0 : f == tabLib ? 1 : 2;
                        if (t != tab) showTab(t);                                        // вкладка под фокусом, не прежняя
                        enterTop();
                        return true;
                    }
                    if (f != null && f.getParent() == root) { enterTop(); return true; } // крестик
                    if (inList(f)) {
                        TrackList l = (TrackList) f;
                        if (l.getSelectedItemPosition() >= l.getCount() - 1) return true;
                        break;
                    }
                    if (f == lyricsScroll) { lyricsScroll.smoothScrollBy(0, px(140)); return true; }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (inList(f) || f == tabQueue || f == lyricsScroll) { backToLeft(); return true; }
                    if (f == like || f == myPill || f == audioPill) return true;           // левее ничего нет
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (f == dislike || f == more || (ex && f == toastPill)) { enterAt(f); return true; }
                    if (inList(f) || f == lyricsScroll || (f != null && f.getParent() == root)) return true;
                    break;
            }
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    e.toggle();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    e.next();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    e.prev();
                    return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }
}
