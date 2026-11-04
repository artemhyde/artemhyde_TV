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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// Мини-плеер поверх любого приложения (кнопка Premier). Справа внизу по общей сетке (поля 56 и 40 dp):
// обложка и трек, полоса, ряд кнопок как в любом плеере, отдельно режимы и «Вкус», редкие настройки под «⋯».
// Фон под карточкой мягко темнеет, чтобы не было двух горящих фокусов
public class MenuActivity extends Activity implements Engine.Listener {
    static final long IDLE_CLOSE_MS = 12_000;

    static final int SECOND_ACCENT = 0xFFE58FB5;   // цвет второго вкуса
    Engine e;
    float dp;
    FrameLayout root;
    View scrim;
    LinearLayout panel, modesRow, extrasRow;
    ImageView cover;
    TextView badge, title, artist;
    Bar bar;
    Icon like, play, more;
    Pill myPill, newPill, tastePill, toastPill;
    int accent = Ui.MINT;
    Bitmap shownArt;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean visible, closing;
    Glass glass;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        e = Engine.get(this);
        Fonts.init(this);
        dp = getResources().getDisplayMetrics().density;
        e.connect();
        // Одно нажатие = музыка: если волна не идёт или стоит на паузе, включаем
        if (!e.driving) e.start(e.mode);
        else if (!e.playing()) e.toggle();

