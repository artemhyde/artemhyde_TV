package app.glasstv.volna;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Движок: держит связь с плеером и ведёт очередь «Волны». Звук играет своя сборка Aura (app.glasstv.aura, Aura-patches), её экран не показывается.
// Правила, ноябрь 2026:
// 1. Пока музыка нужна, работает Keeper: иначе система замораживает Aura во время игры.
// 2. Трек просим по точному videoId: команда id/<videoId> выполняется сразу, без поиска и базы, чужому треку взяться неоткуда.
// 3. Серия нажатий сводится к последнему треку. Адрес потока следующего трека готовится заранее.
// 4. Закрыть значит ACTION_HARD_STOP: плеер пуст, все пути запуска внутри Aura закрыты до следующей явной команды.
// 5. Профиль это канал аккаунта: лайки и личные подборки идут от его имени, куки не покидают Aura.
final class Engine {
    static final String AURA = "app.glasstv.aura";
    static final String AURA_SERVICE = "com.auramusic.app.playback.MusicService";
    static final String HARD_STOP = "ACTION_HARD_STOP", PRELOAD = "VOLNA_PRELOAD", PROFILE = "VOLNA_PROFILE",
            FEED = "VOLNA_FEED", LIKE = "VOLNA_LIKE", WHOAMI = "VOLNA_WHOAMI";

    static final long SWITCH_TIMEOUT_MS = 15_000;   // за столько Aura должна включить трек
    static final long BURST_MS = 200;               // команды не чаще: серия нажатий сводится к последней
    static final long RELEASE_MS = 45_000;          // столько Aura не отпускаем после тишины: следующая команда сработает сразу
    static final long END_GAP_MS = 300;             // следующий трек просим за столько до конца: хвост песни не теряется
    static final long PRELOAD_AFTER_MS = 3_000;     // через столько после старта трека готовим адрес следующего
    static final long ASK_MS = 8_000;               // дольше ответа Aura на вопрос не ждём

    interface Listener { void onEngine(boolean newTrack); }

    interface Answer { void on(JSONObject o); }   // o == null: ответа нет

    private static Engine instance;

    static Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    static int uiVisible;   // сколько экранов «Волны» сейчас открыто: тогда плашку о треке не показываем

    final Context app;
    final boolean live;           // false: тихий движок стенда (Bench), своя память, поддельный плеер и источник
    final Source source;
    private final java.util.Map<String, List<Track>> testSeed;
    Link link;                    // команды плееру; null: связи нет
    interface Hook { void on(String event, Track t); }
    Hook hook;                    // стенд слушает, что движок просит
    Link testLink;                // стенд: поддельный плеер, к которому движок «подключается»
    long testReconnectMs = 300;
    private Object testConn;
    final SharedPreferences ui;   // общее для всех профилей: какой профиль выбран, режим плашки
    Store store;                  // вкус выбранного профиля (первого или второго)
    Brain brain;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private MediaBrowser browser;
    private MediaController ctl;
    private boolean connecting;
    private boolean watching;
    private boolean startup = true;

    boolean driving;          // «Волна» ведёт очередь
    int mode;
    Track planned;            // трек, который должен играть
    private boolean userPaused;           // волна идёт, но стоит на паузе
    private boolean switching;            // ждём, пока Aura включит planned
    private long switchAt;
    private long lastSendAt;
    private boolean waitingForBrain;
    private int errorsInRow;
    private String suspect;               // трек не смог начаться: сеть или сам трек, станет ясно по следующему
    private long stallSince;              // с какого момента музыка стоит
    private long lastStateAt;             // когда Aura последний раз сообщала о себе
    private int stateTicks;
    boolean fakeStall;                    // проверка сторожа с ПК: --es do fakestall
    private boolean keeperOn;

    // ===== Состояние «Волны» одним словом. Считается из флагов при каждом изменении, переходы пишутся в журнал =====
    // IDLE: волна не ведёт (не включали, или в Aura выбрали своё). LOADING: подбор или загрузка трека. PLAYING: звучит по данным Aura.
    // PAUSED: пауза. ERROR: повторы кончились, ошибка на экране, ждём нажатия. STOPPED: закрыли, сама не включится ни от чего.
    // Последняя команда побеждает: каждое нажатие меняет gen, отложенные шаги прошлого намерения снимаются (cancelPending)
    enum Phase { IDLE, LOADING, PLAYING, PAUSED, ERROR, STOPPED }
    Phase phase = Phase.IDLE;
    int gen;                      // поколение намерения пользователя
    long req;                     // номер запроса трека: по нему в журнале связаны запрос, команда, метаданные и игра
    private boolean playLogged;   // STATE_PLAYING этого запроса уже записан
    private boolean stoppedByUser;
    private long stopAt;
    private boolean gaveUp;       // повторы кончились
    private int slowRetries;      // повторов раз в минуту подряд
    private long listenedReq = -1;
    private String tag = "volna"; // у стенда volna-t: его журнал не путается с настоящим
    static final long PARK_MS = 20 * 60_000;   // столько живёт кэш очередей профиля после смены
    private final java.util.Map<String, Brain.Parked> parked = new java.util.HashMap<>();   // профиль -> что собрал его прошлый подбор

    // Что играет сейчас (по данным Aura)
    String curId;
    String curTitle = "", curArtist = "";
    long curDur;
    Bitmap curArt;
    String badge = "";
    int state = PlaybackState.STATE_NONE;
    private long posBase, posTime;
    private float speed = 1f;
    String status = "";

    private Engine(Context c) { this(c, true, Source.NET, null); }

