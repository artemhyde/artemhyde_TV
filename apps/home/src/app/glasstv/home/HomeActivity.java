package app.glasstv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Главный экран «Дом» в духе Apple TV. Три состояния:
// простой («блокировка»): только фото, часы, погода и стих;
// активный (первое нажатие): фон размывается, стих уходит вверх, снизу каскадом встают плитки и две стеклянные капсулы;
// «Все приложения»: отдельный слой поверх, открывается плиткой в ряду, «Назад» возвращает. Минута без нажатий: снова простой
public class HomeActivity extends Activity {
    static final String[] FAVORITES = {
            "app.glasstv.tile.browser", "app.glasstv.tile.vk", "app.glasstv.tile.youtube", "app.glasstv.tile.lampa", "app.glasstv.tile.serials",
            "app.glasstv.tile.files", "app.glasstv.tile.twitch", "app.glasstv.volna", "app.glasstv.nightmode",
            "all", "app.glasstv.tile.settings"};
    // Не показывать: приложения, которые открывают плитки избранного, служебные и старый лаунчер.
    // Key Mapper виден: он чинит кнопки пульта
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.phlox.tvwebbrowser.foss", "com.vk.tv", "org.smarttube.stable", "top.rootu.lampa", "com.cxinventor.file.explorer",
            "com.fgl27.twitch", "app.glasstv.home", "app.glasstv.clip", "app.glasstv.probe", "com.spocky.projengmenu",
            "com.brouken.player", "com.google.android.youtube.tv", "com.neilturner.aerialviews", "com.android.tv.settings",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx"));
    static final long IDLE_MS = 60_000;
    static final int PAD = 26;   // запас вокруг ряда: рост плитки, свечение и тень не обрезаются
    static final int IDLE = 0, ACTIVE = 1, GRID = 2;
    static final int GRID_TOP = 96;   // верх первой строки «Всех приложений»

    Ambient amb;
    FrameLayout root, gridLayer;
    LinearLayout shelf, dock, gridBox, suggestRow;
    HorizontalScrollView rowScroll;
    Suggest cont, play;
    Tile allTile;
    final List<Tile> dockTiles = new ArrayList<>();
    final Map<String, Drawable> icons = new HashMap<>();
    final Handler h = new Handler(Looper.getMainLooper());
    int state = IDLE;
    float dp;
    String appsSig;
    View lastDock;
    final View[] gridHead = new View[2];
    long pausedAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Fonts.init(this);
        amb = new Ambient(this, false);
        root = amb.root;

        // Прозрачность ряда применяется к каждой плитке напрямую, без отдельного слоя во весь экран
        shelf = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        sl.topMargin = px(Grid.ROW_Y - PAD);
        root.addView(shelf, sl);