        root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(20), px(20), px(20), px(20));
        panel.setClipToPadding(false);
        panel.setClipChildren(false);
        panel.setClickable(true);
        // Тёмное стекло: плотное, чтобы сквозь него не читался чужой текст, со светлой кромкой и бликом
        glass = new Glass(px(24), dp, 0xF017171C);
        panel.setBackground(glass);
        panel.setElevation(px(16));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(448), -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = px(56);
        lp.bottomMargin = px(40);
        root.addView(panel, lp);
        setContentView(root);
        build();
        refresh(true);

        // Вход: фон темнеет, карточка поднимается и проявляется
        scrim.animate().alpha(0.35f).setDuration(M.t(300)).start();
        // Без масштаба: он заново растеризует весь текст карточки на каждом кадре
        panel.setAlpha(0f);
        panel.setTranslationY(px(28));
        panel.animate().alpha(1f).translationY(0).setDuration(M.t(420))
                .setInterpolator(PlayerActivity.SHEET).start();
    }

    int px(float v) { return Math.round(v * dp); }

    void build() {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF1A1A20);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * dp); }
        });
        head.addView(cover, new LinearLayout.LayoutParams(px(72), px(72)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(14), 0, px(8), 0);
        badge = Fonts.text(this, Fonts.CAPTION, Fonts.med18, accent);
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        title = Fonts.text(this, Fonts.LEAD, Fonts.med24, Fonts.WHITE);
        title.setMaxLines(2);
        title.setLineHeight(px(24));
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, px(6), 0, 0);
        artist = Fonts.text(this, Fonts.BODY, Fonts.reg18, Fonts.WHITE_72);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setPadding(0, px(4), 0, 0);
        col.addView(badge);
        col.addView(title);
        col.addView(artist);
        head.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        Icon expand = new Icon(this, Icon.EXPAND, false);
        expand.setOnClickListener(v -> { startActivity(new Intent(this, PlayerActivity.class)); finish(); overridePendingTransition(0, 0); });
        head.addView(expand, new LinearLayout.LayoutParams(px(36), px(36)));
        // Крестик: выключить музыку и убрать карточку одним нажатием
        Icon x = new Icon(this, Icon.CLOSE, false);
        x.setOnClickListener(v -> { e.stop(); close(); });
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(px(36), px(36));
        xl.leftMargin = px(6);
        head.addView(x, xl);
        panel.addView(head);

        bar = new Bar(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, px(10));
        blp.topMargin = px(12);
        panel.addView(bar, blp);

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER);
        transport.setClipChildren(false);
        like = icon(transport, Icon.HEART, 40, false, v -> { e.like(); refresh(false); });
        icon(transport, Icon.PREV, 44, false, v -> e.prev());
        icon(transport, Icon.REW, 44, false, v -> e.seekBy(-10_000));
        play = icon(transport, Icon.PAUSE, 56, true, v -> e.toggle());
        icon(transport, Icon.FWD, 44, false, v -> e.seekBy(10_000));
        icon(transport, Icon.NEXT, 44, false, v -> e.next());
        Icon dis = icon(transport, Icon.DISLIKE, 40, false, v -> e.dislike());
        ((LinearLayout.LayoutParams) dis.getLayoutParams()).leftMargin = px(20);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, px(60));
        tl.topMargin = px(6);
        panel.addView(transport, tl);

        FrameLayout modes = new FrameLayout(this);
        modes.setClipChildren(false);
        modesRow = new LinearLayout(this);
        modesRow.setClipChildren(false);
        myPill = pill(modesRow, "Моя волна", v -> switchMode(Brain.MY));
        newPill = pill(modesRow, "Новое для вас", v -> switchMode(Brain.NEW));
        tastePill = pill(modesRow, "", v -> switchTaste());
        ((LinearLayout.LayoutParams) tastePill.getLayoutParams()).leftMargin = px(14);
        more = new Icon(this, Icon.MORE, false);
        more.setOnClickListener(v -> showExtras(true));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(px(36), px(36));
        ml.leftMargin = px(6);
        modesRow.addView(more, ml);
        modes.addView(modesRow, new FrameLayout.LayoutParams(-1, px(36)));
        extrasRow = new LinearLayout(this);
        extrasRow.setClipChildren(false);
        pill(extrasRow, "Только аудио", v -> { startActivity(new Intent(this, BlackoutActivity.class)); close(); });
        pill(extrasRow, "Наушники", v -> openBluetooth());
        toastPill = pill(extrasRow, "", v -> { TrackToast.setAlways(e, !TrackToast.always(e)); refresh(false); });
        extrasRow.setVisibility(View.GONE);
        modes.addView(extrasRow, new FrameLayout.LayoutParams(-1, px(36)));
        LinearLayout.LayoutParams mrl = new LinearLayout.LayoutParams(-1, px(36));
        mrl.topMargin = px(8);
        panel.addView(modes, mrl);

        // requestFocusFromTouch: курсор встаёт на место, даже если ТВ остался в режиме касаний
        play.post(() -> play.requestFocusFromTouch());
    }

    Icon icon(LinearLayout row, int glyph, int sizeDp, boolean primary, View.OnClickListener l) {
        Icon i = new Icon(this, glyph, primary);
        i.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp));
        if (row.getChildCount() > 0) lp.leftMargin = px(10);
        row.addView(i, lp);
        return i;
    }

    Pill pill(LinearLayout row, String text, View.OnClickListener l) {
        Pill p = new Pill(this, text, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(36));
        if (row.getChildCount() > 0) lp.leftMargin = px(6);
        row.addView(p, lp);
        return p;
    }

    void showExtras(boolean on) {
        View out = on ? modesRow : extrasRow, in = on ? extrasRow : modesRow;
        out.animate().alpha(0f).setDuration(M.t(160)).withEndAction(() -> out.setVisibility(View.GONE)).start();
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(M.t(220)).start();
        (on ? extrasRow.getChildAt(0) : more).requestFocus();
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
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                close();
                return;
            } catch (Exception ignored) { }
        }
    }

    void switchMode(int m) {
        if (m != e.mode || !e.driving) e.start(m);
        refresh(false);
    }

    // Смена вкуса как смена страницы: содержимое карточки тает и проявляется уже новым
    void switchTaste() {
        panel.animate().cancel();
        panel.animate().alpha(0.2f).setDuration(M.t(120)).withEndAction(() -> {
            e.setProfile(e.store.profile.isEmpty() ? Store.SECOND : Store.MAIN);
            refresh(true);
            panel.animate().alpha(1f).setDuration(M.t(240)).setInterpolator(PlayerActivity.EASE).start();
        }).start();
    }

    boolean shownSecond;
    String shownFor = "";

    void refresh(boolean newTrack) {
        boolean second = !e.store.profile.isEmpty();
        Bitmap art = e.shownArt();
        String id = e.shownId() == null ? "" : e.shownId();
        // Чёткая обложка своя (Thumbs.large), картинка Aura только пока своей нет
        if (newTrack || art != shownArt || second != shownSecond || !id.equals(shownFor)) {
            boolean changed = !id.equals(shownFor);
            shownArt = art;
            shownSecond = second;
            shownFor = id;
            Thumbs.Art big = id.isEmpty() ? null : Thumbs.ready(id);
            if (big != null) {
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setImageBitmap(big.cover);
            } else {
                cover.setTag(null);
                Ui.setCover(cover, art);
                if (changed && !id.isEmpty()) Thumbs.large(this, id, a -> { if (a != null && id.equals(shownFor)) refresh(false); });
            }
            accent = second ? SECOND_ACCENT : big != null ? big.accent : Ui.accent(art);
            // У второго вкуса и карточка с розовым отливом
            glass.setFill(second ? 0xF02A1A24 : 0xF017171C);
        }
        badge.setTextColor(Ui.light(accent));
        String b = e.shownBadge();
        badge.setText(b.isEmpty() ? (e.playing() ? "Сейчас играет" : "На паузе") : b.replace("Для видосов", "Для видео"));
        title.setText(e.shownId() == null ? "Сейчас начнётся" : e.shownTitle());
        artist.setText(!e.status.isEmpty() ? e.status : e.shownArtist());
        play.setGlyph(e.playing() ? Icon.PAUSE : Icon.PLAY);
        boolean liked = e.liked();
        like.setGlyph(liked ? Icon.HEART_FILL : Icon.HEART);
        like.setTint(liked ? 0xFFFF6B81 : 0xF2FFFFFF);
        myPill.setOn(e.mode == Brain.MY && e.driving);
        newPill.setOn(e.mode == Brain.NEW && e.driving);
        tastePill.setText("Вкус: " + e.profileName(e.store.profile));
        tastePill.setOn(!e.store.profile.isEmpty());
        tastePill.setTint(e.store.profile.isEmpty() ? 0 : SECOND_ACCENT);
        boolean pin = TrackToast.always(e);
        toastPill.setText(pin ? "Плашка: всегда" : "Плашка: при смене");
        toastPill.setOn(pin);
        bar.set(e.curDur > 0 ? e.position() / (float) e.curDur : 0, accent);
    }

    @Override public void onEngine(boolean newTrack) { refresh(newTrack); }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            bar.set(e.pending() || e.waiting() || e.curDur <= 0 ? 0 : e.position() / (float) e.curDur, accent);
            h.postDelayed(this, 500);
        }
    };

    final Runnable autoClose = this::close;

    void poke() {
        h.removeCallbacks(autoClose);
        h.postDelayed(autoClose, IDLE_CLOSE_MS);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        Engine.uiVisible++;
        TrackToast.sync(this, e);   // свой экран открыт: плашка о треке не нужна
        e.addListener(this);
        refresh(true);
        h.post(tick);
        poke();
    }

    @Override protected void onPause() {
        visible = false;
        Engine.uiVisible--;
        Engine.get(this).uiHidden();
        e.removeListener(this);
        h.removeCallbacksAndMessages(null);
        super.onPause();
    }

    // Повторное долгое нажатие, пока карточка открыта, прячет её
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (visible) { close(); return; }
        // Карточка жила свёрнутой: при новом открытии тоже включаем музыку
        if (!e.driving) e.start(e.mode);
        else if (!e.playing()) e.toggle();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        poke();
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            switch (ev.getKeyCode()) {
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

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        poke();
        return super.dispatchTouchEvent(ev);
    }

    @Override public void onBackPressed() {
        if (extrasRow.getVisibility() == View.VISIBLE) showExtras(false);
        else close();
    }

    // Уход: карточка опускается и тает, фон светлеет, потом окно закрывается
    void close() {
        if (closing || isFinishing()) return;
        closing = true;
        scrim.animate().alpha(0f).setDuration(M.t(220)).start();
        panel.animate().alpha(0f).translationY(px(16)).setDuration(M.t(220)).setInterpolator(PlayerActivity.EASE)
                .withEndAction(() -> { finish(); overridePendingTransition(0, 0); }).start();
    }
}