    // live false: второй, тихий движок для стенда. Своя память (volnatest), ни плашки, ни службы, ни «Дома», ни настоящей Aura
    Engine(Context c, boolean live, Source source, java.util.Map<String, List<Track>> testSeed) {
        app = c;
        this.live = live;
        this.source = source;
        this.testSeed = testSeed;
        tag = live ? "volna" : "volna-t";
        ui = c.getSharedPreferences(live ? "volna_ui" : "volnatest_ui", Context.MODE_PRIVATE);
        if (live && !ui.contains("toastAlways"))   // раньше настройка плашки жила в памяти первого профиля
            ui.edit().putBoolean("toastAlways", c.getSharedPreferences("volna2", Context.MODE_PRIVATE).getBoolean("toastAlways", false)).apply();
        store = newStore(ui.getString("profile", Store.MAIN));
        brain = newBrain();
        mode = store.mode();
        brain.refillIfLow(Brain.MY);
        if (!live) return;
        // «Волну» перезапустили (обновление, система выгрузила): спросить «Дом», открыт ли он сейчас
        try {
            c.sendBroadcast(new android.content.Intent().setClassName("app.glasstv.home", "app.glasstv.home.Now").putExtra("hello", true));
        } catch (Exception ignored) { }
        // ТВ выключили: музыка закрывается совсем, при следующем включении ничего не заиграет
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context ctx, android.content.Intent i) { screenOff(); }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        watchSessions();
    }

    private Store newStore(String id) {
        return live ? new Store(app, id) : new Store(app, id, "volnatest", testSeed.get(id));
    }

    @SuppressWarnings("unchecked")
    private Brain newBrain() {
        final Brain b = new Brain(store);
        b.source = source;
        b.tag = tag;
        // Подборки аккаунта идут от имени профиля этого подбора, даже если профиль уже сменили. Закрытый подбор Aura не спрашивает
        final String ch = channel();
        b.personal = (kind, seed) -> b.closed() ? null : feed(ch, kind, seed);
        b.ready = m -> {
            if (b != brain) return;   // ответ закрытого подбора прошлого профиля
            // Подбор принёс первый трек, а мы как раз стоим без музыки
            if (waitingForBrain && driving && m == mode && !switching) { waitingForBrain = false; playNext(); }
        };
        b.changed = () -> { if (b == brain) for (Listener l : new ArrayList<>(listeners)) l.onEngine(false); };
        Brain.Parked p = parked.remove(store.profile);
        if (p != null && SystemClock.elapsedRealtime() - p.at < PARK_MS) b.adopt(p);
        return b;
    }

    // ===== Профили: у каждого из двух свой вкус, лайки и «не моё»; имена задаёт Config. Второй профиль это канал аккаунта с тем же именем =====

    // Имена меняются с пульта («Настройки «Дома»», 06.11, план Codex, блок 3); Config даёт только начальные
    String profileName(String id) { return id.isEmpty() ? ui.getString("name_main", Config.PROFILE1) : ui.getString("name_" + id, Config.PROFILE2); }

    // Как профиль называется для Aura: пусто значит основной профиль аккаунта. Второй профиль Aura ищет по имени канала, поэтому
    // при первом переименовании нынешнее имя запоминается как имя канала (chan_): экранное имя меняется, канал остаётся тем же
    private String channel() { return store.profile.isEmpty() ? "" : ui.getString("chan_" + store.profile, profileName(store.profile)); }

    void renameProfile(boolean second, String name) {
        if (name == null || name.trim().isEmpty()) return;
        String n = name.trim().length() > 24 ? name.trim().substring(0, 24) : name.trim();
        android.content.SharedPreferences.Editor ed = ui.edit();
        if (second) {
            if (!ui.contains("chan_" + Store.SECOND)) ed.putString("chan_" + Store.SECOND, profileName(Store.SECOND));
            ed.putString("name_" + Store.SECOND, n);
        } else {
            ed.putString("name_main", n);
        }
        ed.apply();
        Log.i(tag, "имя вкуса: " + (second ? "второй" : "первый") + " теперь «" + n + "»");
    }

    void setProfile(String id) {
        if (id.equals(store.profile)) return;
        long from = SystemClock.elapsedRealtime();
        boolean wasPlaying = driving;
        if (driving) stop();   // музыку, которую включили не через «Волну», не трогаем
        gen++;
        history.clear();       // «Очередь» и «назад» нового вкуса не тянут треки прошлого
        planned = null;        // и прошлый трек не попадёт в историю нового вкуса при старте
        store.save();
        // Старый подбор закрыт: его потоки, повторы и ответы больше никуда не идут. Готовые очереди ждут возврата в этот профиль
        parked.put(store.profile, brain.close());
        ui.edit().putString("profile", id).apply();
        store = newStore(id);
        brain = newBrain();
        mode = store.mode();
        brain.refillIfLow(Brain.MY);
        brain.refillIfLow(Brain.NEW);   // своя очередь у «Нового» тоже: переключение волны потом не ждёт сеть
        Log.i(tag, "профиль: " + profileName(id) + ", треков во вкусе: " + store.seed.size() + ", за " + (SystemClock.elapsedRealtime() - from) + " мс");
        tellProfile();
        if (wasPlaying) start(mode);
        else notifyAll(false);
    }

    // Сказать Aura, чей сейчас профиль: от его имени пойдут её запросы, в том числе история прослушивания. Потом отослать оценки, которые ждали связи
    private void tellProfile() {
        final Store of = store;
        final long from = SystemClock.elapsedRealtime();
        ask(PROFILE, new Bundle(), o -> {
            Log.i(tag, "канал «" + channel() + "»: " + (o == null ? "нет ответа" : o.toString()) + ", " + (SystemClock.elapsedRealtime() - from) + " мс");
            if (o != null && o.optBoolean("ok") && of == store) {
                flushRatings();
                brain.accountReady();
            }
        });
    }

    private void screenOff() {
        if (driving) {
            Log.i(tag, "экран погас, волна закрыта");
            stop();
        } else if (link != null && auraPlaying()) {
            Log.i(tag, "экран погас, музыка из самой Aura на паузе");
            link.pause();
        }
    }

    // Закрыть: тишина сразу, пустой плеер, все ожидания сняты. Открыть снова можно только явно: плитка, кнопка, команда
    void stop() {
        gen++;
        noteListen();
        stoppedByUser = true;
        stopAt = SystemClock.elapsedRealtime();
        gaveUp = false;
        slowRetries = 0;
        driving = false;
        switching = false;
        userPaused = false;
        waitingForBrain = false;
        planned = null;
        fakeStall = false;
        h.removeCallbacks(watchdog);
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(switchTimeout);
        h.removeCallbacks(retry);
        h.removeCallbacks(sendPlanned);
        h.removeCallbacks(preloadAhead);
        silence("волна закрыта");
        forgetTrack();
        status = "";
        Log.i(tag, "закрыто");
        keep();
        notifyAll(false);
    }

    // Все отложенные шаги прошлого намерения: таймер конца, ожидание включения, повтор, отправка, подготовка следующего
    private void cancelPending() {
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(switchTimeout);
        h.removeCallbacks(retry);
        h.removeCallbacks(sendPlanned);
        h.removeCallbacks(preloadAhead);
    }

    // Явное нажатие: новое намерение, «закрыто» и ошибка сняты
    private void userCommand() {
        gen++;
        stoppedByUser = false;
        gaveUp = false;
        slowRetries = 0;
    }

    private Phase phaseNow() {
        if (!driving) return stoppedByUser ? Phase.STOPPED : Phase.IDLE;
        if (gaveUp) return Phase.ERROR;
        if (userPaused) return Phase.PAUSED;
        if (!switching && !waitingForBrain && state == PlaybackState.STATE_PLAYING) return Phase.PLAYING;
        return Phase.LOADING;
    }

    // Кто, что и для кого: в журнал без токенов и куки, только номера, профиль, имя канала и id треков
    String ids() {
        return "req=" + req + " gen=" + gen + " профиль=" + (store.profile.isEmpty() ? "main" : store.profile) + " канал=«" + channel() + "» волна=" + mode
                + " план=" + (planned == null ? "-" : planned.id) + " играет=" + (curId == null ? "-" : curId);
    }

    private void notePhase() {
        Phase p = phaseNow();
        if (p == phase) return;
        Log.i(tag, "состояние " + phase + " → " + p + ": " + ids() + (status.isEmpty() ? "" : ", «" + status + "»"));
        phase = p;
    }

    // Трек прослушан, если отыграл заметную часть: 30 с или половину. Быстро пролистанный только «запрошен» и вкус не меняет
    private void noteListen() {
        if (planned == null || curId == null || !planned.id.equals(curId) || listenedReq == req) return;
        long p = position();
        if (p >= 30_000 || (curDur > 0 && p >= curDur / 2)) {
            listenedReq = req;
            store.listened(planned);
        }
    }

    // Повтор после сбоя. Медленных (раз в минуту) подряд не больше трёх: дальше ошибка на экране, и ждём нажатия
    private void retryLater(long ms) {
        h.removeCallbacks(retry);
        if (ms >= 60_000 && ++slowRetries > 3) { giveUp(); return; }
        h.postDelayed(retry, ms);
    }

    private void giveUp() {
        gaveUp = true;
        switching = false;
        cancelPending();
        status = "Музыка не включается. Проверьте интернет и нажмите ▶";
        Log.w(tag, "повторы кончились: " + ids());
        keep();
        notifyAll(false);
    }

    // Стенд: движок больше не нужен, его подбор закрыт
    void close() {
        stop();
        brain.close();
    }

    private void forgetTrack() {
        curId = null;
        curTitle = curArtist = badge = "";
        curDur = 0;
        curArt = null;
        posBase = posTime = 0;
    }

    private void silence(String why) {
        if (link == null) return;
        Bundle b = new Bundle();
        b.putString("why", why);
        link.custom(HARD_STOP, b);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void notifyAll(boolean newTrack) {
        notePhase();
        for (Listener l : new ArrayList<>(listeners)) l.onEngine(newTrack);
        if (!live) return;
        TrackToast.sync(app, this);
        tellHome();
    }

    // ===== Главный экран «Дом»: пока он открыт, плашка не нужна, «Дом» сам пишет, что играет =====

    static boolean homeVisible;

    void homeShown(boolean on) {
        homeVisible = on;
        h.removeCallbacks(syncAfterHome);
        if (on) {
            TrackToast.hideNow();
            tellHome();
        } else {
            h.postDelayed(syncAfterHome, 700);   // через миг: вдруг из «Дома» открыли саму «Волну»
        }
    }

    private final Runnable syncAfterHome = new Runnable() {
        @Override public void run() { TrackToast.sync(Engine.this.app, Engine.this); }
    };

    private void tellHome() {
        if (!homeVisible) return;
        // Закрытая волна на главном экране выглядит как закрытая: кнопка «Включить», а не прошлый трек
        boolean open = driving || auraPlaying();
        try {
            app.sendBroadcast(new android.content.Intent().setClassName("app.glasstv.home", "app.glasstv.home.Now")
                    .putExtra("playing", open && playing() && shownId() != null)
                    .putExtra("title", open ? shownTitle() : "").putExtra("artist", open ? shownArtist() : ""));
        } catch (Exception ignored) { }
    }

    // Экран «Волны» закрыли, а музыка играет: напомнить плашкой, что играет (через миг, чтобы не мигнуть между экранами)
    void uiHidden() {
        h.removeCallbacks(showAfterUi);
        h.postDelayed(showAfterUi, 700);
    }

    private final Runnable showAfterUi = new Runnable() {
        @Override public void run() {
            if (uiVisible == 0 && !homeVisible && driving && playing() && curId != null) TrackToast.show(Engine.this.app, Engine.this);
        }
    };

    // ===== Связь с Aura =====

    // Сессию Aura система показывает слушателю уведомлений: команды работают и без подключения к её службе,
    // а заодно видно, что Aura играет сама, например с собственного экрана
    void watchSessions() {
        if (!live || watching) return;
        try {
            MediaSessionManager m = app.getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(app, app.glasstv.volna.Listener.class);
            m.addOnActiveSessionsChangedListener(this::onSessions, me, h);
            watching = true;
            onSessions(m.getActiveSessions(me));
        } catch (Exception e) {
            Log.w(tag, "сессии не видны: " + e.getMessage());
        }
    }

    private void onSessions(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) if (AURA.equals(c.getPackageName())) { attach(c); return; }
    }

    // Подключение к службе Aura: поднимает её, если не запущена, и держит её важность вровень с «Волной»
    void connect() {
        if (!live) { testConnect(); return; }
        if (connecting || (browser != null && browser.isConnected())) return;
        dropBrowser();        // сначала старое подключение прочь (оно сбрасывает connecting), потом новое
        connecting = true;
        try {
            final MediaBrowser[] holder = new MediaBrowser[1];
            holder[0] = new MediaBrowser(app, new ComponentName(AURA, AURA_SERVICE), new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    if (holder[0] != browser) return;   // ответ от старого подключения
                    connecting = false;
                    h.removeCallbacks(connectTimeout);
                    attach(new MediaController(app, holder[0].getSessionToken()));
                }
                @Override public void onConnectionFailed() { if (holder[0] == browser) linkDown("Aura не отвечает"); }
                @Override public void onConnectionSuspended() { if (holder[0] == browser) linkDown("Связь с Aura прервалась"); }
            }, null);
            browser = holder[0];
            browser.connect();
            h.postDelayed(connectTimeout, 8_000);
        } catch (Exception e) {
            connecting = false;
            status = "Aura не установлена";
            notifyAll(false);
        }
    }

    private final Runnable connectTimeout = () -> { if (connecting) linkDown("Aura не отвечает"); };

    // Стенд: подключение к поддельному плееру с теми же флагами, что у настоящего (connecting, сброс в dropBrowser)
    private void testConnect() {
        if (connecting || link != null || testLink == null) return;
        connecting = true;
        final Object token = testConn = new Object();
        h.postDelayed(() -> {
            if (token != testConn) return;   // подключение отменили, как browser.disconnect()
            connecting = false;
            attachTest(testLink);
        }, testReconnectMs);
    }

    private void dropBrowser() {
        h.removeCallbacks(connectTimeout);
        testConn = null;
        connecting = false;   // иначе отменённое подключение навсегда считалось «идущим», и Aura больше не поднималась
        if (browser != null) {
            try { browser.disconnect(); } catch (Exception ignored) { }
            browser = null;
        }
    }

    // Подключение к службе оборвалось. Нужна музыка: подключаемся снова. Не нужна: молчим до следующей просьбы
    private void linkDown(String why) {
        Log.w(tag, why);
        connecting = false;
        dropBrowser();
        h.removeCallbacks(reconnect);
        if (musicWanted()) {
            status = why;
            h.postDelayed(reconnect, 2000);
            notifyAll(false);
        }
    }

    private final Runnable reconnect = this::connect;

    // Процесс Aura умер вместе с сессией. Музыка должна была играть: следующий трек после нового подключения
    void sessionGone() {
        boolean resume = driving && !userPaused;
        Log.w(tag, "сессия Aura закрылась, волна " + (resume ? "продолжит" : "молчит"));
        if (ctl != null) ctl.unregisterCallback(cb);
        ctl = null;
        link = null;
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(sendPlanned);
        h.removeCallbacks(preloadAhead);
        state = PlaybackState.STATE_NONE;
        switching = false;
        connecting = false;
        dropBrowser();
        // Aura падает снова и снова: не дёргаем её по кругу, с каждым разом ждём дольше
        if (resume) {
            errorsInRow++;
            status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "";
            retryLater(errorsInRow > 5 ? 60_000 : 1500L * errorsInRow);
        }
        keep();
        notifyAll(false);
    }

    private void attach(MediaController c) {
        boolean same = ctl != null && ctl.getSessionToken().equals(c.getSessionToken());
        if (!same) {
            if (ctl != null) ctl.unregisterCallback(cb);
            ctl = c;
            link = new Link.Ctl(c);
            c.registerCallback(cb, h);
            status = "";
            onMeta(c.getMetadata());
            onState(c.getPlaybackState());
            tellProfile();
        }
        linked();
    }

    // Стенд: поддельный плеер вместо сессии Aura
    void attachTest(Link l) {
        link = l;
        status = "";
        tellProfile();
        linked();
    }

    private void linked() {
        // «Волну» перезапустили или только что закрыли, а Aura играет: это музыка без хозяина, она замолкает
        boolean justStopped = stoppedByUser && SystemClock.elapsedRealtime() - stopAt < 60_000;
        if ((startup || justStopped) && !driving && auraPlaying()) {
            Log.i(tag, "Aura играла без волны " + (startup ? "после перезапуска" : "после закрытия") + ", закрываю");
            silence(startup ? "волна перезапущена" : "волна закрыта");
        }
        startup = false;
        if (driving && switching && planned != null) sendPlanned.run();
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata md) { onMeta(md); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { onState(st); }
        @Override public void onSessionDestroyed() { sessionGone(); }
    };

    // ===== Вопросы к Aura: профиль, подборки, оценки. Ответ JSON строкой, в нём только названия и id =====

    private void ask(String action, Bundle args, final Answer a) {
        Link c = link;
        if (c == null) { if (a != null) a.on(null); return; }
        if (!args.containsKey("profile")) args.putString("profile", channel());
        final boolean[] done = {false};
        final Runnable late = () -> { if (!done[0]) { done[0] = true; if (a != null) a.on(null); } };
        h.postDelayed(late, ASK_MS);
        c.command(action, args, new ResultReceiver(h) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (done[0]) return;
                done[0] = true;
                h.removeCallbacks(late);
                JSONObject o = null;
                try {
                    if (data != null && data.getString("json") != null) o = new JSONObject(data.getString("json"));
                } catch (Exception ignored) { }
                if (a != null) a.on(o);
            }
        });
    }

    // Подборка из аккаунта выбранного профиля для Brain: вызывается не из главного потока. null: нет входа, связи или такого канала, берём открытые запросы
    private List<Track> feed(String ch, String kind, String seed) {
        final AtomicReference<JSONObject> answer = new AtomicReference<>();
        final CountDownLatch got = new CountDownLatch(1);
        h.post(() -> {
            Bundle b = new Bundle();
            b.putString("profile", ch);
            b.putString("kind", kind);
            if (seed != null) b.putString("seed", seed);
            ask(FEED, b, o -> { answer.set(o); got.countDown(); });
        });
        try {
            if (!got.await(ASK_MS + 1000, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            return null;
        }
        JSONObject o = answer.get();
        if (o == null || !o.optBoolean("ok")) {
            Log.i(tag, "подборка " + kind + ": " + (o == null ? "нет ответа" : o.optString("err")));
            return null;
        }
        JSONArray a = o.optJSONArray("items");
        List<Track> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            Track x = new Track(t.optString("id"), t.optString("t"), t.optString("a"), t.optLong("d") * 1000);
            x.thumb = Track.bigThumb(t.optString("th"));
            out.add(x);
        }
        Log.i(tag, "подборка " + kind + " канала «" + ch + "»: " + out.size());
        return out;
    }

    // Кто сейчас в аккаунте, для проверки с ПК: --es do whoami
    void whoami() {
        ask(WHOAMI, new Bundle(), o -> Log.i(tag, "вход: канал «" + channel() + "», ответ " + o));
    }

    // Оценка уходит в канал выбранного профиля и больше никуда. Нет входа или связи: ждёт в памяти профиля. 1 лайк, 0 снять, -1 не нравится
    private void rate(final String id, final int value) {
        final Store of = store;
        of.pendRating(id, value);
        Bundle b = new Bundle();
        b.putString("id", id);
        b.putInt("like", value);
        ask(LIKE, b, o -> {
            boolean ok = o != null && o.optBoolean("ok");
            Log.i(tag, "оценка " + value + " для " + id + ": " + (ok ? "в аккаунте" : o == null ? "нет связи, подождёт" : o.optString("err")));
            if (ok) of.ratingDone(id, value);
        });
    }

    private void flushRatings() {
        for (java.util.Map.Entry<String, Integer> e : store.pending.entrySet()) rate(e.getKey(), e.getValue());
    }

    void onMeta(MediaMetadata md) {
        if (md == null) return;
        String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id == null) return;
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (id.equals(curId)) {
            // Тот же трек, Aura дослала обложку или длительность
            boolean changed = false;
            if (art != null && art != curArt) { curArt = art; if (live) TrackToast.refreshArt(this); changed = true; }
            if (dur > 0 && dur != curDur) { curDur = dur; scheduleEnd(); changed = true; }
            if (changed) notifyAll(false);
            return;
        }
        if (title == null || title.isEmpty()) return;   // промежуточные пустые метаданные
        if (!driving) {
            // Закрытая волна не показывает старые треки, пока Aura молчит
            if (!auraPlaying()) return;
        } else if (planned != null && switching) {
            if (!id.equals(planned.id)) return;   // след прошлой команды: нужный трек уже просили
        } else if (planned != null && curId != null) {
            // Трек сменился посреди песни, и не нами: это выбор на экране самой Aura, волна отходит в сторону
            Log.i(tag, "в Aura выбрали другой трек, волна отходит в сторону");
            driving = false;
            h.removeCallbacks(endOfTrack);
            h.removeCallbacks(watchdog);
        }
        curId = id;
        curTitle = title;
        curArtist = artist == null ? "" : artist;
        curDur = dur > 0 ? dur : planned != null && id.equals(planned.id) ? planned.durationMs : 0;
        curArt = art;
        stateTicks = 0;
        if (driving && planned != null) {
            long now = SystemClock.elapsedRealtime();
            // Метаданные приходят до звука: это не время до звука, его мерят по AudioTrack
            Log.i(tag, "метаданные: «" + title + "» через " + (now - switchAt) + " мс после запроса (ещё не звук), " + ids());
            switching = false;
            h.removeCallbacks(switchTimeout);
            h.removeCallbacks(sendPlanned);
            badge = planned.badge;
            store.started(planned);
            store.save();
            // Нажали паузу, пока трек загружался
            if (userPaused && link != null) link.pause();
            h.removeCallbacks(preloadAhead);
            h.postDelayed(preloadAhead, PRELOAD_AFTER_MS);
        } else {
            badge = "";
        }
        status = "";
        scheduleEnd();
        notifyAll(true);
        if (hook != null) hook.on("meta", planned);
        if (live && uiVisible == 0 && driving && !userPaused) TrackToast.show(app, this);
    }

    // Пока играет трек, Aura заранее узнаёт адрес потока следующего: переключение почти мгновенное
    private final Runnable preloadAhead = new Runnable() {
        @Override public void run() {
            if (!driving || switching || link == null) return;
            List<Track> next = brain.upcoming(mode, 1);
            if (next.isEmpty()) return;
            Bundle b = new Bundle();
            b.putString("id", next.get(0).id);
            link.custom(PRELOAD, b);
        }
    };

    // Взвести таймер конца трека
    private void scheduleEnd() {
        h.removeCallbacks(endOfTrack);
        if (driving && state == PlaybackState.STATE_PLAYING && curDur > 0 && !switching)
            h.postDelayed(endOfTrack, Math.max(200, curDur - position() - END_GAP_MS));
    }

    void onState(PlaybackState st) {
        if (st == null) return;
        int prev = state;
        state = st.getState();
        posBase = st.getPosition();
        posTime = st.getLastPositionUpdateTime();
        speed = st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed();
        lastStateAt = SystemClock.elapsedRealtime();
        stateTicks++;
        if (driving && planned != null && !playLogged && state == PlaybackState.STATE_PLAYING && planned.id.equals(curId)) {
            playLogged = true;
            Log.i(tag, "играет по данным Aura: через " + (lastStateAt - switchAt) + " мс после запроса (STATE_PLAYING, слышимый звук мерить по AudioTrack), " + ids());
            if (hook != null) hook.on("playing", planned);
        }
        if (driving && switching && userPaused && auraPlaying() && link != null) link.pause();
        // Трек не смог заиграть: следующий. Раньше 600 мс это ещё старая ошибка прошлого трека, плеер сообщает её до новой загрузки
        if (driving && state == PlaybackState.STATE_ERROR && prev != PlaybackState.STATE_ERROR && lastStateAt - lastSendAt > 600) {
            errorsInRow++;
            Log.w(tag, "ошибка плеера на «" + curTitle + "»: " + st.getErrorMessage() + ", подряд " + errorsInRow);
            status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "";
            switching = false;
            h.removeCallbacks(switchTimeout);
            h.removeCallbacks(retry);
            // Трек не начался: та же песня другой загрузкой (студийная версия из YouTube Music), и только если её нет, следующий
            if (position() < 1000 && planned != null && planned.id.equals(curId) && triedAlt.add(planned.id)) {
                findAlternative(planned);
            } else {
                if (position() < 1000) suspect = curId;
                retryLater(errorsInRow > 5 ? 60_000 : 300L * errorsInRow);
            }
        }
        if (driving && !switching) {
            // Пульт или фильм, забравший звук, тоже меняют паузу: волна следует за Aura. Но пока трек ещё не заиграл, Aura сама
            // сообщает «пауза», пока готовит поток (видно в журнале 06.11): это не нажатие. Долгую «паузу» до старта принимает сторож
            if (state == PlaybackState.STATE_PAUSED && playLogged) userPaused = true;
            else if (state == PlaybackState.STATE_PLAYING) userPaused = false;
            // Счётчик ошибок обнуляется, только когда трек реально заиграл. Сеть жива, значит трек перед ним YouTube больше не отдаёт: из волны его убираем
            if (state == PlaybackState.STATE_PLAYING && position() > 3000) {
                errorsInRow = 0;
                slowRetries = 0;
                if (suspect != null && !suspect.equals(curId)) {
                    Log.i(tag, "трек " + suspect + " недоступен, в волну больше не попадёт");
                    store.markDead(suspect);
                }
                suspect = null;
            }
            if ((state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE)
                    && prev == PlaybackState.STATE_PLAYING && curId != null) {
                Log.i(tag, "трек кончился на " + position() / 1000 + " с из " + curDur / 1000);
                playNext();   // трек кончился, а наш таймер не успел
                return;
            }
        }
        scheduleEnd();
        keep();
        notifyAll(false);
    }

    // Повтор не снимает паузу и не действует после закрытия или ошибки
    private final Runnable retry = () -> { if (driving && !userPaused && !gaveUp) playNext(); };

    private final java.util.Set<String> triedAlt = new java.util.HashSet<>();

    // Поиск песни по названию и исполнителю в YouTube Music: первая подходящая загрузка с другим id. Не в потоке подбора: он бывает занят
    private void findAlternative(final Track lost) {
        final long from = SystemClock.elapsedRealtime();
        final int g = gen;
        new Thread(() -> {
            Track alt = null;
            try { alt = source.alternative(lost); } catch (Exception ignored) { }
            final Track found = alt;
            h.post(() -> {
                if (g != gen || !driving || planned != lost) return;   // пока искали, нажали что-то ещё
                if (found == null) {
                    Log.i(tag, "замены для «" + lost.title + "» нет, следующий");
                    suspect = lost.id;
                    playNext();
                    return;
                }
                Log.i(tag, "замена «" + lost.title + "»: " + found.id + " «" + found.title + "», " + (SystemClock.elapsedRealtime() - from) + " мс");
                store.alias(lost.id, found.id);
                errorsInRow = 0;
                request(lost.withId(found.id));
                notifyAll(true);
            });
        }, "alt").start();
    }

    // ===== Важность Aura: служба работает, пока музыка нужна, и ещё немного после =====

    private boolean musicWanted() { return switching || (driving && !userPaused && !gaveUp) || auraPlaying(); }

    private void keep() {
        if (musicWanted()) {
            h.removeCallbacks(release);
            if (!keeperOn) {
                keeperOn = true;
                if (live) Keeper.on(app);
                Log.i(tag, "служба включена");
            }
            connect();
        } else if (keeperOn) {
            h.removeCallbacks(release);
            h.postDelayed(release, driving ? RELEASE_MS : 3_000);
        }
    }

    private final Runnable release = () -> {
        if (musicWanted()) { keep(); return; }
        keeperOn = false;
        if (Engine.this.live) Keeper.off(Engine.this.app);
        Log.i(tag, "служба выключена");
        // Волна закрыта: Aura отпускаем совсем, память ей больше не нужна
        if (!driving) dropBrowser();
    };

    // ===== Сторож: раз в секунду смотрит, не встала ли музыка =====
    // Сеть пропала посреди песни (Aura пишет «загрузка») или Aura перестала сообщать о себе: идём на следующий трек.
    // Команду на новый трек ведёт switchTimeout, а смерть процесса видна сразу по закрытию сессии
    static final long STALL_MS = 8_000, SILENT_MS = 15_000;

    private void watch() {
        stallSince = 0;
        h.removeCallbacks(watchdog);
        if (driving) h.postDelayed(watchdog, 1000);
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!driving) return;
            h.postDelayed(this, 1000);
            long now = SystemClock.elapsedRealtime();
            // Aura стоит на «паузе» дольше 8 с и ни разу не заиграла: это пауза с пульта во время загрузки, волна её принимает
            if (!switching && !userPaused && !playLogged && planned != null && state == PlaybackState.STATE_PAUSED && now - lastStateAt >= STALL_MS) {
                userPaused = true;
                Log.i(tag, "Aura стоит на паузе до старта трека: принимаю как паузу, " + ids());
                Engine.this.notifyAll(false);
                return;
            }
            String why = stallReason(now);
            if (why == null) { stallSince = 0; return; }
            if (stallSince == 0) stallSince = now;
            if (now - stallSince >= STALL_MS) {
                Log.w(tag, "сторож: " + why + " («" + curTitle + "», " + position() / 1000 + " с)");
                stallSince = 0;
                fakeStall = false;
                errorsInRow++;
                playNext();
            }
        }
    };

    private String stallReason(long now) {
        if (fakeStall) return "проверка с ПК";
        if (switching || userPaused || gaveUp || link == null) return null;
        if (state == PlaybackState.STATE_BUFFERING) return "загрузка стоит на одном месте";
        if (state == PlaybackState.STATE_ERROR) return "плеер стоит с ошибкой";
        // Пока музыка идёт, Aura сообщает о себе каждые 3 с
        if (state == PlaybackState.STATE_PLAYING && stateTicks >= 3 && now - lastStateAt > SILENT_MS) return "Aura молчит";
        return null;
    }

    // Связь с Aura с чистого листа: от повторных неудач подряд
    void restartAura(String why) {
        Log.w(tag, "перезапуск связи с Aura: " + why);
        errorsInRow++;
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(sendPlanned);
        Link l = link;
        if (l != null) try { l.stopPlayer(); } catch (Exception ignored) { }
        if (ctl != null) ctl.unregisterCallback(cb);
        ctl = null;
        link = null;
        connecting = false;
        dropBrowser();
        state = PlaybackState.STATE_NONE;
        switching = false;
        status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "Aura зависла, перезапускаю";
        notifyAll(false);
        retryLater(errorsInRow > 5 ? 60_000 : 1500);
    }

    // У самого конца трека просим следующий: адрес его потока уже готов, поэтому пауза между песнями короткая
    private final Runnable endOfTrack = new Runnable() {
        @Override public void run() {
            if (!driving || switching || state != PlaybackState.STATE_PLAYING) return;
            long remain = curDur - position();
            if (remain > END_GAP_MS + 500) { h.postDelayed(this, remain - END_GAP_MS); return; }
            Log.i(tag, "конец трека: позиция " + position() / 1000 + " с из " + curDur / 1000);
            Track t = brain.next(mode);
            if (t == null) return;   // подбор пуст: конец трека обработает onState
            rememberCurrent();
            store.requested(t);
            request(t);
            Engine.this.notifyAll(true);
        }
    };

    long position() {
        if (state == PlaybackState.STATE_PLAYING && posTime > 0)
            return Math.min(curDur > 0 ? curDur : Long.MAX_VALUE, posBase + (long) ((SystemClock.elapsedRealtime() - posTime) * speed));
        return posBase;
    }

    // Что показывать на кнопке: намерение, а не миг загрузки. Значок не мигает на каждой смене трека
    boolean playing() { return driving ? !userPaused : auraPlaying(); }

    boolean auraPlaying() { return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING; }

    // ===== Команды =====

    // Запустить волну (или переключиться на другую): сразу играет первый трек
    void start(int m) {
        userCommand();
        // Вкус профиля ещё не загружен (нет её плейлистов): «Моей волне» не из чего собираться
        if (m == Brain.MY && store.empty()) m = Brain.NEW;
        // Другая волна: прошлый трек уходит сразу и из экрана, и из звука, история новой волны начинается с нуля
        if (driving && m != mode) {
            noteListen();
            history.clear();
            planned = null;   // иначе играющий трек прошлой волны сразу ляжет в историю новой
            // Ожидание включения, отправка и таймеры прошлой волны больше не действуют. Раньше оставался switching,
            // и первый трек новой волны ждал таймаута прошлой (до 15 с)
            cancelPending();
            switching = false;
            if (brain.peek(m) == null) {
                silence("смена волны");
                forgetTrack();
            }
        }
        mode = m;
        store.mode(m);
        driving = true;
        userPaused = false;
        waitingForBrain = false;
        errorsInRow = 0;
        watch();
        playNext();
    }

    void playNext() {
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(retry);
        Track t = brain.next(mode);
        if (t == null) {
            waitingForBrain = true;
            status = Net.down() ? "Нет связи с YouTube Music, пробую снова" : "Подбираю новое для вас…";
            keep();
            notifyAll(false);
            return;
        }
        waitingForBrain = false;
        rememberCurrent();
        // Сразу «запрошен»: иначе подбор успел бы выбрать этот же трек ещё раз, пока Aura его грузит. Прослушанным он станет позже
        store.requested(t);
        request(t);
        notifyAll(true);   // экраны сразу показывают новый трек, не дожидаясь Aura
    }

    // Пока Aura готовит наш трек, экраны уже показывают его, а не прошлый: смена вкуса и «дальше» видны сразу
    // Пока подбор нового (вкус сменили на «Новое») ищет первый трек, экраны уже чистые, без прошлого трека
    // Пока подбор ищет, а прошлый трек ещё звучит (нажали «дальше» при пустой очереди), экран показывает его: экран и звук не расходятся
    boolean waiting() { return driving && waitingForBrain && curId == null; }

    boolean pending() { return driving && !waitingForBrain && switching && planned != null && !planned.id.equals(curId); }

    String shownId() { return waiting() ? null : pending() ? planned.id : curId; }

    String shownTitle() { return waiting() ? "" : pending() ? planned.title : curTitle; }

    String shownArtist() { return waiting() ? "" : pending() ? planned.artist : curArtist; }

    String shownBadge() { return waiting() ? "" : pending() ? planned.badge : badge; }

    Bitmap shownArt() { return waiting() || pending() ? null : curArt; }

    // Квадратная обложка YouTube Music для показанного трека, если подборка её дала
    String shownThumb() {
        String id = shownId();
        return planned != null && id != null && id.equals(planned.id) ? planned.thumb : "";
    }

    long shownDur() { return waiting() ? 0 : pending() ? planned.durationMs : curDur; }

    // ===== Назад, перемотка, выбранный трек =====

    // Что уже отыграло в этой волне: для кнопки «назад»
    private final java.util.ArrayDeque<Track> history = new java.util.ArrayDeque<>();

    // Текущий трек в историю, только если он действительно заиграл
    private void rememberCurrent() {
        noteListen();
        if (planned == null || curId == null || !planned.id.equals(curId)) return;
        if (!history.isEmpty() && history.peekLast().id.equals(planned.id)) return;
        history.addLast(planned);
        while (history.size() > 40) history.pollFirst();
    }

    boolean hasPrev() { return !history.isEmpty(); }

    java.util.List<Track> history() { return new ArrayList<>(history); }

    // «Назад» как в обычных плеерах: дальше 5 секунд от начала возвращает к началу, иначе предыдущий трек
    void prev() {
        if (!driving) { start(mode); return; }
        userCommand();
        if (position() > 5000 || history.isEmpty()) { seekTo(0); return; }
        Track t = history.pollLast();
        play(t, false);
    }

    void seekBy(long ms) { seekTo(position() + ms); }

    void seekTo(long ms) {
        if (link == null || curDur <= 0) return;
        long to = Math.max(0, Math.min(curDur - 1500, ms));
        link.seekTo(to);
        posBase = to;
        posTime = SystemClock.elapsedRealtime();
        // Перемотали ближе к концу: таймер стыка пересчитать от нового места
        h.removeCallbacks(endOfTrack);
        h.postDelayed(endOfTrack, 500);
        notifyAll(false);
    }

    // Включить выбранный трек (из списка «Треки» или «назад»); после него волна идёт дальше как обычно
    void play(Track chosen, boolean remember) {
        userCommand();
        userPaused = false;
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(retry);
        if (remember) rememberCurrent();
        brain.drop(chosen.id);
        driving = true;
        waitingForBrain = false;
        watch();
        Track t = chosen.copy();
        t.kind = Track.FAMILIAR;
        t.badge = chosen.badge.isEmpty() ? "▶ Вы выбрали" : chosen.badge;
        store.requested(t);
        request(t);
        notifyAll(true);
    }

    // Хочу, чтобы играл этот трек. Первое нажатие уходит сразу, серия сводится к последнему
    private void request(Track t) {
        // Эта загрузка не играет, но раньше нашлась замена: сразу она
        String alt = store.alias.get(t.id);
        if (alt != null && !alt.equals(t.id)) t = t.withId(alt);
        planned = t;
        switching = true;
        switchAt = SystemClock.elapsedRealtime();
        req++;
        playLogged = false;
        // Пауза не снимается: сами её снимают только нажатия (start, next, play, toggle). Автоповтор и таймауты паузу не теряют
        status = "";
        h.removeCallbacks(switchTimeout);
        h.postDelayed(switchTimeout, SWITCH_TIMEOUT_MS);
        keep();
        if (hook != null) hook.on("request", t);
        if (link == null) return;   // уйдёт сразу после подключения (attach)
        // Тот же трек уже в Aura: достаточно вернуть его в начало
        if (t.id.equals(curId)) {
            switching = false;
            h.removeCallbacks(switchTimeout);
            badge = t.badge;
            link.seekTo(0);
            if (!userPaused) link.resume();
            return;
        }
        long wait = BURST_MS - (switchAt - lastSendAt);
        h.removeCallbacks(sendPlanned);
        if (wait <= 0) sendPlanned.run();
        else {
            // Отправка ждёт конца серии нажатий, а прошлый трек ещё может догрузиться и зазвучать: держим его на паузе
            link.pause();
            h.postDelayed(sendPlanned, wait);
        }
    }

    private final Runnable sendPlanned = new Runnable() {
        @Override public void run() {
            if (!switching || !driving || link == null || planned == null) return;
            h.removeCallbacks(this);
            lastSendAt = SystemClock.elapsedRealtime();
            Log.i(tag, "команда: «" + planned.title + "», " + planned.id + ", через " + (lastSendAt - switchAt) + " мс после запроса, " + ids());
            if (hook != null) hook.on("send", planned);
            link.play(mediaId(planned));
            if (userPaused) link.pause();   // трек сменился сам (повтор, конец), а пауза стоит: загрузится и будет ждать
        }
    };

    // Команда своей сборке Aura: трек по точному id. Название и исполнитель сразу видны на плашке, длительность для таймера конца
    static String mediaId(Track t) {
        return "id/" + t.id + "/" + Uri.encode(t.title) + "/" + Uri.encode(t.artist) + "/" + t.durationMs;
    }

    // Aura за 15 секунд не включила трек: берём следующий, с третьего раза подряд подключаемся заново;
    // при долгой беде пробуем раз в минуту
    private final Runnable switchTimeout = () -> {
        if (!switching || !driving || userPaused) return;   // на паузе ждать включения не нужно
        errorsInRow++;
        Log.w(tag, "Aura не включила «" + (planned == null ? "" : planned.title) + "» за 15 с, ошибок подряд " + errorsInRow);
        if (errorsInRow <= 2) { playNext(); return; }
        if (errorsInRow <= 5) { restartAura("Aura не включила трек за 15 с"); return; }
        switching = false;
        status = "Нет связи с Aura или YouTube, пробую снова";
        notifyAll(false);
        retryLater(60_000);
    };

    void toggle() {
        if (!driving || gaveUp) { start(mode); return; }
        gen++;
        if (!userPaused) {
            userPaused = true;
            if (link != null) link.pause();
        } else {
            userPaused = false;
            if (switching) {
                // Трек ещё загружается. Если команда уже ушла, снять паузу у плеера; ждать включения заново
                if (link != null && lastSendAt >= switchAt) link.resume();
                h.removeCallbacks(switchTimeout);
                h.postDelayed(switchTimeout, SWITCH_TIMEOUT_MS);
            }
            else if (link == null || curId == null || planned == null || state == PlaybackState.STATE_NONE
                    || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) playNext();
            else link.resume();
        }
        keep();
        notifyAll(false);
    }

    void pause() { if (driving && !userPaused) toggle(); }

    void seekNearEnd() { if (link != null && curDur > 8000) link.seekTo(curDur - 6000); }

    void next() {
        userCommand();
        if (!driving) start(mode);
        else { userPaused = false; playNext(); }
    }

    Track current() {
        Track t = new Track(curId == null ? "" : curId, curTitle, curArtist, curDur);
        if (planned != null && planned.id.equals(curId)) {
            t.kind = planned.kind;
            t.bucket = planned.bucket;
            t.tags = planned.tags;
            t.badge = planned.badge;
        }
        return t;
    }

    boolean liked() { return curId != null && store.liked.contains(curId); }

    // Сердце: поставить лайк, а на треке с лайком снять его
    void like() {
        if (curId == null) return;
        Track t = current();
        if (liked()) {
            rate(t.id, 0);
            store.unlike(t);
        } else {
            rate(t.id, 1);
            store.like(t);
            brain.onLike(t);
        }
        notifyAll(false);
    }

    // «Не моё»: «не нравится» в аккаунте профиля, в блок-лист, вес направления вниз, сразу следующий
    void dislike() {
        if (curId == null) { next(); return; }
        Track t = current();
        rate(t.id, -1);
        store.dislike(t);
        brain.onDislike(t);
        next();
    }
}
