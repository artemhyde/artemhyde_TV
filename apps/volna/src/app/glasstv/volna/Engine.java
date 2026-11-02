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

    private Engine(Context c) {
        app = c;
        ui = c.getSharedPreferences("volna_ui", Context.MODE_PRIVATE);
        if (!ui.contains("toastAlways"))   // раньше настройка плашки жила в памяти первого профиля
            ui.edit().putBoolean("toastAlways", c.getSharedPreferences("volna2", Context.MODE_PRIVATE).getBoolean("toastAlways", false)).apply();
        store = new Store(c, ui.getString("profile", Store.MAIN));
        brain = newBrain();
        mode = store.mode();
        brain.refillIfLow(Brain.MY);
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

    private Brain newBrain() {
        Brain b = new Brain(store);
        b.personal = this::feed;
        b.ready = m -> {
            // Подбор принёс первый трек, а мы как раз стоим без музыки
            if (waitingForBrain && driving && m == mode && !switching) { waitingForBrain = false; playNext(); }
        };
        return b;
    }

    // ===== Профили: у каждого из двух свой вкус, лайки и «не моё»; имена задаёт Config. Второй профиль это канал аккаунта с тем же именем =====

    String profileName(String id) { return id.isEmpty() ? Config.PROFILE1 : ui.getString("name_" + id, Config.PROFILE2); }

    // Как профиль называется для Aura: пусто значит основной профиль аккаунта
    private String channel() { return store.profile.isEmpty() ? "" : profileName(store.profile); }

    void setProfile(String id) {
        if (id.equals(store.profile)) return;
        long from = SystemClock.elapsedRealtime();
        boolean wasPlaying = driving;
        if (driving) stop();   // музыку, которую включили не через «Волну», не трогаем
        history.clear();       // «Очередь» и «назад» нового вкуса не тянут треки прошлого
        planned = null;        // и прошлый трек не попадёт в историю нового вкуса при старте
        store.save();
        ui.edit().putString("profile", id).apply();
        store = new Store(app, id);
        brain = newBrain();
        mode = store.mode();
        brain.refillIfLow(Brain.MY);
        Log.i("volna", "профиль: " + profileName(id) + ", треков во вкусе: " + store.seed.size() + ", за " + (SystemClock.elapsedRealtime() - from) + " мс");
        tellProfile();
        if (wasPlaying) start(mode);
        else notifyAll(false);
    }

    // Сказать Aura, чей сейчас профиль: от его имени пойдут её запросы, в том числе история прослушивания. Потом отослать оценки, которые ждали связи
    private void tellProfile() {
        final Store of = store;
        final long from = SystemClock.elapsedRealtime();
        ask(PROFILE, new Bundle(), o -> {
            Log.i("volna", "канал «" + channel() + "»: " + (o == null ? "нет ответа" : o.toString()) + ", " + (SystemClock.elapsedRealtime() - from) + " мс");
            if (o != null && o.optBoolean("ok") && of == store) flushRatings();
        });
    }

    private void screenOff() {
        if (driving) {
            Log.i("volna", "экран погас, волна закрыта");
            stop();
        } else if (ctl != null && auraPlaying()) {
            Log.i("volna", "экран погас, музыка из самой Aura на паузе");
            ctl.getTransportControls().pause();
        }
    }

    // Закрыть: тишина сразу, пустой плеер, все ожидания сняты. Открыть снова можно только явно: плитка, кнопка, команда
    void stop() {
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
        Log.i("volna", "закрыто");
        keep();
        notifyAll(false);
    }

    private void forgetTrack() {
        curId = null;
        curTitle = curArtist = badge = "";
        curDur = 0;
        curArt = null;
        posBase = posTime = 0;
    }

    private void silence(String why) {
        if (ctl == null) return;
        Bundle b = new Bundle();
        b.putString("why", why);
        ctl.getTransportControls().sendCustomAction(HARD_STOP, b);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void notifyAll(boolean newTrack) {
        for (Listener l : new ArrayList<>(listeners)) l.onEngine(newTrack);
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
        if (watching) return;
        try {
            MediaSessionManager m = app.getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(app, app.glasstv.volna.Listener.class);
            m.addOnActiveSessionsChangedListener(this::onSessions, me, h);
            watching = true;
            onSessions(m.getActiveSessions(me));
        } catch (Exception e) {
            Log.w("volna", "сессии не видны: " + e.getMessage());
        }
    }

    private void onSessions(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) if (AURA.equals(c.getPackageName())) { attach(c); return; }
    }

    // Подключение к службе Aura: поднимает её, если не запущена, и держит её важность вровень с «Волной»
    void connect() {
        if (connecting || (browser != null && browser.isConnected())) return;
        connecting = true;
        dropBrowser();
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

    private void dropBrowser() {
        h.removeCallbacks(connectTimeout);
        if (browser != null) {
            try { browser.disconnect(); } catch (Exception ignored) { }
            browser = null;
        }
    }

    // Подключение к службе оборвалось. Нужна музыка: подключаемся снова. Не нужна: молчим до следующей просьбы
    private void linkDown(String why) {
        Log.w("volna", why);
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
    private void sessionGone() {
        boolean resume = driving && !userPaused;
        Log.w("volna", "сессия Aura закрылась, волна " + (resume ? "продолжит" : "молчит"));
        if (ctl != null) ctl.unregisterCallback(cb);
        ctl = null;
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
            h.removeCallbacks(retry);
            h.postDelayed(retry, errorsInRow > 5 ? 60_000 : 1500L * errorsInRow);
        }
        keep();
        notifyAll(false);
    }

    private void attach(MediaController c) {
        boolean same = ctl != null && ctl.getSessionToken().equals(c.getSessionToken());
        if (!same) {
            if (ctl != null) ctl.unregisterCallback(cb);
            ctl = c;
            c.registerCallback(cb, h);
            status = "";
            onMeta(c.getMetadata());
            onState(c.getPlaybackState());
            tellProfile();
        }
        // «Волну» перезапустили, а Aura осталась играть: это музыка без хозяина, она замолкает
        if (startup && !driving && auraPlaying()) {
            Log.i("volna", "Aura играла без волны после перезапуска, закрываю");
            silence("волна перезапущена");
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
        MediaController c = ctl;
        if (c == null) { if (a != null) a.on(null); return; }
        args.putString("profile", channel());
        final boolean[] done = {false};
        final Runnable late = () -> { if (!done[0]) { done[0] = true; if (a != null) a.on(null); } };
        h.postDelayed(late, ASK_MS);
        c.sendCommand(action, args, new ResultReceiver(h) {
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
    private List<Track> feed(String kind, String seed) {
        final AtomicReference<JSONObject> answer = new AtomicReference<>();
        final CountDownLatch got = new CountDownLatch(1);
        h.post(() -> {
            Bundle b = new Bundle();
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
            Log.i("volna", "подборка " + kind + ": " + (o == null ? "нет ответа" : o.optString("err")));
            return null;
        }
        JSONArray a = o.optJSONArray("items");
        List<Track> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            out.add(new Track(t.optString("id"), t.optString("t"), t.optString("a"), t.optLong("d") * 1000));
        }
        Log.i("volna", "подборка " + kind + " канала «" + channel() + "»: " + out.size());
        return out;
    }

    // Кто сейчас в аккаунте, для проверки с ПК: --es do whoami
    void whoami() {
        ask(WHOAMI, new Bundle(), o -> Log.i("volna", "вход: канал «" + channel() + "», ответ " + o));
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
            Log.i("volna", "оценка " + value + " для " + id + ": " + (ok ? "в аккаунте" : o == null ? "нет связи, подождёт" : o.optString("err")));
            if (ok) of.ratingDone(id, value);
        });
    }

    private void flushRatings() {
        for (java.util.Map.Entry<String, Integer> e : store.pending.entrySet()) rate(e.getKey(), e.getValue());
    }

    private void onMeta(MediaMetadata md) {
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
            if (art != null && art != curArt) { curArt = art; TrackToast.refreshArt(this); changed = true; }
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
            Log.i("volna", "в Aura выбрали другой трек, волна отходит в сторону");
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
            Log.i("volna", "трек: «" + title + "» за " + (now - switchAt) + " мс");
            switching = false;
            h.removeCallbacks(switchTimeout);
            h.removeCallbacks(sendPlanned);
            badge = planned.badge;
            store.played(planned);
            store.save();
            // Нажали паузу, пока трек загружался
            if (userPaused && ctl != null) ctl.getTransportControls().pause();
            h.removeCallbacks(preloadAhead);
            h.postDelayed(preloadAhead, PRELOAD_AFTER_MS);
        } else {
            badge = "";
        }
        status = "";
        scheduleEnd();
        notifyAll(true);
        if (uiVisible == 0 && driving && !userPaused) TrackToast.show(app, this);
    }

    // Пока играет трек, Aura заранее узнаёт адрес потока следующего: переключение почти мгновенное
    private final Runnable preloadAhead = new Runnable() {
        @Override public void run() {
            if (!driving || switching || ctl == null) return;
            List<Track> next = brain.upcoming(mode, 1);
            if (next.isEmpty()) return;
            Bundle b = new Bundle();
            b.putString("id", next.get(0).id);
            ctl.getTransportControls().sendCustomAction(PRELOAD, b);
        }
    };

    // Взвести таймер конца трека
    private void scheduleEnd() {
        h.removeCallbacks(endOfTrack);
        if (driving && state == PlaybackState.STATE_PLAYING && curDur > 0 && !switching)
            h.postDelayed(endOfTrack, Math.max(200, curDur - position() - END_GAP_MS));
    }

    private void onState(PlaybackState st) {
        if (st == null) return;
        int prev = state;
        state = st.getState();
        posBase = st.getPosition();
        posTime = st.getLastPositionUpdateTime();
        speed = st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed();
        lastStateAt = SystemClock.elapsedRealtime();
        stateTicks++;
        if (driving && switching && userPaused && auraPlaying() && ctl != null) ctl.getTransportControls().pause();
        // Трек не смог заиграть: следующий. Раньше 600 мс это ещё старая ошибка прошлого трека, плеер сообщает её до новой загрузки
        if (driving && state == PlaybackState.STATE_ERROR && prev != PlaybackState.STATE_ERROR && lastStateAt - lastSendAt > 600) {
            errorsInRow++;
            Log.w("volna", "ошибка плеера на «" + curTitle + "»: " + st.getErrorMessage() + ", подряд " + errorsInRow);
            status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "";
            if (position() < 1000) suspect = curId;
            switching = false;
            h.removeCallbacks(switchTimeout);
            h.removeCallbacks(retry);
            h.postDelayed(retry, errorsInRow > 5 ? 60_000 : 300L * errorsInRow);
        }
        if (driving && !switching) {
            // Пульт или фильм, забравший звук, тоже меняют паузу: волна следует за Aura
            if (state == PlaybackState.STATE_PAUSED) userPaused = true;
            else if (state == PlaybackState.STATE_PLAYING) userPaused = false;
            // Счётчик ошибок обнуляется, только когда трек реально заиграл. Сеть жива, значит трек перед ним YouTube больше не отдаёт: из волны его убираем
            if (state == PlaybackState.STATE_PLAYING && position() > 3000) {
                errorsInRow = 0;
                if (suspect != null && !suspect.equals(curId)) {
                    Log.i("volna", "трек " + suspect + " недоступен, в волну больше не попадёт");
                    store.markDead(suspect);
                }
                suspect = null;
            }
            if ((state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE)
                    && prev == PlaybackState.STATE_PLAYING && curId != null) {
                Log.i("volna", "трек кончился на " + position() / 1000 + " с из " + curDur / 1000);
                playNext();   // трек кончился, а наш таймер не успел
                return;
            }
        }
        scheduleEnd();
        keep();
        notifyAll(false);
    }

    private final Runnable retry = () -> { if (driving) playNext(); };

    // ===== Важность Aura: служба работает, пока музыка нужна, и ещё немного после =====

    private boolean musicWanted() { return switching || (driving && !userPaused) || auraPlaying(); }

    private void keep() {
        if (musicWanted()) {
            h.removeCallbacks(release);
            if (!keeperOn) {
                keeperOn = true;
                Keeper.on(app);
                Log.i("volna", "служба включена");
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
        Keeper.off(Engine.this.app);
        Log.i("volna", "служба выключена");
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
            String why = stallReason(now);
            if (why == null) { stallSince = 0; return; }
            if (stallSince == 0) stallSince = now;
            if (now - stallSince >= STALL_MS) {
                Log.w("volna", "сторож: " + why + " («" + curTitle + "», " + position() / 1000 + " с)");
                stallSince = 0;
                fakeStall = false;
                errorsInRow++;
                playNext();
            }
        }
    };

    private String stallReason(long now) {
        if (fakeStall) return "проверка с ПК";
        if (switching || userPaused || ctl == null) return null;
        if (state == PlaybackState.STATE_BUFFERING) return "загрузка стоит на одном месте";
        if (state == PlaybackState.STATE_ERROR) return "плеер стоит с ошибкой";
        // Пока музыка идёт, Aura сообщает о себе каждые 3 с
        if (state == PlaybackState.STATE_PLAYING && stateTicks >= 3 && now - lastStateAt > SILENT_MS) return "Aura молчит";
        return null;
    }

    // Связь с Aura с чистого листа: от повторных неудач подряд
    void restartAura(String why) {
        Log.w("volna", "перезапуск связи с Aura: " + why);
        errorsInRow++;
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(sendPlanned);
        MediaController c = ctl;
        if (c != null) {
            try { c.getTransportControls().stop(); } catch (Exception ignored) { }
            c.unregisterCallback(cb);
        }
        ctl = null;
        connecting = false;
        dropBrowser();
        state = PlaybackState.STATE_NONE;
        switching = false;
        status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "Aura зависла, перезапускаю";
        notifyAll(false);
        h.removeCallbacks(retry);
        h.postDelayed(retry, errorsInRow > 5 ? 60_000 : 1500);
    }

    // У самого конца трека просим следующий: адрес его потока уже готов, поэтому пауза между песнями короткая
    private final Runnable endOfTrack = new Runnable() {
        @Override public void run() {
            if (!driving || switching || state != PlaybackState.STATE_PLAYING) return;
            long remain = curDur - position();
            if (remain > END_GAP_MS + 500) { h.postDelayed(this, remain - END_GAP_MS); return; }
            Log.i("volna", "конец трека: позиция " + position() / 1000 + " с из " + curDur / 1000);
            Track t = brain.next(mode);
            if (t == null) return;   // подбор пуст: конец трека обработает onState
            rememberCurrent();
            store.played(t);
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
        // Вкус профиля ещё не загружен (нет её плейлистов): «Моей волне» не из чего собираться
        if (m == Brain.MY && store.empty()) m = Brain.NEW;
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
        // Сразу в историю: иначе подбор успел бы выбрать этот же трек ещё раз, пока Aura его грузит
        store.played(t);
        request(t);
        notifyAll(true);   // экраны сразу показывают новый трек, не дожидаясь Aura
    }

    // Пока Aura готовит наш трек, экраны уже показывают его, а не прошлый: смена вкуса и «дальше» видны сразу
    // Пока подбор нового (вкус сменили на «Новое») ищет первый трек, экраны уже чистые, без прошлого трека
    boolean waiting() { return driving && waitingForBrain; }

    boolean pending() { return driving && !waitingForBrain && switching && planned != null && !planned.id.equals(curId); }

    String shownId() { return waiting() ? null : pending() ? planned.id : curId; }

    String shownTitle() { return waiting() ? "" : pending() ? planned.title : curTitle; }

    String shownArtist() { return waiting() ? "" : pending() ? planned.artist : curArtist; }

    String shownBadge() { return waiting() ? "" : pending() ? planned.badge : badge; }

    Bitmap shownArt() { return waiting() || pending() ? null : curArt; }

    long shownDur() { return waiting() ? 0 : pending() ? planned.durationMs : curDur; }

    // ===== Назад, перемотка, выбранный трек =====

    // Что уже отыграло в этой волне: для кнопки «назад»
    private final java.util.ArrayDeque<Track> history = new java.util.ArrayDeque<>();

    // Текущий трек в историю, только если он действительно заиграл
    private void rememberCurrent() {
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
        if (position() > 5000 || history.isEmpty()) { seekTo(0); return; }
        Track t = history.pollLast();
        play(t, false);
    }

    void seekBy(long ms) { seekTo(position() + ms); }

    void seekTo(long ms) {
        if (ctl == null || curDur <= 0) return;
        long to = Math.max(0, Math.min(curDur - 1500, ms));
        ctl.getTransportControls().seekTo(to);
        posBase = to;
        posTime = SystemClock.elapsedRealtime();
        // Перемотали ближе к концу: таймер стыка пересчитать от нового места
        h.removeCallbacks(endOfTrack);
        h.postDelayed(endOfTrack, 500);
        notifyAll(false);
    }

    // Включить выбранный трек (из списка «Треки» или «назад»); после него волна идёт дальше как обычно
    void play(Track chosen, boolean remember) {
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
        store.played(t);
        request(t);
        notifyAll(true);
    }

    // Хочу, чтобы играл этот трек. Первое нажатие уходит сразу, серия сводится к последнему
    private void request(Track t) {
        planned = t;
        switching = true;
        switchAt = SystemClock.elapsedRealtime();
        userPaused = false;
        status = "";
        h.removeCallbacks(switchTimeout);
        h.postDelayed(switchTimeout, SWITCH_TIMEOUT_MS);
        keep();
        if (ctl == null) return;   // уйдёт сразу после подключения (attach)
        // Тот же трек уже в Aura: достаточно вернуть его в начало
        if (t.id.equals(curId)) {
            switching = false;
            h.removeCallbacks(switchTimeout);
            badge = t.badge;
            ctl.getTransportControls().seekTo(0);
            ctl.getTransportControls().play();
            return;
        }
        long wait = BURST_MS - (switchAt - lastSendAt);
        h.removeCallbacks(sendPlanned);
        if (wait <= 0) sendPlanned.run();
        else h.postDelayed(sendPlanned, wait);
    }

    private final Runnable sendPlanned = new Runnable() {
        @Override public void run() {
            if (!switching || !driving || ctl == null || planned == null) return;
            h.removeCallbacks(this);
            lastSendAt = SystemClock.elapsedRealtime();
            Log.i("volna", "команда: «" + planned.title + "», " + planned.id);
            ctl.getTransportControls().playFromMediaId(mediaId(planned), null);
        }
    };

    // Команда своей сборке Aura: трек по точному id. Название и исполнитель сразу видны на плашке, длительность для таймера конца
    static String mediaId(Track t) {
        return "id/" + t.id + "/" + Uri.encode(t.title) + "/" + Uri.encode(t.artist) + "/" + t.durationMs;
    }

    // Aura за 15 секунд не включила трек: берём следующий, с третьего раза подряд подключаемся заново;
    // при долгой беде пробуем раз в минуту
    private final Runnable switchTimeout = () -> {
        if (!switching || !driving) return;
        errorsInRow++;
        Log.w("volna", "Aura не включила «" + (planned == null ? "" : planned.title) + "» за 15 с, ошибок подряд " + errorsInRow);
        if (errorsInRow <= 2) { playNext(); return; }
        if (errorsInRow <= 5) { restartAura("Aura не включила трек за 15 с"); return; }
        switching = false;
        status = "Нет связи с Aura или YouTube, пробую снова";
        notifyAll(false);
        h.removeCallbacks(retry);
        h.postDelayed(retry, 60_000);
    };

    void toggle() {
        if (!driving) { start(mode); return; }
        if (!userPaused) {
            userPaused = true;
            if (ctl != null) ctl.getTransportControls().pause();
        } else {
            userPaused = false;
            if (switching) { /* трек ещё загружается: сам заиграет */ }
            else if (ctl == null || curId == null || planned == null || state == PlaybackState.STATE_NONE
                    || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) playNext();
            else ctl.getTransportControls().play();
        }
        keep();
        notifyAll(false);
    }

    void pause() { if (driving && !userPaused) toggle(); }

    void seekNearEnd() { if (ctl != null && curDur > 8000) ctl.getTransportControls().seekTo(curDur - 6000); }

    void next() {
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