        // Ряд плиток без затухания по краю: затухание перерисовывает весь ряд через лишний слой на каждом кадре
        // Прокруткой ряда управляет только scrollDock (плавный доезд). Стандартный ряд сам мгновенно прыгал, чтобы
        // показать плитку в фокусе целиком, а потом добегал коротко (0,25 с): листание выглядело резким (27.10)
        // Свои пути прокрутки у ряда отключены совсем: стрелки только переносят фокус, остальное делает scrollDock.
        // Иначе штатная прокрутка писала scrollX параллельно с нашим доездом, а при отставании ряда сам ряд
        // забирал фокус, и следующее нажатие прыгало на несколько плиток назад (найдено проверкой 27.10)
        rowScroll = new HorizontalScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
            @Override public boolean arrowScroll(int d) {
                View cur = findFocus();
                if (cur == this) cur = null;
                View next = android.view.FocusFinder.getInstance().findNextFocus(this, cur, d);
                return next != null && next.requestFocus(d);
            }
            @Override public boolean pageScroll(int d) { return false; }
            @Override public boolean fullScroll(int d) { return false; }
        };
        rowScroll.setFocusable(false);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowScroll.setHorizontalFadingEdgeEnabled(false);   // затухание края рисуется слоем по рамке ряда и срезало бы свечение
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(px(Grid.MARGIN), px(PAD), px(Grid.MARGIN), px(PAD));
        rowScroll.addView(dock);
        shelf.addView(rowScroll, new LinearLayout.LayoutParams(-1, px(Grid.TILE_H + 2 * PAD)));

        // Две маленькие стеклянные капсулы под рядом
        suggestRow = new LinearLayout(this);
        suggestRow.setOrientation(LinearLayout.HORIZONTAL);
        suggestRow.setClipChildren(false);
        suggestRow.setClipToPadding(false);   // капсула в фокусе подрастает за левое поле 56 dp: не срезать
        suggestRow.setPadding(px(Grid.MARGIN), 0, px(Grid.MARGIN), 0);
        cont = new Suggest(this);
        play = new Suggest(this);
        cont.wide(460);   // в подписи ещё и «осталось N мин»
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -1);
        // 42 точки, как в макете «Графит»: капсула в фокусе растёт и не упирается в соседку. Зазор у «Продолжить»:
        // когда её нет, «Играет» встаёт на левое поле без пустого места
        cl.rightMargin = px(21);
        suggestRow.addView(cont, cl);
        suggestRow.addView(play, new LinearLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, px(44));
        srl.topMargin = px(28 - PAD);
        shelf.addView(suggestRow, srl);

        buildGridLayer();
        setContentView(root);
        // Фон окна под корнем не виден (корень сам тёмный, сверху фото), а видеоядро заливало его каждый кадр:
        // с частицами экран перерисовывается целиком, и эта лишняя заливка 1080p стоила около миллисекунды (27.10)
        getWindow().setBackgroundDrawable(null);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> onFocus(n));
        amb.onNow = this::updateSuggest;
        hideShelf(false);
    }

    int px(float v) { return Math.round(v * dp); }

    // «Все приложения»: отдельный слой поверх фона, со своим заголовком и подсказкой, как выйти
    void buildGridLayer() {
        gridLayer = new FrameLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        gridLayer.setVisibility(View.GONE);
        gridLayer.setClipChildren(false);
        TextView title = Fonts.text(this, Fonts.LEAD, Fonts.med24, Fonts.WHITE);
        title.setText("Все приложения");
        gridHead[0] = title;
        gridLayer.addView(title, Grid.at(title, dp, Grid.MARGIN, 64, false, 0));
        TextView back = Fonts.text(this, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        back.setText("«Назад»: на главный экран");
        gridHead[1] = back;
        gridLayer.addView(back, Grid.at(back, dp, Grid.RIGHT, 64, true, 0));
        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setClipChildren(false);
        gridBox.setClipToPadding(false);
        gridBox.setPadding(px(Grid.MARGIN), px(PAD), px(Grid.MARGIN), px(80));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        gl.topMargin = px(GRID_TOP - PAD);
        gridLayer.addView(gridBox, gl);
        root.addView(gridLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    // ===== Приложения =====

    void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = r.activityInfo.packageName;
                if (!apps.containsKey(p)) apps.put(p, r);
            }
        }
        // Набор и версии приложений не менялись: плитки не пересобираем, иначе сбивается фокус.
        // Путь к файлу приложения меняется при каждом обновлении, по нему видно новую картинку плитки
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo r : apps.values()) sb.append(r.activityInfo.applicationInfo.sourceDir).append(';');
        String sig = sb.toString();
        if (sig.equals(appsSig)) return;
        appsSig = sig;
        dock.removeAllViews();
        dockTiles.clear();
        for (String p : FAVORITES) {
            Tile t;
            if (p.equals("all")) t = allTile = allAppsTile();
            else {
                ResolveInfo r = apps.get(p);
                if (r == null) continue;
                t = tile(r, true);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(Grid.TILE_W), px(Grid.TILE_H));
            if (dock.getChildCount() > 0) lp.leftMargin = px(Grid.GAP);
            dock.addView(t, lp);
            dockTiles.add(t);
        }
        // Остальные приложения сеткой по 4 в ряд, по алфавиту
        List<ResolveInfo> rest = new ArrayList<>();
        Set<String> fav = new HashSet<>(Arrays.asList(FAVORITES));
        for (ResolveInfo r : apps.values()) {
            String p = r.activityInfo.packageName;
            icons.put(p, r.loadIcon(pm));
            if (!fav.contains(p) && !HIDDEN.contains(p)) rest.add(r);
        }
        rest.sort((a, c) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(c.loadLabel(pm))));
        List<View> all = new ArrayList<>();
        // В сетке все приложения одинаково: значок и подпись на тёмной плитке, без чужих баннеров
        for (ResolveInfo r : rest) all.add(tile(r, false));
        if (!apps.containsKey("app.glasstv.tile.settings")) all.add(settingsTile());
        gridBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) rl.topMargin = px(Grid.GAP);
                gridBox.addView(row, rl);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(Grid.TILE_W), px(Grid.TILE_H));
            if (row.getChildCount() > 0) lp.leftMargin = px(Grid.GAP);
            row.addView(all.get(i), lp);
        }
        lastDock = dockTiles.isEmpty() ? null : dockTiles.get(0);
        if (lastDock != null && state != GRID) lastDock.post(() -> lastDock.requestFocus());
        // Новые плитки в простом состоянии не видны, их выведет первое нажатие
        if (state == IDLE) hideShelf(false);
    }

    // Плитка приложения. В ряду значок и подпись из готовых картинок (assets/tiles), в сетке и для
    // приложений без картинки значок с подписью рисуются сами: новое приложение сразу выглядит как остальные
    Tile tile(ResolveInfo r, boolean dock) {
        PackageManager pm = getPackageManager();
        ActivityInfo a = r.activityInfo;
        String label = String.valueOf(r.loadLabel(pm));
        Bitmap content = dock ? Tile.asset(this, a.packageName) : null;
        boolean art = content != null;
        if (content == null) content = Tile.app(this, r.loadIcon(pm), label, px(Grid.TILE_W), px(Grid.TILE_H));
        Tile t = new Tile(this, a.packageName, label, content, 1.08f);
        if (art) t.row();
        t.setOnClickListener(v -> launch(a.packageName, v));
        return t;
    }

    // Плитка «Все приложения» в ряду: вход в сетку виден сразу
    Tile allAppsTile() {
        Bitmap content = Tile.asset(this, "all");
        boolean art = content != null;
        if (content == null) content = Tile.app(this, null, "Все приложения", px(Grid.TILE_W), px(Grid.TILE_H));
        Tile t = new Tile(this, "all", "Все приложения", content, 1.08f);
        if (art) t.row();
        t.setOnClickListener(v -> openGrid());
        return t;
    }

    View settingsTile() {
        Drawable gear = getDrawable(android.R.drawable.ic_menu_preferences);
        Tile t = new Tile(this, "settings", "Настройки", Tile.app(this, gear, "Настройки", px(Grid.TILE_W), px(Grid.TILE_H)), 1.08f);
        t.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        });
        return t;
    }

    void launch(String pkg) { launch(pkg, null); }

    // Приложение «вырастает» из плитки, на которой нажали. Если ТВ гас после прошлого использования приложения,
    // оно открывается с чистого листа, на своём главном экране (Go.fresh); исключения и подробности там же
    void launch(String pkg, View from) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        Bundle opts = from == null ? null
                : android.app.ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        Intent fresh = Go.fresh(this, pkg, new Intent(i));
        if (fresh != null) {
            try { startActivity(fresh, opts); return; } catch (Exception ignored) { }
        }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts); } catch (Exception ignored) { }
    }

    // Поверх открылась карточка «Волны»: рамка плитки под ней не должна гореть вторым фокусом
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        for (Tile t : dockTiles) t.setQuiet(!has);
    }

    // ===== Капсулы: что продолжить и что играет =====

    void updateSuggest() {
        // «Продолжить» только для настоящего просмотра (Last, Watch); нечего продолжать или приложения уже нет: капсулы нет
        Last.Rec last = Last.pick(this);
        String app = last == null ? null : Last.K_LAMPA.equals(last.kind) ? Last.LAMPA : last.pkg;
        if (app != null && !installed(app)) last = null;
        showCont(last != null);
        if (last != null) {
            final Last.Rec rec = last;
            String[] txt = Last.caption(rec);
            cont.set("Продолжить", txt[0], txt[1], cap(app));
            // По OK запись берётся заново: последнюю паузу плеер пишет уже после того, как «Дом» показался
            cont.setOnClickListener(v -> {
                Last.Rec now = Last.pick(this);
                Go.resume(this, now != null && now.pkg.equals(rec.pkg) ? now : rec, v);
            });
        }
        Drawable volna = cap("app.glasstv.volna");
        if (!Now.title.isEmpty()) {
            // OK открывает плеер «Волны» сразу на весь экран
            play.set(Now.playing ? "Играет" : "На паузе", Now.title + (Now.artist.isEmpty() ? "" : " · " + Now.artist), volna);
            play.setOnClickListener(v -> openVolnaPlayer());
        } else {
            play.set("Включить", "Моя волна", volna);
            play.setOnClickListener(v -> {
                try { sendBroadcast(new Intent().setClassName("app.glasstv.volna", "app.glasstv.volna.Cmd").putExtra("do", "my")); }
                catch (Exception ignored) { }
            });
        }
    }

    // Значок капсулы в нашем стиле (assets/caps, собирает Home/tiles-src/build_caps.py): хлопушка для Фильмов,
    // красный YouTube и т. д. Родные значки приложений здесь не годятся (у Lampa белая спираль)
    static final Map<String, String> CAPS = new HashMap<>();
    static {
        CAPS.put("top.rootu.lampa", "lampa");
        CAPS.put("org.smarttube.stable", "youtube");
        CAPS.put("com.vk.tv", "vk");
        CAPS.put("com.fgl27.twitch", "twitch");
        CAPS.put("com.brouken.player", "files");
        CAPS.put("app.glasstv.volna", "volna");
    }
    final Map<String, Drawable> capCache = new HashMap<>();

    Drawable cap(String pkg) {
        String name = CAPS.get(pkg);
        if (name == null) return icons.get(pkg);
        Drawable d = capCache.get(name);
        if (d == null) {
            android.graphics.Bitmap b = null;
            try (java.io.InputStream in = getAssets().open("caps/" + name + ".png")) { b = android.graphics.BitmapFactory.decodeStream(in); }
            catch (Exception ignored) { }
            if (b == null) return icons.get(pkg);
            d = new android.graphics.drawable.BitmapDrawable(getResources(), b);
            capCache.put(name, d);
        }
        return d;
    }

    boolean installed(String pkg) {
        PackageManager pm = getPackageManager();
        return pm.getLaunchIntentForPackage(pkg) != null || pm.getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    // Капсула «Продолжить» появляется и исчезает вместе с тем, что можно продолжить, а соседняя встаёт на её место.
    // Скрытая не ловит фокус и не входит в каскад (shelfItems). Появившаяся вне каскада берёт прозрачность по состоянию
    // экрана: в простое прозрачная, в активном проявляется, в «Всех приложениях» ряд и так погашен целиком
    void showCont(boolean on) {
        if (on == (cont.getVisibility() == View.VISIBLE)) return;
        cont.animate().cancel();
        if (!on) {
            if (cont.isFocused()) {
                View back = lastDock != null ? lastDock : (dockTiles.isEmpty() ? null : dockTiles.get(0));
                if (back != null) back.requestFocus();
            }
            cont.setVisibility(View.GONE);
            return;
        }
        cont.setVisibility(View.VISIBLE);
        cont.setAlpha(state == GRID ? 1f : 0f);
        cont.setTranslationY(state == IDLE ? px(16) : 0);
        if (state == ACTIVE) cont.animate().alpha(1f).setStartDelay(0).setDuration(M.t(300)).setInterpolator(Ambient.EASE).start();
        cont.fade(getCurrentFocus() instanceof Suggest ? 0.62f : 0.45f);
    }

    void openVolnaPlayer() {
        try {
            startActivity(new Intent().setClassName("app.glasstv.volna", "app.glasstv.volna.PlayerActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    // ===== Фокус: прокрутка ряда и сетки =====

    void onFocus(View v) {
        if (v == null) return;
        // Пока фокус на плитках, капсулы приглушены; на капсуле она проявляется, соседняя чуть тише
        // Скрытой капсуле «Продолжить» затухание ни к чему: её проявит showCont по текущему фокусу
        boolean hasCont = cont.getVisibility() == View.VISIBLE;
        if (v instanceof Suggest) {
            if (hasCont) cont.fade(v == cont ? 1f : 0.62f);
            play.fade(v == play ? 1f : 0.62f);
        } else if (v instanceof Tile && dockTiles.contains(v)) {
            if (hasCont) cont.fade(0.45f);
            play.fade(0.45f);
        }
        if (v instanceof Tile && dockTiles.contains(v)) {
            lastDock = v;
            scrollDock((Tile) v);
        } else if (v instanceof Tile && state == GRID) {
            // Сетка едет, только когда строка в фокусе не помещается над нижним полем.
            // Уехала: заголовок тает, чтобы плитки не наезжали на текст
            View row = (View) v.getParent();
            int rowIdx = Math.max(0, gridBox.indexOfChild(row));
            int over = GRID_TOP + rowIdx * (Grid.TILE_H + Grid.GAP) + Grid.TILE_H - Grid.BOTTOM;
            float target = -Math.max(0, over) * dp;
            if (gridBox.getTranslationY() != target)
                gridBox.animate().translationY(target).setStartDelay(0).setDuration(M.t(420)).setInterpolator(Ambient.EASE).start();
            for (View t : gridHead) t.animate().alpha(target == 0 ? 1f : 0f).setDuration(M.t(240)).start();
        }
    }

    // Ряд доезжает плавно, 0,5 с с мягким торможением. Новое нажатие во время доезда продолжает движение
    // с того места, где ряд сейчас, к новой цели: при быстром листании ряд течёт, а не дёргается
    static final long ROW_MS = 500;
    static final android.view.animation.PathInterpolator ROW_EASE = new android.view.animation.PathInterpolator(0.25f, 0.46f, 0.3f, 1f);
    android.animation.ValueAnimator rowAnim;
    int rowTarget;

    void scrollDock(Tile t) {
        int x = t.getLeft(), sx = rowScroll.getScrollX();
        int maxLeft = px(Grid.col(6)), minLeft = px(Grid.MARGIN);
        boolean running = rowAnim != null && rowAnim.isRunning();
        // Поводок: при удержании стрелки фокус бежит быстрее плавного доезда. Плитка в фокусе не должна уходить
        // за край: если ряд отстал, он сразу подтягивается до края, а дальше доезжает плавно уже оттуда
        int lo = x + t.getWidth() - rowScroll.getWidth(), hi = x;
        boolean snapped = false;
        if (sx < lo || sx > hi) {
            if (rowAnim != null) rowAnim.cancel();
            rowScroll.scrollTo(Math.max(lo, Math.min(hi, sx)), 0);
            sx = rowScroll.getScrollX();
            snapped = true;
            running = false;
        }
        int base = running ? rowTarget : sx;   // считаем от цели текущего доезда, а не от середины пути
        int target = base;
        if (x - target > maxLeft) target = x - maxLeft;
        if (x - target < minLeft) target = x - minLeft;
        int max = Math.max(0, dock.getWidth() - rowScroll.getWidth());
        target = Math.max(0, Math.min(max, target));
        if (running && !snapped && target == rowTarget) return;   // цель та же: идущий доезд не перезапускать
        rowScrollTo(target);
    }

    void rowScrollTo(int to) {
        int from = rowScroll.getScrollX();
        rowTarget = to;
        if (rowAnim != null) rowAnim.cancel();
        if (to == from) return;
        rowAnim = android.animation.ValueAnimator.ofInt(from, to);
        rowAnim.setDuration(M.t(ROW_MS));
        rowAnim.setInterpolator(ROW_EASE);
        rowAnim.addUpdateListener(a -> rowScroll.scrollTo((int) a.getAnimatedValue(), 0));
        rowAnim.start();
    }

    // ===== Состояния =====

    // «Все приложения»: главный экран отступает, слой сетки поднимается и проявляется поверх.
    // Без масштаба и без слоёв во весь экран: видеоядро телевизора слабое, каждый лишний проход по экрану
    // стоит кадра. Масштаб к тому же заново растеризует весь текст на каждом кадре
    void openGrid() {
        if (state == GRID) return;
        state = GRID;
        amb.setGrid(true);
        shelf.animate().alpha(0f).translationY(-px(16)).setStartDelay(0).setDuration(M.t(220)).setInterpolator(Ambient.EASE).start();
        gridBox.setTranslationY(0);
        for (View t : gridHead) t.setAlpha(1f);
        gridLayer.setAlpha(0f);
        gridLayer.setTranslationY(px(20));
        gridLayer.setVisibility(View.VISIBLE);
        gridLayer.animate().alpha(1f).translationY(0).setStartDelay(M.t(60)).setDuration(M.t(380))
                .setInterpolator(Ambient.EASE).start();
        gridLayer.post(() -> {
            View first = gridBox.getChildCount() > 0 ? ((LinearLayout) gridBox.getChildAt(0)).getChildAt(0) : null;
            if (first != null) first.requestFocus();
            shelfFocusable(false);
        });
    }

    // Назад из сетки тем же путём: слой тает, главный экран возвращается
    void closeGrid() {
        if (state != GRID) return;
        state = ACTIVE;
        amb.setGrid(false);
        shelfFocusable(true);
        gridLayer.animate().alpha(0f).translationY(px(20)).setStartDelay(0).setDuration(M.t(240))
                .setInterpolator(Ambient.EASE).withEndAction(() -> gridLayer.setVisibility(View.GONE)).start();
        shelf.animate().alpha(1f).translationY(0).setStartDelay(M.t(60)).setDuration(M.t(360)).setInterpolator(Ambient.EASE).start();
        View back = allTile != null ? allTile : lastDock;
        if (back != null) back.requestFocus();
    }

    // Пока открыта сетка, невидимый ряд под ней не должен ловить фокус: иначе «вниз» уводит рамку в пустоту
    void shelfFocusable(boolean on) {
        shelf.setDescendantFocusability(on ? android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                : android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    // Простой: плитки и капсулы уходят вниз и тают, фон снова резкий, стих возвращается
    void toIdle() {
        if (state == IDLE) return;
        if (state == GRID) {
            amb.setGrid(false);
            shelfFocusable(true);
            gridLayer.setVisibility(View.GONE);
            shelf.setAlpha(1f);
            shelf.setTranslationY(0);
        }
        state = IDLE;
        hideShelf(true);
        amb.setActive(false);
    }

    void hideShelf(boolean animated) {
        for (View v : shelfItems()) {
            v.animate().cancel();
            if (animated) v.animate().alpha(0f).translationY(px(16)).setStartDelay(0).setDuration(M.t(450)).setInterpolator(Ambient.EASE).start();
            else { v.setAlpha(0f); v.setTranslationY(px(16)); }
        }
    }

    // Разблокировка: плитки каскадом снизу, следом капсулы; фокус встаёт, когда ряд уже на месте
    void toActive(boolean cascade) {
        if (state == ACTIVE) return;
        if (state == GRID) { closeGrid(); return; }
        state = ACTIVE;
        amb.setActive(true);
        shelf.setAlpha(1f);
        shelf.setTranslationY(0);
        int i = 0;
        for (View v : shelfItems()) {
            boolean isSuggest = v instanceof Suggest;
            long delay = cascade ? (isSuggest ? 480 + (v == play ? 60 : 0) : 150 + 60L * Math.min(i, 6)) : 0;
            v.animate().cancel();
            v.setTranslationY(px(isSuggest ? 16 : 32));
            v.animate().alpha(1f).translationY(0).setStartDelay(M.t(delay)).setDuration(M.t(cascade ? 600 : 300))
                    .setInterpolator(Ambient.EASE).start();
            if (!isSuggest) i++;
        }
        View target = lastDock != null ? lastDock : (dockTiles.isEmpty() ? null : dockTiles.get(0));
        if (target != null && !target.isFocused()) h.postDelayed(target::requestFocus, cascade ? 450 : 0);
    }

    // Что выезжает и прячется вместе с рядом; скрытой капсулы «Продолжить» здесь нет, её ведёт showCont
    List<View> shelfItems() {
        List<View> out = new ArrayList<>(dockTiles);
        if (cont.getVisibility() == View.VISIBLE) out.add(cont);
        out.add(play);
        return out;
    }

    final Runnable goIdle = this::toIdle;

    void restartIdle() {
        h.removeCallbacks(goIdle);
        h.postDelayed(goIdle, IDLE_MS);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            restartIdle();
            if (state == IDLE) {
                // Первое нажатие только «разблокирует» экран
                if (e.getRepeatCount() == 0) toActive(true);
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (state == GRID) closeGrid();
                else if (state == ACTIVE) toIdle();
                return true;
            }
            // Вниз из капсул никуда не ведёт: «Все приложения» открываются своей плиткой
            if (state == ACTIVE && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && getCurrentFocus() instanceof Suggest) return true;
        } else if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // Кнопка «Домой» на главном: к первой плитке
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        restartIdle();
        if (state == IDLE) { toActive(true); return; }
        if (state == GRID) {
            amb.setGrid(false);
            shelfFocusable(true);
            gridLayer.setVisibility(View.GONE);
            shelf.setAlpha(1f);
            shelf.setTranslationY(0);
            state = ACTIVE;
        }
        if (!dockTiles.isEmpty()) {
            lastDock = dockTiles.get(0);
            lastDock.requestFocus();
        }
        rowScrollTo(0);
    }

    @Override protected void onResume() {
        super.onResume();
        amb.start();
        loadApps();
        updateSuggest();
        // История использования читается в фоне, не чаще раза в 5 с; когда прочиталась, капсула подпишется заново
        Usage.refresh(this, () -> h.post(() -> { if (!isDestroyed()) updateSuggest(); }));
        // Вернулись из приложения: сразу плитки. После долгого перерыва (или первого запуска) экран блокировки
        boolean longAway = pausedAt == 0 || SystemClock.elapsedRealtime() - pausedAt > 5 * 60_000;
        if (!longAway && state == IDLE) toActive(false);
        restartIdle();
    }

    @Override protected void onPause() {
        amb.stop();
        h.removeCallbacksAndMessages(null);
        pausedAt = SystemClock.elapsedRealtime();
        super.onPause();
    }

    @Override public void onBackPressed() { }
}
