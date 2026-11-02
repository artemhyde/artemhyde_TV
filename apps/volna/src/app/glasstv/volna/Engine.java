package app.glasstv.volna;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Движок: держит связь с Aura Music и ведёт очередь «Волны».
// Звук играет Aura (её экран не показывается): в конце каждого трека «Волна» ставит её на паузу и говорит, что играть дальше.
final class Engine {
    static final String AURA = "com.auramusic.app";
    static final String AURA_SERVICE = "com.auramusic.app.playback.MusicService";
    static final String LIKE_ACTION = "TOGGLE_LIKE";

    interface Listener { void onEngine(boolean newTrack); }

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

    boolean driving;          // «Волна» ведёт очередь
    int mode;
    Track planned;            // что мы попросили Aura сыграть сейчас
    private boolean switching;
    private long switchAt;
    private boolean waitingForBrain;
    private boolean startWhenConnected;   // после подключения начать со следующего трека
    private boolean sendWhenConnected;    // после подключения отправить уже выбранный трек
    private int errorsInRow;
    private int mismatchInRow;
    private long lastEventAt;             // когда Aura последний раз что-то присылала
    private long stallSince;              // с какого момента музыка стоит
    boolean fakeStall;                    // проверка сторожа с ПК: --es do fakestall

    // Что играет сейчас (по данным Aura)
    String curId;
    String curTitle = "", curArtist = "";
    long curDur;
    Bitmap curArt;
    String badge = "";
    int state = PlaybackState.STATE_NONE;
    private long posBase, posTime;
    private float speed = 1f;
    private String likeLabel;
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
        // ТВ выключили: волна встаёт на паузу, иначе при следующем включении музыка заиграла бы сама
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context ctx, android.content.Intent i) { screenOff(); }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
    }

    private Brain newBrain() {
        Brain b = new Brain(store);
        b.ready = m -> {
            // Подбор принёс первый трек, а мы как раз стоим без музыки
            if (waitingForBrain && driving && m == mode && !switching) { waitingForBrain = false; playNext(); }
        };
        return b;
    }

    // ===== Профили: у каждого из двух свой вкус, лайки и «не моё»; имена задаёт Config =====

    String profileName(String id) { return id.isEmpty() ? Config.PROFILE1 : ui.getString("name_" + id, Config.PROFILE2); }

    void setProfile(String id) {
        if (id.equals(store.profile)) return;
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
        Log.i("volna", "профиль: " + profileName(id) + ", треков во вкусе: " + store.seed.size());
        if (wasPlaying) start(mode);
        else notifyAll(false);
    }

    private void screenOff() {
        if (!driving) return;
        Log.i("volna", "экран погас, волна на паузе");
        stop();
        status = "Волна на паузе: телевизор выключали";
        notifyAll(false);
    }

    // Остановить волну и музыку (команда «stop» без экрана)
    void stop() {
        driving = false;
        switching = false;
        waitingForBrain = false;
        startWhenConnected = false;
        sendWhenConnected = false;
        fakeStall = false;
        h.removeCallbacks(watchdog);
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(switchTimeout);
        h.removeCallbacks(retry);
        h.removeCallbacks(acceptNearest);
        h.removeCallbacks(searchAgain);
        if (ctl != null) ctl.getTransportControls().pause();
        status = "";
        notifyAll(false);
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
        try {
            app.sendBroadcast(new android.content.Intent().setClassName("app.glasstv.home", "app.glasstv.home.Now")
                    .putExtra("playing", playing() && curId != null)
                    .putExtra("title", shownTitle()).putExtra("artist", shownArtist()));
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

    void connect() {
        if (ctl != null || connecting) return;
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
                @Override public void onConnectionFailed() { if (holder[0] == browser) lost("Aura Music не отвечает"); }
                @Override public void onConnectionSuspended() { if (holder[0] == browser) lost("Связь с Aura прервалась"); }
            }, null);
            browser = holder[0];
            browser.connect();
            h.postDelayed(connectTimeout, 10_000);
        } catch (Exception e) {
            lost("Aura Music не установлена");
        }
    }

    private final Runnable connectTimeout = () -> { if (connecting) lost("Aura Music не отвечает"); };

    private void dropBrowser() {
        if (browser != null) {
            try { browser.disconnect(); } catch (Exception ignored) { }
            browser = null;
        }
    }

    private void lost(String why) {
        connecting = false;
        h.removeCallbacks(connectTimeout);
        if (ctl != null) ctl.unregisterCallback(cb);
        ctl = null;
        dropBrowser();
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(switchTimeout);
        h.removeCallbacks(retry);
        state = PlaybackState.STATE_NONE;
        // Волна шла: после переподключения продолжим со следующего трека
        if (driving) { switching = false; startWhenConnected = true; }
        status = why;
        notifyAll(false);
        h.removeCallbacks(reconnect);
        h.postDelayed(reconnect, 4000);
    }

    private final Runnable reconnect = this::connect;

    private void attach(MediaController c) {
        if (ctl != null) ctl.unregisterCallback(cb);
        ctl = c;
        c.registerCallback(cb, h);
        status = "";
        onMeta(c.getMetadata());
        onState(c.getPlaybackState());
        if (sendWhenConnected && planned != null && driving) { sendWhenConnected = false; startWhenConnected = false; send(planned); }
        else if (startWhenConnected) { startWhenConnected = false; playNext(); }
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata md) { lastEventAt = SystemClock.elapsedRealtime(); onMeta(md); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { lastEventAt = SystemClock.elapsedRealtime(); onState(st); }
        @Override public void onSessionDestroyed() { lost("Aura закрылась"); }
    };

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
        if (driving && planned != null && !id.equals(planned.id)) {
            long since = SystemClock.elapsedRealtime() - switchAt;
            if (switching) {
                // Перед командой Aura стоит на паузе, значит это ответ на нашу команду
                if (sameSong(planned, title, artist)) {
                    // Поиск нашёл ту же песню в другой версии (студийная вместо клипа): принимаем
                    planned = adopt(planned, id, title, artist, dur, planned.badge);
                } else if (since < 6000) {
                    // Возможно, это ответ на прошлую команду или первый поиск ещё не видит трек: ждём повтора
                    Log.i("volna", "Aura пока включила другое (" + title + "), жду повтора поиска");
                    // Чужой трек не должен звучать, пока ищем свой
                    if (ctl != null) ctl.getTransportControls().pause();
                    h.removeCallbacks(acceptNearest);
                    h.postDelayed(acceptNearest, 6500 - since);
                    return;
                } else {
                    // Ровно наш трек не нашёлся: играем найденное, чем листать впустую
                    planned = adopt(planned, id, title, artist, dur, "≈ Ближайшее совпадение");
                }
            } else if (since < 12_000) {
                // Запоздалый ответ на недавнюю команду: не считаем это выбором пользователя
                planned = adopt(planned, id, title, artist, dur, sameSong(planned, title, artist) ? planned.badge : "≈ Ближайшее совпадение");
            } else if (curDur > 0 && curDur - position() > 5000) {
                // Трек сменился посреди песни, и не нами: это выбор в самой Aura, волна отходит в сторону
                Log.i("volna", "в Aura выбрали другой трек, волна на паузе");
                driving = false;
                h.removeCallbacks(endOfTrack);
            } else {
                // Aura сама перешла дальше в конце трека: ставим свой следующий
                Log.i("volna", "Aura ушла на " + id + ", ставлю свой трек");
                playNext();
                return;
            }
        }
        curId = id;
        curTitle = title == null ? "" : title;
        curArtist = artist == null ? "" : artist;
        curDur = dur;
        curArt = art;
        likeLabel = null;
        if (ctl != null) readLike(ctl.getPlaybackState());
        if (driving && planned != null) {
            switching = false;
            h.removeCallbacks(switchTimeout);
            badge = planned.badge;
            mismatchInRow = 0;
            store.played(planned);   // id мог смениться, если принята другая версия той же песни
            store.save();
        } else {
            badge = "";
        }
        status = "";
        scheduleEnd();
        notifyAll(true);
        if (uiVisible == 0 && driving) TrackToast.show(app, this);
    }

    // Через 6,5 с после команды Aura так и играет не наш трек: принимаем его, чтобы музыка не стояла
    private final Runnable acceptNearest = () -> {
        if (!switching || !driving || ctl == null) return;
        MediaMetadata md = ctl.getMetadata();
        String id = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id == null || planned == null) return;
        if (!id.equals(planned.id)) {
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            planned = adopt(planned, id, title, artist, md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    sameSong(planned, title, artist) ? planned.badge : "≈ Ближайшее совпадение");
        }
        curId = null;   // заставить onMeta принять трек как новый
        onMeta(md);
        ctl.getTransportControls().play();   // на время поиска он стоял на паузе
    };

    private static Track adopt(Track from, String id, String title, String artist, long dur, String badge) {
        Track t = new Track(id, title, artist, dur);
        t.kind = from.kind;
        t.bucket = from.bucket;
        t.tags = from.tags;
        t.badge = badge;
        return t;
    }

    // Взвести таймер «за секунду до конца трека»
    private void scheduleEnd() {
        h.removeCallbacks(endOfTrack);
        if (driving && state == PlaybackState.STATE_PLAYING && curDur > 0 && !switching)
            h.postDelayed(endOfTrack, Math.max(200, curDur - position() - 900));
    }

    private void onState(PlaybackState st) {
        if (st == null) return;
        int prev = state;
        state = st.getState();
        posBase = st.getPosition();
        posTime = st.getLastPositionUpdateTime();
        speed = st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed();
        readLike(st);
        scheduleEnd();
        // Счётчик ошибок обнуляется, только когда трек реально заиграл
        if (state == PlaybackState.STATE_PLAYING && position() > 3000) errorsInRow = 0;
        if (driving && !switching) {
            if (state == PlaybackState.STATE_ERROR) {
                errorsInRow++;
                status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "";
                h.removeCallbacks(retry);
                h.postDelayed(retry, errorsInRow > 5 ? 60_000 : 1500L * errorsInRow);
            } else if ((state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE)
                    && prev == PlaybackState.STATE_PLAYING && curId != null) {
                playNext();   // очередь Aura кончилась, а наш таймер не успел
                return;
            }
        }
        notifyAll(false);
    }

    private void readLike(PlaybackState st) {
        if (st == null) return;
        for (PlaybackState.CustomAction a : st.getCustomActions())
            if (LIKE_ACTION.equals(a.getAction())) likeLabel = String.valueOf(a.getName());
    }

    private final Runnable retry = () -> { if (driving) playNext(); };

    // ===== Сторож зависания (ноябрь): 02.11 волна замерла на одном треке на 3-й минуте =====
    // Пока Aura пишет «загрузка», наш таймер конца трека не идёт и ждать можно вечно. Раз в секунду смотрим, что с музыкой.
    // Стоит дольше 7 с (в начале трека 12 с: там долго ищется ссылка) или Aura молчит после команды: её плеер сбрасывается
    // (stop и новое подключение), играет следующий трек. Убить чужой процесс Android 14 не даёт: killBackgroundProcesses
    // работает только на свои, зато умерший процесс поднимется сам при подключении (на TCL нужны AUTO_START и ASSOC_START,
    // иначе прошивка режет запуск: forbid bind service, так волна и замерла).
    // Пять неудач подряд: повтор раз в минуту, чтобы без интернета не дёргать Aura по кругу
    static final long STALL_MS = 7_000, STALL_START_MS = 12_000;

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
            String why = ctl == null ? null : stallReason(now);
            if (why == null) { stallSince = 0; return; }
            if (stallSince == 0) stallSince = now;
            if (now - stallSince >= (position() < 1000 ? STALL_START_MS : STALL_MS)) restartAura(why);
        }
    };

    private String stallReason(long now) {
        if (fakeStall) return "проверка с ПК";
        if (switching) return lastEventAt < switchAt ? "Aura не отвечает на команду" : null;   // ответила, но ищет: это ведёт switchTimeout
        if (state == PlaybackState.STATE_BUFFERING) return "загрузка стоит на одном месте";
        if (state == PlaybackState.STATE_PLAYING && curId != null && !audioActive()) return "Aura пишет «играет», а звука нет";
        return null;
    }

    boolean audioActive() {
        AudioManager am = app.getSystemService(AudioManager.class);
        return am == null || am.isMusicActive();
    }

    void restartAura(String why) {
        Log.w("volna", "сторож: " + why + " («" + curTitle + "», " + position() / 1000 + " с), перезапускаю Aura");
        errorsInRow++;
        stallSince = 0;
        fakeStall = false;
        switching = false;
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(switchTimeout);
        h.removeCallbacks(retry);
        h.removeCallbacks(acceptNearest);
        h.removeCallbacks(searchAgain);
        h.removeCallbacks(connectTimeout);
        h.removeCallbacks(reconnect);
        MediaController c = ctl;
        if (c != null) {
            try { c.getTransportControls().stop(); } catch (Exception ignored) { }
            c.unregisterCallback(cb);
        }
        ctl = null;
        connecting = false;
        dropBrowser();
        state = PlaybackState.STATE_NONE;
        startWhenConnected = true;   // после подключения сразу следующий трек
        sendWhenConnected = false;
        status = errorsInRow > 5 ? "Aura не может играть, проверь интернет" : "Aura зависла, перезапускаю";
        notifyAll(false);
        h.postDelayed(reconnect, errorsInRow > 5 ? 60_000 : 1500);
    }

    // За секунду до конца трека сами ставим следующий, чтобы Aura не успела включить своё
    private final Runnable endOfTrack = new Runnable() {
        @Override public void run() {
            if (!driving || switching || state != PlaybackState.STATE_PLAYING) return;
            long remain = curDur - position();
            if (remain <= 1600) playNext();
            else h.postDelayed(this, Math.max(200, remain - 900));
        }
    };

    long position() {
        if (state == PlaybackState.STATE_PLAYING && posTime > 0)
            return Math.min(curDur > 0 ? curDur : Long.MAX_VALUE, posBase + (long) ((SystemClock.elapsedRealtime() - posTime) * speed));
        return posBase;
    }

    boolean playing() { return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING; }

    // ===== Команды =====

    // Запустить волну (или переключиться на другую): сразу играет первый трек
    void start(int m) {
        // Вкус профиля ещё не загружен (нет её плейлистов): «Моей волне» не из чего собираться
        if (m == Brain.MY && store.empty()) m = Brain.NEW;
        mode = m;
        store.mode(m);
        driving = true;
        waitingForBrain = false;
        errorsInRow = 0;
        mismatchInRow = 0;
        watch();
        playNext();
    }

    void playNext() {
        h.removeCallbacks(endOfTrack);
        h.removeCallbacks(retry);
        h.removeCallbacks(acceptNearest);
        Track t = brain.next(mode);
        if (t == null) {
            waitingForBrain = true;
            status = Net.down() ? "Нет связи с YouTube Music, пробую снова" : "Подбираю новое для вас…";
            notifyAll(false);
            return;
        }
        waitingForBrain = false;
        rememberCurrent();
        planned = t;
        switching = true;
        switchAt = SystemClock.elapsedRealtime();
        // Сразу в историю: иначе подбор успел бы выбрать этот же трек ещё раз, пока Aura его ищет
        store.played(t);
        send(t);
        notifyAll(true);   // экраны сразу показывают новый трек, не дожидаясь, пока Aura его найдёт
    }

    // Пока Aura ищет наш трек, экраны уже показывают его, а не прошлый: смена вкуса и «дальше» видны сразу
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

    // Текущий трек в историю, только если он действительно заиграл (а не промахнулся поиском)
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
        h.removeCallbacks(acceptNearest);
        if (remember) rememberCurrent();
        brain.drop(chosen.id);
        driving = true;
        waitingForBrain = false;
        watch();
        Track t = new Track(chosen.id, chosen.title, chosen.artist, chosen.durationMs);
        t.kind = Track.FAMILIAR;
        t.badge = chosen.badge.isEmpty() ? "▶ Вы выбрали" : chosen.badge;
        t.playlist = chosen.playlist;
        planned = t;
        switching = true;
        switchAt = SystemClock.elapsedRealtime();
        store.played(t);
        send(t);
        notifyAll(true);
    }

    // Отдать Aura команду на трек. Если связи ещё нет, тот же трек уйдёт сразу после подключения
    private void send(Track t) {
        h.removeCallbacks(acceptNearest);
        h.removeCallbacks(switchTimeout);
        h.postDelayed(switchTimeout, 15_000);
        if (ctl == null) { sendWhenConnected = true; connect(); return; }
        switchAt = SystemClock.elapsedRealtime();
        // Пауза до команды: пока Aura ищет наш трек, она не успеет сама перейти на свой
        if (playing()) ctl.getTransportControls().pause();
        // Команда Aura без её экрана: трек из публичной подборки включается точно, остальные через поиск по названию
        String mid = mediaId(t);
        ctl.getTransportControls().playFromMediaId(mid, null);
        // Aura кладёт найденное в свою базу в фоне и с первого раза его не видит: повторяем поиск
        if (mid.startsWith("search/")) {
            h.removeCallbacks(searchAgain);
            searchAgainId = mid;
            h.postDelayed(searchAgain, 1500);
            h.postDelayed(searchAgain, 4000);
        }
        status = "";
        notifyAll(false);
    }

    private String searchAgainId;
    private final Runnable searchAgain = () -> {
        if (switching && driving && ctl != null && planned != null && mediaId(planned).equals(searchAgainId))
            ctl.getTransportControls().playFromMediaId(searchAgainId, null);
    };

    static String mediaId(Track t) {
        if (t.playlist != null && !t.playlist.isEmpty()) return "youtube_playlist/" + t.playlist + "/" + t.id;
        String first = t.artists().isEmpty() ? "" : t.artist.split("\\s*(?:&|,| x | feat\\. )\\s*")[0];
        String q = (t.title + " " + first).replace('/', ' ').replaceAll("\\s+", " ").trim();
        if (q.length() > 90) q = q.substring(0, 90);
        return "search/" + q + "/" + t.id;
    }

    private static final java.util.Set<String> NOISE_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "slowed", "sped", "speed", "up", "reverb", "ultra", "super", "mega", "extreme", "over", "totally", "version",
            "remix", "edit", "mix", "feat", "ft", "prod", "official", "audio", "video", "music", "lyric", "lyrics",
            "visualiser", "visualizer", "instrumental", "the", "a", "and", "x", "by"));

    // Та же песня: большинство значимых слов названия совпадает (пометки slowed, remix, official audio не в счёт)
    // и хотя бы один исполнитель встречается у другого трека в исполнителях или в названии
    static boolean sameSong(Track want, String title, String artist) {
        java.util.Set<String> a = words(want.title), b = words(title == null ? "" : title);
        a.removeAll(words(want.artist));
        b.removeAll(words(artist == null ? "" : artist));
        if (a.isEmpty() || b.isEmpty()) { a = words(want.title); b = words(title == null ? "" : title); }
        if (a.isEmpty() || b.isEmpty()) return false;
        int common = 0;
        for (String w : a) if (b.contains(w)) common++;
        if (common < Math.max(1, Math.round(0.6f * Math.min(a.size(), b.size())))) return false;
        String got = ((artist == null ? "" : artist) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        for (String w : want.artists()) if (got.contains(w)) return true;
        String wantAll = (want.artist + " " + want.title).toLowerCase(Locale.ROOT);
        for (String g : new Track("", "", artist == null ? "" : artist, 0).artists()) if (wantAll.contains(g)) return true;
        return false;
    }

    private static java.util.Set<String> words(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            if (!w.isEmpty() && !NOISE_WORDS.contains(w)) out.add(w);
        return out;
    }

    // Если Aura за 15 секунд так и не включила трек, берём следующий, со второго раза подряд перезапускаем её;
    // при долгой беде пробуем раз в минуту
    private final Runnable switchTimeout = () -> {
        if (!switching || !driving) return;
        errorsInRow++;
        if (errorsInRow == 1) { playNext(); return; }
        if (errorsInRow <= 5) { restartAura("Aura не включила трек за 15 с"); return; }
        switching = false;
        status = "Нет связи с Aura или YouTube, пробую снова";
        notifyAll(false);
        h.removeCallbacks(retry);
        h.postDelayed(retry, 60_000);
    };

    void toggle() {
        if (!driving) { start(mode); return; }
        if (ctl == null) { startWhenConnected = true; connect(); return; }
        if (playing()) ctl.getTransportControls().pause();
        else if (curId == null || planned == null || state == PlaybackState.STATE_NONE
                || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) playNext();
        else ctl.getTransportControls().play();
    }

    void pause() { if (ctl != null && playing()) ctl.getTransportControls().pause(); }

    void seekNearEnd() { if (ctl != null && curDur > 8000) ctl.getTransportControls().seekTo(curDur - 6000); }

    void next() {
        if (!driving) start(mode);
        else playNext();
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

    boolean liked() {
        if (curId == null) return false;
        if (store.liked.contains(curId)) return true;
        return store.profile.isEmpty() && auraLiked();   // лайк в Aura принадлежит аккаунту первого профиля
    }

    // Aura сама подписывает кнопку лайка, и только по подписи видно, стоит ли лайк у неё
    private boolean auraLiked() {
        return likeLabel != null && (likeLabel.contains("Убрать") || likeLabel.toLowerCase(Locale.ROOT).contains("remove") || likeLabel.contains("Не нрав"));
    }

    // Единственное место, откуда лайк уходит в Aura, а из неё (если выполнен вход) в аккаунт YouTube Music. Только первый профиль:
    // лайки второго остаются в его собственной памяти и с библиотекой аккаунта не смешиваются
    private void auraLike(boolean want) {
        if (!store.profile.isEmpty() || ctl == null || likeLabel == null || auraLiked() == want) return;
        ctl.getTransportControls().sendCustomAction(LIKE_ACTION, null);
    }

    // Сердце: поставить лайк, а на треке с лайком снять его
    void like() {
        if (curId == null) return;
        Track t = current();
        if (liked()) {
            auraLike(false);
            store.unlike(t);
        } else {
            auraLike(true);
            store.like(t);
            brain.onLike(t);
        }
        notifyAll(false);
    }

    // «Не моё»: снять лайк, если он был, в блок-лист, вес направления вниз, сразу следующий
    void dislike() {
        if (curId == null) { next(); return; }
        Track t = current();
        auraLike(false);
        store.dislike(t);
        brain.onDislike(t);
        next();
    }
}
