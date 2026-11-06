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
    // Приложения, которые открывают плитки-ярлыки, скрываются на лету (loadApps), пока стоит их ярлык: удалили ярлык или
    // ярлыка нет, приложение видно в сетке как обычное
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "app.glasstv.home", "app.glasstv.clip", "app.glasstv.probe", "com.spocky.projengmenu",
            "com.brouken.player", "com.google.android.youtube.tv", "com.neilturner.aerialviews", "com.android.tv.settings",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx"));
    static final long IDLE_MS = 60_000;
    // Сетка «Все приложения» открыта дольше: выбирать там можно спокойно, а без нажатий она сама уступит экрану блокировки
    static final long GRID_IDLE_MS = 5 * 60_000;
    // На экране блокировки 15 минут без нажатий: «Сторож» убавляет подсветку вдвое, любая кнопка возвращает сразу
    static final long SAVER_MS = 15 * 60_000;
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
    boolean vision;   // стиль «Vision» (Style): капсулы над сферами, ряд-карусель
    String styleName;
    TextView flash;   // короткая плашка с названием стиля после переключения
    final View[] gridHead = new View[2];
    long pausedAt;
    // Правка ряда с пульта (05.11): меню плитки, плитка «в руке», порядок в настройках
    static final String DOCK_PREFS = "dock", DOCK_KEY = "order";
    static final long LONG_MS = 600;   // удержание OK: меню плитки
    FrameLayout menu;                  // меню плитки поверх экрана; null: закрыто
    View menuScrim;                    // затемнение фона под рядом, пока открыто меню
    final List<View> menuDimmed = new ArrayList<>();   // соседи плитки с меню: приглушены
    Tile menuFor, moving, okDown;
    boolean longFired;
    String focusPkg;                   // после перестройки ряда фокус на эту плитку
    // Библиотека приложений (06.11): скрытые, отметка «Новое», ярлыки и их приложения, события установки и удаления
    static final String HIDE_KEY = "hidden", SEEN_KEY = "seen";
    static final long NEW_MS = 3L * 24 * 3600_000;   // «Новое»: поставлено за последние 3 дня и ещё не открывалось
    boolean showHidden;                // сетка показывает скрытые приложения
    boolean reloadLater;               // пакеты сменились, пока было открыто меню или плитка в руке
    int gridFocusAt = -1;              // после перестройки сетки фокус на это место
    final Map<String, String> tileTarget = new HashMap<>();   // плитка-ярлык: приложение, которое она открывает
    final Map<String, String> tileSwap = new HashMap<>();     // ярлык, который открывает не то, что вшито в него: выбор человека или замена

    // Другой вариант того же приложения (06.11, план Codex: связка плитки и цели). Нет нашей сборки браузера, а есть TV Bro
    // из Google Play, нет одного файлового менеджера, а есть другой: ярлык не пропадает, открывается то, что стоит.
    // Тот же список в tools/tv.sh (tiles_install), чтобы мастер ставил такие плитки
    static final Map<String, String[]> ALT = new HashMap<>();
    static {
        ALT.put("com.phlox.tvwebbrowser.foss", new String[]{"com.phlox.tvwebbrowser"});
        ALT.put("com.phlox.tvwebbrowser", new String[]{"com.phlox.tvwebbrowser.foss"});
        ALT.put("me.zhanghai.android.files", new String[]{"com.cxinventor.file.explorer"});
        ALT.put("com.cxinventor.file.explorer", new String[]{"me.zhanghai.android.files"});
        ALT.put("org.smarttube.stable", new String[]{"com.liskovsoft.smarttubetv.beta", "com.google.android.youtube.tv"});
    }

    String alternative(String target) {
        String[] a = ALT.get(target);
        if (a != null) for (String x : a) if (present(x)) return x;
        return null;
    }
    android.content.BroadcastReceiver pkgEvents;
    int setupRev = -1;                 // номер правки настроек «Дома» (Setup), с которым экран нарисован

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        saverSent = getSharedPreferences("home", MODE_PRIVATE).getBoolean("saverSent", false);
        dp = getResources().getDisplayMetrics().density;
        Fonts.init(this);
        amb = new Ambient(this);
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
            // На краю ряда фокус стоит (05.11): раньше «вправо» с последней плитки уводило его из ряда на капсулы,
            // и при зажатой стрелке фокус прыгал по капсулам
            @Override public boolean arrowScroll(int d) {
                View cur = findFocus();
                if (cur == this) cur = null;
                View next = android.view.FocusFinder.getInstance().findNextFocus(this, cur, d);
                if (next == null) {
                    if ((d == View.FOCUS_LEFT || d == View.FOCUS_RIGHT) && vision && keyRepeat == 0) edgeBump(d == View.FOCUS_RIGHT ? 1 : -1);
                    return d == View.FOCUS_LEFT || d == View.FOCUS_RIGHT;
                }
                return next.requestFocus(d);
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
        flash = Fonts.text(this, Fonts.CAPTION, Fonts.med18, Fonts.WHITE_92);
        android.graphics.drawable.GradientDrawable fb = new android.graphics.drawable.GradientDrawable();
        fb.setColor(0x8C000000);
        fb.setCornerRadius(px(15));
        flash.setBackground(fb);
        flash.setPadding(px(12), px(4), px(12), px(4));
        flash.setAlpha(0f);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        fl.topMargin = px(10);
        root.addView(flash, fl);
        applyStyle(Style.get(this));
        setContentView(root);
        // Фон окна под корнем не виден (корень сам тёмный, сверху фото), а видеоядро заливало его каждый кадр:
        // с частицами экран перерисовывается целиком, и эта лишняя заливка 1080p стоила около миллисекунды (27.10)
        getWindow().setBackgroundDrawable(null);
        // Заставки Android больше нет, и пока открыт «Дом», экран не гаснет сам
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> onFocus(n));
        watchPackages();
        amb.onNow = this::updateSuggest;
        hideShelf(false);
    }

    int px(float v) { return Math.round(v * dp); }

    // Стиль экрана. «Графит»: ряд плиток, под ним капсулы. «Vision»: капсулы по центру, под ними ряд сфер
    void applyStyle(String s) {
        styleName = s;
        boolean v = !Style.GRAPHITE.equals(s);
        vision = v;
        amb.setStyle(v);
        if (rowSpring != null) rowSpring.stop();
        cont.compact(v);
        play.compact(v);
        shelf.removeAllViews();
        LinearLayout.LayoutParams rsl = new LinearLayout.LayoutParams(-1, px((v ? Style.H : Grid.TILE_H) + 2 * PAD));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, px(v ? Style.CAPS_H : 44));
        if (v) {
            rsl.topMargin = px(2);
            shelf.addView(suggestRow, srl);
            shelf.addView(rowScroll, rsl);
        } else {
            srl.topMargin = px(28 - PAD);
            shelf.addView(rowScroll, rsl);
            shelf.addView(suggestRow, srl);
        }
        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) shelf.getLayoutParams();
        sl.topMargin = px(v ? Style.CAPS_Y : Grid.ROW_Y - PAD);
        shelf.setLayoutParams(sl);
        suggestRow.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.START | Gravity.CENTER_VERTICAL);
        appsSig = null;   // ряд перестроится в новой форме при ближайшем loadApps
    }

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
        back.setText("Удерживайте OK: меню приложения · «Назад»: выйти");
        gridHead[1] = back;
        gridLayer.addView(back, Grid.at(back, dp, Grid.RIGHT, 64, true, 0));
        // Высота сетки не ограничена экраном: иначе строки за его нижним краем сжимались в ноль и все ложились друг на друга
        gridBox = new LinearLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
        };
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
        // Пока открыто меню или плитка в руке, ряд и сетку не трогаем: перестроятся, как только меню закроется
        if (menu != null || moving != null) { reloadLater = true; return; }
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.GET_META_DATA)) {
                String p = r.activityInfo.packageName;
                if (!apps.containsKey(p)) apps.put(p, r);
            }
        }
        // Плитки-ярлыки (06.11): какое приложение открывает ярлык, написано в его метаданных (target). Ярлык, чьего приложения
        // нет на ТВ, не показывается нигде (в ряду его место сохраняется: поставят приложение снова, ярлык вернётся туда же).
        // Приложение, у которого стоит ярлык, в сетке не повторяется: его открывает ярлык
        tileTarget.clear();
        tileSwap.clear();
        Set<String> covered = new HashSet<>(), orphan = new HashSet<>();
        for (ResolveInfo r : apps.values()) {
            android.os.Bundle md = r.activityInfo.metaData;
            String tg = md == null ? null : md.getString("target");
            if (tg == null || tg.isEmpty()) continue;
            String baked = tg;
            String ov = Setup.tileApp(this, r.activityInfo.packageName);   // человек выбрал для ярлыка своё приложение
            if (ov != null && present(ov)) tg = ov;
            else if (!present(tg)) { String alt = alternative(tg); if (alt != null) tg = alt; }
            tileTarget.put(r.activityInfo.packageName, tg);
            if (!tg.equals(baked)) tileSwap.put(r.activityInfo.packageName, tg);
            if (present(tg)) covered.add(tg); else orphan.add(r.activityInfo.packageName);
        }
        Set<String> hiddenByUser = userHidden();
        // Набор и версии приложений не менялись: плитки не пересобираем, иначе сбивается фокус.
        // Путь к файлу приложения меняется при каждом обновлении, по нему видно новую картинку плитки
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo r : apps.values()) sb.append(r.activityInfo.applicationInfo.sourceDir).append(';');
        boolean round = vision;
        List<String> order = dockOrder();
        sb.append(styleName);   // сменили стиль в полосе шестерёнки: ряд перестраивается
        sb.append('|').append(android.text.TextUtils.join(",", order));   // и после правки ряда
        sb.append('|').append(android.text.TextUtils.join(",", hiddenByUser)).append('|').append(showHidden).append('|').append(Setup.rev(this));
        String sig = sb.toString();
        if (sig.equals(appsSig)) return;
        appsSig = sig;
        // Перестройка не должна сбивать фокус: в ряду остаёмся на той же плитке, в сетке на том же месте
        View was = getCurrentFocus();
        if (focusPkg == null && was instanceof Tile && dockTiles.contains(was)) focusPkg = ((Tile) was).pkg;
        int gridAt = gridFocusAt >= 0 ? gridFocusAt : state == GRID ? gridIndexOf(was) : -1;
        gridFocusAt = -1;
        dock.removeAllViews();
        dockTiles.clear();
        for (String p : order) {
            Tile t;
            if (p.equals("all")) t = allTile = allAppsTile();
            else {
                ResolveInfo r = apps.get(p);
                if (r == null || orphan.contains(p)) continue;
                t = tile(r, true);
            }
            if (round) t.orb(px(Style.D), px(Style.W), px(Style.H), dp);
            LinearLayout.LayoutParams lp = round ? new LinearLayout.LayoutParams(px(Style.W), px(Style.H))
                    : new LinearLayout.LayoutParams(px(Grid.TILE_W), px(Grid.TILE_H));
            if (dock.getChildCount() > 0) lp.leftMargin = px(round ? Style.GAP : Grid.GAP);
            dock.addView(t, lp);
            dockTiles.add(t);
        }
        // Высота ряда под форму: шар с подписью выше плитки, капсулы под рядом сдвигаются вниз вместе с ним
        android.view.ViewGroup.LayoutParams rsl = rowScroll.getLayoutParams();
        int rowH = px((round ? Style.H : Grid.TILE_H) + 2 * PAD);
        // «Графит»: левый край первой плитки на линии часов. «Vision»: карусель, у краёв поле в полэкрана,
        // чтобы и первая, и последняя сфера могли встать посередине
        int padL = px(round ? Grid.W / 2 - Style.W / 2 : Grid.MARGIN);
        if (dock.getPaddingLeft() != padL) dock.setPadding(padL, dock.getPaddingTop(), padL, dock.getPaddingBottom());
        if (rsl != null && rsl.height != rowH) {
            rsl.height = rowH;
            rowScroll.setLayoutParams(rsl);
            rowScroll.scrollTo(0, 0);
        }
        // Остальные приложения сеткой по 4 в ряд, по алфавиту. Скрытые человеком в общей сетке не видны, у них свой список
        // (плитка «Скрытые» в конце); в режиме скрытых сетка показывает только их
        List<ResolveInfo> rest = new ArrayList<>();
        Set<String> fav = new HashSet<>(order);
        int hiddenCount = 0;
        for (ResolveInfo r : apps.values()) {
            String p = r.activityInfo.packageName;
            icons.put(p, r.loadIcon(pm));
            if (fav.contains(p) || HIDDEN.contains(p) || covered.contains(p) || orphan.contains(p)) continue;
            boolean h = hiddenByUser.contains(p);
            if (h) hiddenCount++;
            if (h == showHidden) rest.add(r);
        }
        if (showHidden && hiddenCount == 0) {   // вернули последнее скрытое: обратно ко всем приложениям
            showHidden = false;
            appsSig = null;
            loadApps();
            return;
        }
        rest.sort((a, c) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(c.loadLabel(pm))));
        List<View> all = new ArrayList<>();
        // В сетке все приложения одинаково: значок и подпись на тёмной плитке, без чужих баннеров
        for (ResolveInfo r : rest) {
            Tile t = tile(r, false);
            if (!showHidden && isNew(r.activityInfo.packageName)) t.badge("Новое");
            all.add(t);
        }
        if (!showHidden) {
            all.add(installTile());
            if (hiddenCount > 0) all.add(hiddenTile(hiddenCount));
            all.add(setupTile());
            if (!apps.containsKey("app.glasstv.tile.settings")) all.add(settingsTile());
        }
        ((TextView) gridHead[0]).setText(showHidden ? "Скрытые приложения" : "Все приложения");
        ((TextView) gridHead[1]).setText(showHidden ? "Удерживайте OK: вернуть в список · «Назад»: ко всем приложениям"
                : "Удерживайте OK: меню приложения · «Назад»: выйти");
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
        Tile want = null;
        if (focusPkg != null) for (Tile t : dockTiles) if (focusPkg.equals(t.pkg)) want = t;
        focusPkg = null;
        lastDock = want != null ? want : dockTiles.isEmpty() ? null : dockTiles.get(0);
        if (lastDock != null && state != GRID) lastDock.post(() -> lastDock.requestFocus());
        if (state == GRID) {
            final List<View> g = gridTiles();
            final int at = Math.max(0, Math.min(g.size() - 1, gridAt < 0 ? 0 : gridAt));
            if (!g.isEmpty()) gridBox.post(() -> g.get(at).requestFocus());
        }
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

    // Плитка «Установить приложение» в конце сетки: откуда можно поставить новое приложение на этом ТВ
    Tile installTile() {
        Drawable plus = getDrawable(android.R.drawable.ic_input_add);
        Tile t = new Tile(this, "install", "Установить", Tile.app(this, plus, "Установить", px(Grid.TILE_W), px(Grid.TILE_H)), 1.08f);
        t.setOnClickListener(v -> openInstallMenu(t));
        return t;
    }

    // Плитка «Настройки «Дома»» в конце сетки: город, курсы, стих, стиль, приложения ярлыков (SetupActivity)
    Tile setupTile() {
        Drawable gear = getDrawable(android.R.drawable.ic_menu_preferences);
        Tile t = new Tile(this, "setup", "Настройки «Дома»", Tile.app(this, gear, "Настройки «Дома»", px(Grid.TILE_W), px(Grid.TILE_H)), 1.08f);
        t.setOnClickListener(v -> {
            try { startActivity(new Intent(this, SetupActivity.class)); } catch (Exception ignored) { }
        });
        return t;
    }

    // Плитка «Скрытые: N»: список приложений, которые человек скрыл из сетки
    Tile hiddenTile(int n) {
        Drawable eye = getDrawable(android.R.drawable.ic_menu_view);
        String label = "Скрытые: " + n;
        Tile t = new Tile(this, "hidden", label, Tile.app(this, eye, label, px(Grid.TILE_W), px(Grid.TILE_H)), 1.08f);
        t.setOnClickListener(v -> { showHidden = true; gridFocusAt = 0; appsSig = null; loadApps(); });
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
        // Ярлык, для которого на ТВ выбрано другое приложение (Setup): открываем выбранное
        String ov = tileTarget.containsKey(pkg) ? Setup.tileApp(this, pkg) : null;
        if (ov != null && present(ov)) pkg = ov;
        else if (tileSwap.containsKey(pkg) && present(tileSwap.get(pkg))) pkg = tileSwap.get(pkg);   // замена варианта
        markSeen(pkg);
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
                try { sendBroadcast(new Intent().setClassName("app.glasstv.volna", "app.glasstv.volna.AppCmd").putExtra("do", "my")); }
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
        CAPS.put("tv.twitch.android.app", "twitch");
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
        // Для проверок с ПК: разбор экрана (uiautomator) на «Доме» не снимается из-за постоянных анимаций, фокус видно в журнале
        if (v instanceof Tile) android.util.Log.d("home", "фокус: " + ((Tile) v).label);
        // Пока фокус на плитках, капсулы приглушены; на капсуле она проявляется, соседняя чуть тише
        // Скрытой капсуле «Продолжить» затухание ни к чему: её проявит showCont по текущему фокусу
        boolean hasCont = cont.getVisibility() == View.VISIBLE;
        if (v instanceof Suggest) {
            if (hasCont) cont.fade(v == cont ? 1f : 0.62f);
            play.fade(v == play ? 1f : 0.62f);
        } else if (v instanceof Tile && dockTiles.contains(v)) {
            // «Vision»: капсулы стоят над рядом как виджеты под часами, совсем их не гасим
            // 05.11: тише, чем было (0,8): капсулы не должны спорить со сферами
            float q = vision ? 0.62f : 0.45f;
            if (hasCont) cont.fade(q);
            play.fade(q);
        }
        if (v instanceof Tile && dockTiles.contains(v)) {
            lastDock = v;
            scrollDock((Tile) v);
            if (vision) makeRoom((Tile) v);
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
        if (vision) {
            // «Vision»: сфера в фокусе всегда посередине экрана, ряд едет под ней
            int max = Math.max(0, dock.getWidth() - rowScroll.getWidth());
            int target = Math.max(0, Math.min(max, t.getLeft() + t.getWidth() / 2 - rowScroll.getWidth() / 2));
            if (target == rowTarget && rowSpring != null && rowSpring.running()) return;
            rowScrollTo(target);
            return;
        }
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

    // «Vision»: ряд едет на пружине без отскока. Новая цель посреди движения не обрывает его, скорость сохраняется:
    // при зажатой стрелке карусель течёт ровно, а не дёргается от перезапусков
    Spring rowSpring;

    void rowScrollTo(int to) {
        if (vision) {
            if (rowAnim != null) rowAnim.cancel();
            if (rowSpring == null) {
                rowSpring = new Spring(rowScroll.getScrollX(), v -> rowScroll.scrollTo(Math.round(v), 0)).tune(1f, 0.42f);
                rowSpring.eps = 0.5f;
            }
            if (!rowSpring.running()) { rowSpring.value = rowScroll.getScrollX(); rowSpring.velocity = 0; }
            rowTarget = to;
            rowSpring.to(to);
            return;
        }
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
        if (showHidden) { showHidden = false; appsSig = null; loadApps(); }   // сетка всегда открывается со всех приложений
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
        restartIdle();
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
        restartIdle();
    }

    // Пока открыта сетка, невидимый ряд под ней не должен ловить фокус: иначе «вниз» уводит рамку в пустоту
    void shelfFocusable(boolean on) {
        shelf.setDescendantFocusability(on ? android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                : android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    // Простой: плитки и капсулы уходят вниз и тают, фон снова резкий, стих возвращается
    void toIdle() {
        closeMenu(false);
        endMove();
        if (state == IDLE) return;
        boolean fromGrid = state == GRID;
        if (fromGrid) {
            // Сетка плавно уходит, под ней сразу экран блокировки: плитки ряда уже спрятаны, им выезжать незачем
            amb.setGrid(false);
            shelfFocusable(true);
            gridLayer.animate().alpha(0f).translationY(px(20)).setStartDelay(0).setDuration(M.t(420))
                    .setInterpolator(Ambient.EASE).withEndAction(() -> gridLayer.setVisibility(View.GONE)).start();
            shelf.setTranslationY(0);
        }
        state = IDLE;
        hideShelf(!fromGrid);
        if (fromGrid) shelf.setAlpha(1f);
        amb.setActive(false);
        h.removeCallbacks(saverOn);
        h.postDelayed(saverOn, SAVER_MS);
    }

    // Сразу экран блокировки, без анимаций: ТВ выключили, при включении первый же кадр должен быть чистым
    void idleNow() {
        closeMenu(false);
        endMove();
        h.removeCallbacks(goIdle);
        if (state == GRID) {
            amb.setGrid(false);
            shelfFocusable(true);
            gridLayer.animate().cancel();
            gridLayer.setVisibility(View.GONE);
            shelf.animate().cancel();
            shelf.setAlpha(1f);
            shelf.setTranslationY(0);
        }
        state = IDLE;
        hideShelf(false);
        amb.setIdleNow();
    }

    // Подсветку вдвое убавляет «Сторож» (у него доступ к подсветке TCL), он же возвращает её по команде
    // Помним и на диске (06.11): «Дом», перезапущенный посреди приглушения, иначе не говорил «Сторожу» вернуть подсветку
    boolean saverSent;
    final Runnable saverOn = () -> { setSaverSent(true); guardSaver(true); };

    void saverOff() {
        h.removeCallbacks(saverOn);
        if (!saverSent) return;
        setSaverSent(false);
        guardSaver(false);
    }

    void setSaverSent(boolean v) {
        saverSent = v;
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("saverSent", v).apply();
    }

    void guardSaver(boolean on) {
        try {
            sendBroadcast(new Intent().setClassName("app.glasstv.guard", "app.glasstv.guard.AppCmd").putExtra("do", "saver").putExtra("on", on));
        } catch (Exception ignored) { }
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
        h.postDelayed(goIdle, state == GRID ? GRID_IDLE_MS : IDLE_MS);
    }

    // Повтор зажатой клавиши (0: обычное нажатие): по нему ряд отличает удержание стрелки от шага
    int keyRepeat;

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean arrow = kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (menu != null) return menuKey(e, ok);
        if (moving != null) return moveKey(e);
        if (vision && state == ACTIVE && arrow) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                keyRepeat = e.getRepeatCount();
                Tile.holding = keyRepeat > 0;
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                boolean was = Tile.holding;
                Tile.holding = false;
                keyRepeat = 0;
                if (was && getCurrentFocus() instanceof Tile) ((Tile) getCurrentFocus()).holdEnded();
            }
        }
        // OK на плитке (ряд и «Все приложения»): короткое нажатие открывает, удержание открывает меню плитки.
        // Сфера «Vision» ещё и проседает на нажатии и отпружинивает на отпускании, приложение открывается после отскока.
        // Отпускание считается, только если нажатие было на этой же плитке: первое нажатие на экране блокировки
        // лишь будит экран и ничего не открывает
        View fv = getCurrentFocus();
        if (ok && fv instanceof Tile && state != IDLE) {
            Tile t = (Tile) fv;
            boolean orbDock = vision && dockTiles.contains(t);
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                restartIdle();
                saverOff();
                if (e.getRepeatCount() == 0) {
                    okDown = t;
                    longFired = false;
                    if (orbDock) t.press(true);
                    h.removeCallbacks(longPress);
                    if (editable(t)) h.postDelayed(longPress, LONG_MS);
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && okDown == t) {
                okDown = null;
                h.removeCallbacks(longPress);
                if (longFired) { longFired = false; return true; }
                if (orbDock) t.press(false);
                if (e.isCanceled()) return true;
                if (orbDock) h.postDelayed(t::performClick, M.t(110));
                else t.performClick();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            restartIdle();
            saverOff();
            if (state == IDLE) {
                // Первое нажатие только «разблокирует» экран
                if (e.getRepeatCount() == 0) toActive(true);
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (state == GRID && showHidden) {
                    showHidden = false;
                    gridFocusAt = Integer.MAX_VALUE;   // на плитку «Скрытые» в конце
                    appsSig = null;
                    loadApps();
                } else if (state == GRID) closeGrid();
                else if (state == ACTIVE) toIdle();
                return true;
            }
            // «Графит»: вниз из капсул никуда не ведёт, «Все приложения» открываются своей плиткой.
            // «Vision»: капсулы над рядом, вниз из них на сферу посередине, вверх из них некуда
            if (state == ACTIVE && getCurrentFocus() instanceof Suggest) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (vision) {
                        View back = lastDock != null ? lastDock : (dockTiles.isEmpty() ? null : dockTiles.get(0));
                        if (back != null) back.requestFocus();
                    }
                    return true;
                }
                if (vision && k == KeyEvent.KEYCODE_DPAD_UP) return true;
            }
        } else if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // Кнопка «Домой» на главном: к первой плитке. «Сторож» шлёт сюда простой (idle): от него экран блокировки.
    // now: ТВ выключили, переход не нужен, при включении сразу чистый кадр
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        closeMenu(false);
        endMove();
        // С ПК (проверка, откат): am start -n app.glasstv.home/.HomeActivity --es dock reset (ряд по умолчанию)
        // или --es dock <пакеты через запятую>; что стоит сейчас, пишется в журнал с тегом home
        String dk = i.getStringExtra("dock");
        if (dk != null) {
            if ("reset".equals(dk)) getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).edit().remove(DOCK_KEY).apply();
            else if (!"show".equals(dk)) saveDock(new ArrayList<>(Arrays.asList(dk.split(","))));
            appsSig = null;
            loadApps();
            android.util.Log.i("home", "ряд: " + android.text.TextUtils.join(",", dockOrder()));
            return;
        }
        if (i.getBooleanExtra("idle", false)) {
            keepIdle = true;
            if (i.getBooleanExtra("now", false) || !resumed) {
                idleNow();
                // ТВ включат уже на первой плитке, как после кнопки «Домой»
                if (!dockTiles.isEmpty()) lastDock = dockTiles.get(0);
                rowScrollTo(0);
            } else {
                toIdle();
            }
            return;
        }
        saverOff();
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

    boolean resumed, keepIdle;

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        String st = Style.get(this);
        if (!st.equals(styleName)) {
            applyStyle(st);
            showFlash(Style.title(st));
        }
        amb.start();
        int sr = Setup.rev(this);
        if (sr != setupRev) {   // вернулись из «Настроек «Дома»» с правками: погода, курсы, стих, ярлыки
            boolean first = setupRev < 0;
            setupRev = sr;
            if (!first) { Data.get(this).setupChanged(); amb.applySetup(); appsSig = null; }
        }
        loadApps();
        updateSuggest();
        // История использования читается в фоне, не чаще раза в 5 с; когда прочиталась, капсула подпишется заново
        Usage.refresh(this, () -> h.post(() -> { if (!isDestroyed()) updateSuggest(); }));
        // Вернулись из приложения: сразу плитки. После долгого перерыва (или первого запуска) экран блокировки
        boolean longAway = pausedAt == 0 || SystemClock.elapsedRealtime() - pausedAt > 5 * 60_000;
        if (!longAway && !keepIdle && state == IDLE) toActive(false);
        keepIdle = false;
        restartIdle();
        if (state == IDLE) h.postDelayed(saverOn, SAVER_MS);
    }

    @Override protected void onPause() {
        resumed = false;
        Tile.holding = false;
        keyRepeat = 0;
        closeMenu(false);
        endMove();
        okDown = null;
        longFired = false;
        saverOff();
        amb.stop();
        h.removeCallbacksAndMessages(null);
        pausedAt = SystemClock.elapsedRealtime();
        super.onPause();
    }

    @Override public void onBackPressed() { }

    // ===== Правка ряда с пульта (05.11) =====
    // Удержание OK на плитке открывает её меню. В ряду: «Переместить» (плитка в руке, стрелки двигают, OK кладёт)
    // и «Убрать с главного экрана» (уходит в «Все приложения»). У плитки «Все приложения» только «Переместить»: без неё
    // в сетку не попасть. В «Все приложения»: «Добавить на главный экран» (встаёт перед плиткой «Все приложения»).
    // Порядок хранится в настройках dock, ключ order; записи нет: ряд по умолчанию (FAVORITES)

    List<String> dockOrder() {
        String s = getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).getString(DOCK_KEY, "");
        List<String> l = new ArrayList<>();
        for (String p : s.isEmpty() ? FAVORITES : s.split(",")) if (!p.isEmpty() && !l.contains(p)) l.add(p);
        if (!l.contains("all")) l.add("all");
        return l;
    }

    List<String> currentDock() {
        List<String> l = new ArrayList<>();
        for (Tile t : dockTiles) l.add(t.pkg);
        return l;
    }

    // Приложения, которых сейчас нет в ряду не по воле человека (удалено, обновляется, ярлык без приложения), место не теряют:
    // вернутся туда же. Убранное человеком в ряд не возвращается: в момент сохранения оно было на экране
    void saveDock(List<String> l) {
        List<String> old = dockOrder(), out = new ArrayList<>(l);
        Set<String> shown = new HashSet<>(currentDock());
        for (int i = 0; i < old.size(); i++) {
            String p = old.get(i);
            if (out.contains(p) || shown.contains(p)) continue;
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int k = out.indexOf(old.get(j));
                if (k >= 0) { at = k + 1; break; }
            }
            out.add(at, p);
        }
        l = out;
        getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).edit().putString(DOCK_KEY, android.text.TextUtils.join(",", l)).apply();
        android.util.Log.i("home", "ряд сохранён: " + android.text.TextUtils.join(",", l));
    }

    // Запасная плитка «Настройки» в сетке (когда нет своей плитки настроек) в ряд не добавляется
    boolean editable(Tile t) { return t.pkg != null && !"settings".equals(t.pkg) && !"install".equals(t.pkg) && !"hidden".equals(t.pkg) && !"setup".equals(t.pkg); }

    final Runnable longPress = () -> {
        Tile t = okDown;
        if (t == null || !t.isFocused() || menu != null) return;
        longFired = true;
        if (vision && dockTiles.contains(t)) t.press(false);
        openMenu(t);
    };

    boolean menuKey(KeyEvent e, boolean ok) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) restartIdle();
        if (ok && longFired) {   // ещё держат OK, которым открыли меню: это не выбор пункта
            if (e.getAction() == KeyEvent.ACTION_UP) { longFired = false; okDown = null; }
            return true;
        }
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) closeMenu(true);
            return true;
        }
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        return super.dispatchKeyEvent(e);
    }

    // Меню плитки (06.11). Ряд: «Переместить», «Убрать с главного экрана». Сетка: «Добавить на главный экран», «Скрыть».
    // Скрытые: «Вернуть в список». Для настоящего приложения ещё «О приложении» (системный экран сведений) и, если его можно
    // удалить, «Удалить приложение» (системное окно подтверждения). У ярлыка эти два пункта про приложение, которое он открывает.
    // Убрать и скрыть меняют только расположение: пакет и данные остаются. Удалить можно только то, что ставили не мы и не система
    void openMenu(Tile t) {
        boolean inDock = dockTiles.contains(t);
        List<Object[]> acts = new ArrayList<>();
        if (inDock) {
            acts.add(new Object[]{"Переместить", true, (Runnable) () -> startMove(t)});
            if (!"all".equals(t.pkg)) acts.add(new Object[]{"Убрать с главного экрана", false, (Runnable) () -> removeFromDock(t)});
        } else if (showHidden) {
            acts.add(new Object[]{"Вернуть в список", false, (Runnable) () -> setHidden(t, false)});
        } else {
            acts.add(new Object[]{"Добавить на главный экран", false, (Runnable) () -> addToDock(t)});
            acts.add(new Object[]{"Скрыть", false, (Runnable) () -> setHidden(t, true)});
        }
        final String real = realPkg(t.pkg);
        if (real != null) {
            acts.add(new Object[]{"О приложении", true, (Runnable) () -> appInfo(real)});
            if (canDelete(real)) acts.add(new Object[]{"Удалить приложение", true, (Runnable) () -> uninstall(real)});
        }
        showMenu(t, t.label, acts);
    }

    // Меню над плиткой по списку пунктов {подпись, вернуть ли фокус на плитку, действие}
    void showMenu(Tile t, String head, List<Object[]> acts) {
        boolean inDock = dockTiles.contains(t);
        menuFor = t;
        FrameLayout m = new FrameLayout(this);
        // Как в Apple TV: темнеет фон и соседи, плитка с меню остаётся яркой и крупной
        View scrim = new View(this);
        scrim.setBackgroundColor(0x73000000);
        scrim.setAlpha(0f);
        root.addView(scrim, root.indexOfChild(shelf), new FrameLayout.LayoutParams(-1, -1));
        menuScrim = scrim;
        menuDimmed.clear();
        if (inDock) {
            for (Tile o : dockTiles) if (o != t) menuDimmed.add(o);
            menuDimmed.add(suggestRow);
        } else {
            for (int r = 0; r < gridBox.getChildCount(); r++) {
                android.view.ViewGroup row = (android.view.ViewGroup) gridBox.getChildAt(r);
                for (int k = 0; k < row.getChildCount(); k++) if (row.getChildAt(k) != t) menuDimmed.add(row.getChildAt(k));
            }
        }
        for (View v : menuDimmed) v.animate().alpha(0.35f).setStartDelay(0).setDuration(M.t(220)).setInterpolator(Ambient.EASE).start();
        t.keepLit(true);
        LinearLayout card = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(8), px(10), px(8), px(8));
        card.setMinimumWidth(px(300));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF2202024);
        bg.setCornerRadius(px(22));
        bg.setStroke(Math.max(1, px(1)), 0x24FFFFFF);
        card.setBackground(bg);
        TextView title = Fonts.text(this, Fonts.CAPTION, Fonts.med18, Fonts.WHITE_60);
        title.setText(head);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(px(18), px(4), px(18), px(8));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        List<TextView> items = new ArrayList<>();
        for (Object[] a : acts) items.add(menuItem((String) a[0], (Boolean) a[1], (Runnable) a[2]));
        for (TextView v : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(2);
            card.addView(v, lp);
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cw = card.getMeasuredWidth(), ch = card.getMeasuredHeight();
        int[] loc = new int[2], rl = new int[2];
        t.getLocationInWindow(loc);
        root.getLocationInWindow(rl);
        float tx = loc[0] - rl[0], ty = loc[1] - rl[1];
        float tw = t.getWidth() * t.getScaleX();
        float th = (vision && inDock ? px(Style.D) : t.getHeight()) * t.getScaleY();
        float cx = tx + tw / 2f;
        int sw = root.getWidth() > 0 ? root.getWidth() : px(Grid.W);
        int sh = root.getHeight() > 0 ? root.getHeight() : px(540);
        // Где встать: над плиткой; не влезает, под ней; не влезает и там (длинное меню у плитки посередине экрана),
        // сбоку от плитки на её высоте, внутри экрана (06.11: четвёртый пункт уходил за нижний край)
        int x = Math.round(Math.max(px(32), Math.min(sw - px(32) - cw, cx - cw / 2f)));
        int y = Math.round(ty - px(vision && inDock ? 12 : 6) - px(16) - ch);
        int side = 0;   // 0 над или под, 1 справа, -1 слева
        boolean above = y >= px(20);
        if (!above) {
            y = Math.round(ty + th + px(16));
            if (y + ch > sh - px(20)) {
                side = tx + tw + px(16) + cw <= sw - px(24) ? 1 : -1;
                x = side > 0 ? Math.round(tx + tw + px(16)) : Math.round(Math.max(px(24), tx - px(16) - cw));
                y = Math.round(Math.max(px(20), Math.min(sh - px(20) - ch, ty + th / 2f - ch / 2f)));
            }
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(cw, ch, Gravity.TOP | Gravity.START);
        cl.leftMargin = x;
        cl.topMargin = y;
        m.addView(card, cl);
        root.addView(m, new FrameLayout.LayoutParams(-1, -1));
        menu = m;
        card.setPivotX(side > 0 ? 0 : side < 0 ? cw : Math.max(0, Math.min(cw, cx - x)));
        card.setPivotY(side != 0 ? Math.max(0, Math.min(ch, ty + th / 2f - y)) : above ? ch : 0);
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        if (side != 0) card.setTranslationX(px(side > 0 ? -10 : 10));
        else card.setTranslationY(px(above ? 10 : -10));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0).translationY(0).setStartDelay(0).setDuration(M.t(280))
                .setInterpolator(Ambient.EASE).start();
        scrim.animate().alpha(1f).setStartDelay(0).setDuration(M.t(220)).start();
        shelfFocusable(false);
        gridLayer.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        items.get(0).requestFocus();
    }

    TextView menuItem(String text, boolean refocus, Runnable act) {
        final android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(px(14));
        TextView v = new TextView(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
            @Override protected void onFocusChanged(boolean f, int d, android.graphics.Rect r) {
                super.onFocusChanged(f, d, r);
                setBackground(f ? on : null);
                setTextColor(f ? 0xFF141416 : Fonts.WHITE_92);
                animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setStartDelay(0).setDuration(M.t(160))
                        .setInterpolator(Ambient.EASE).start();
            }
        };
        v.setText(text);
        v.setTextSize(18);
        v.setTypeface(Fonts.med18);
        v.setTextColor(Fonts.WHITE_92);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setPadding(px(18), px(13), px(18), px(13));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> { closeMenu(refocus); act.run(); });
        return v;
    }

    void closeMenu(boolean back) {
        if (menu == null) return;
        final FrameLayout m = menu;
        menu = null;
        m.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfFocusable(state != GRID);
        gridLayer.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        Tile f = menuFor;
        menuFor = null;
        if (back && f != null && f.isAttachedToWindow()) f.requestFocus();
        if (f != null) f.keepLit(false);
        final View scrim = menuScrim;
        menuScrim = null;
        if (scrim != null) scrim.animate().alpha(0f).setStartDelay(0).setDuration(M.t(180)).withEndAction(() -> root.removeView(scrim)).start();
        for (View v : menuDimmed) v.animate().alpha(1f).setStartDelay(0).setDuration(M.t(220)).setInterpolator(Ambient.EASE).start();
        menuDimmed.clear();
        View card = m.getChildCount() > 0 ? m.getChildAt(0) : null;
        if (card != null) card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(M.t(180))
                .setInterpolator(Ambient.EASE).withEndAction(() -> root.removeView(m)).start();
        else root.removeView(m);
        if (reloadLater && moving == null) { reloadLater = false; h.post(reloadApps); }
    }

    // ===== Скрытые, сведения, удаление, установка (06.11) =====

    Set<String> userHidden() {
        String s = getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).getString(HIDE_KEY, "");
        Set<String> out = new java.util.TreeSet<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setHidden(Tile t, boolean hide) {
        Set<String> h = userHidden();
        if (hide) h.add(t.pkg); else h.remove(t.pkg);
        getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).edit().putString(HIDE_KEY, android.text.TextUtils.join(",", h)).apply();
        gridFocusAt = gridIndexOf(t);
        appsSig = null;
        loadApps();
        showFlash(hide ? "Скрыто. Вернуть: плитка «Скрытые» в конце списка" : "Вернулось во «Все приложения»");
    }

    // Приложение за плиткой: у ярлыка то, что он открывает; у служебных плиток нет
    String realPkg(String p) {
        if (p == null || "all".equals(p) || "install".equals(p) || "hidden".equals(p) || "settings".equals(p) || "setup".equals(p)) return null;
        String tg = tileTarget.get(p);
        return tg != null ? tg : p;
    }

    // Удалять можно только приложение, которое ставили не мы и не система: свои части «Дома» и Key Mapper (кнопки пульта) не удаляются отсюда
    boolean canDelete(String p) {
        if (p.startsWith("app.glasstv.") || "io.github.sds100.keymapper".equals(p)) return false;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(p, 0);
            return (ai.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    boolean present(String p) {
        try { return getPackageManager().getApplicationInfo(p, 0).enabled; } catch (Exception e) { return false; }
    }

    void appInfo(String p) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + p))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showFlash("Сведения о приложении на этом ТВ не открываются");
        }
    }

    // Удаление только через системное окно: человек видит, что удаляется, и подтверждает сам
    void uninstall(String p) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showFlash("Окно удаления не открылось");
        }
    }

    // «Новое»: поставлено за последние 3 дня и ещё ни разу не открывалось с «Дома»
    boolean isNew(String p) {
        try {
            long at = getPackageManager().getPackageInfo(p, 0).firstInstallTime;
            if (System.currentTimeMillis() - at > NEW_MS) return false;
        } catch (Exception e) {
            return false;
        }
        String s = "," + getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).getString(SEEN_KEY, "") + ",";
        return !s.contains("," + p + ",");
    }

    void markSeen(String p) {
        if (p == null || !isNew(p)) return;
        String s = getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).getString(SEEN_KEY, "");
        getSharedPreferences(DOCK_PREFS, MODE_PRIVATE).edit().putString(SEEN_KEY, s.isEmpty() ? p : s + "," + p).apply();
    }

    // Откуда ставить новое приложение на этом ТВ: показываются только способы, которые здесь есть
    void openInstallMenu(Tile t) {
        List<Object[]> acts = new ArrayList<>();
        addSource(acts, "Google Play", "com.android.vending");
        addSource(acts, "Aurora Store (без входа в Google)", "com.aurora.store");
        String files = installed("me.zhanghai.android.files") ? "me.zhanghai.android.files"
                : installed("com.cxinventor.file.explorer") ? "com.cxinventor.file.explorer" : null;
        if (files != null) addSource(acts, "Файл APK с флешки", files);
        addSource(acts, "С телефона или компьютера: LocalSend", "org.localsend.localsend_app");
        if (acts.isEmpty()) {
            showFlash("На этом ТВ нет ни магазина, ни файлового менеджера");
            return;
        }
        showMenu(t, "Откуда установить", acts);
    }

    void addSource(List<Object[]> acts, String label, String pkg) {
        if (installed(pkg)) acts.add(new Object[]{label, true, (Runnable) () -> launch(pkg, null)});
    }

    List<View> gridTiles() {
        List<View> out = new ArrayList<>();
        for (int r = 0; r < gridBox.getChildCount(); r++) {
            android.view.ViewGroup row = (android.view.ViewGroup) gridBox.getChildAt(r);
            for (int k = 0; k < row.getChildCount(); k++) out.add(row.getChildAt(k));
        }
        return out;
    }

    int gridIndexOf(View v) { return v == null ? -1 : gridTiles().indexOf(v); }

    // Установили, обновили или удалили приложение, пока «Дом» открыт: ряд и сетка перестраиваются сами.
    // Снятие старой версии при обновлении (REMOVED с EXTRA_REPLACING) удалением не считается: следом придёт замена
    void watchPackages() {
        pkgEvents = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                h.removeCallbacks(reloadApps);
                h.postDelayed(reloadApps, 600);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pkgEvents, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(pkgEvents, f);
    }

    final Runnable reloadApps = () -> { if (!isDestroyed()) loadApps(); };

    @Override protected void onDestroy() {
        if (pkgEvents != null) try { unregisterReceiver(pkgEvents); } catch (Exception ignored) { }
        super.onDestroy();
    }

    void startMove(Tile t) {
        if (!dockTiles.contains(t)) return;
        moving = t;
        t.setMoving(true);
        showHint("Влево и вправо: переставить · OK: готово");
    }

    boolean moveKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        restartIdle();
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            step(kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (e.getRepeatCount() == 0) endMove();   // OK, «Назад», вверх, вниз: положить
        return true;
    }

    // Обмен с соседом. Фокус всё время на плитке в руке, поэтому из ряда вынимается сосед, а не она
    // (вынутая плитка теряла бы фокус). Все сдвинутые доезжают от старых мест на пружине
    void step(int dir) {
        int i = dockTiles.indexOf(moving), j = i + dir;
        if (i < 0) { endMove(); return; }
        if (j < 0 || j >= dockTiles.size()) {
            if (vision) edgeBump(dir);
            return;
        }
        final Map<View, Integer> was = new HashMap<>();
        for (int k = 0; k < dock.getChildCount(); k++) was.put(dock.getChildAt(k), dock.getChildAt(k).getLeft());
        Tile other = dockTiles.get(j);
        dock.removeView(other);
        dock.addView(other, i);
        java.util.Collections.swap(dockTiles, i, j);
        for (int k = 0; k < dock.getChildCount(); k++) {
            View c = dock.getChildAt(k);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
            int want = k == 0 ? 0 : px(vision ? Style.GAP : Grid.GAP);
            if (lp.leftMargin != want) { lp.leftMargin = want; c.setLayoutParams(lp); }
        }
        dock.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                dock.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int k = 0; k < dock.getChildCount(); k++) {
                    View c = dock.getChildAt(k);
                    Integer w = was.get(c);
                    if (w != null && w != c.getLeft() && c instanceof Tile) ((Tile) c).slide(w - c.getLeft());
                }
                if (moving != null) {
                    scrollDock(moving);
                    if (vision) makeRoom(moving);
                }
                return true;
            }
        });
    }

    void endMove() {
        if (moving == null) return;
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        hideHint();
        saveDock(currentDock());
        if (reloadLater) { reloadLater = false; h.post(reloadApps); }
    }

    void addToDock(Tile t) {
        markSeen(t.pkg);
        List<String> order = currentDock();
        if (order.contains(t.pkg)) return;
        int at = order.indexOf("all");
        order.add(at < 0 ? order.size() : at, t.pkg);
        saveDock(order);
        focusPkg = t.pkg;
        closeGrid();
        appsSig = null;
        loadApps();
        showFlash("Добавлено на главный экран");
    }

    void removeFromDock(Tile t) {
        List<String> order = currentDock();
        int i = order.indexOf(t.pkg);
        if (i < 0 || "all".equals(t.pkg)) return;
        order.remove(i);
        saveDock(order);
        focusPkg = order.get(Math.min(i, order.size() - 1));
        appsSig = null;
        loadApps();
        showFlash("Убрано с главного экрана, оно в «Все приложения»");
    }

    // Подсказка на время перестановки: та же плашка сверху, что и у стиля, но держится, пока плитка в руке
    void showHint(String s) {
        flash.setText(s);
        flash.animate().cancel();
        flash.animate().alpha(1f).setStartDelay(0).setDuration(M.t(250)).start();
    }

    void hideHint() {
        flash.animate().cancel();
        flash.animate().alpha(0f).setStartDelay(0).setDuration(M.t(300)).start();
    }

    // «Vision»: соседи сферы в фокусе расступаются (на 6 dp ближние, на 2 dp следующие) и тихо возвращаются
    void makeRoom(Tile f) {
        int i = dockTiles.indexOf(f);
        for (int j = 0; j < dockTiles.size(); j++) {
            int d = j - i, a = Math.abs(d);
            float to = a == 1 ? 6 : a == 2 ? 2 : 0;
            dockTiles.get(j).pushTo(Math.signum(d) * to * dp);
        }
    }

    // Стрелка упёрлась в край ряда: ряд на миг подаётся в сторону нажатия и пружинит обратно, сфера чуть проседает.
    // Только на первом нажатии, на повторах зажатой стрелки ничего не дёргается
    Spring rowBump;

    void edgeBump(int dir) {
        if (rowBump == null) {
            rowBump = new Spring(0f, rowScroll::setTranslationX).tune(0.6f, 0.35f);
            rowBump.eps = 0.5f;
        }
        rowBump.value = rowScroll.getTranslationX();
        rowBump.velocity = dir * 360f * dp;
        rowBump.to(0f);
        View fv = getCurrentFocus();
        if (fv instanceof Tile) ((Tile) fv).squeeze();
    }

    // Плашка сверху по центру на 2,5 с: какой стиль теперь включён
    void showFlash(String s) {
        flash.setText(s);
        flash.animate().cancel();
        flash.setAlpha(0f);
        flash.animate().alpha(1f).setStartDelay(M.t(250)).setDuration(M.t(250)).withEndAction(() ->
                flash.animate().alpha(0f).setStartDelay(M.t(2500)).setDuration(M.t(500)).start()).start();
    }
}
