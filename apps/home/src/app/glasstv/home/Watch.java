package app.glasstv.home;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Что смотрели. Слушает медиасессии SmartTube, Just Player и VK Видео и помнит, где остановились: одна запись в prefs
// «watch» (см. Last), по ней капсула «Продолжить» возвращает на место. Уведомления сами не нужны: доступ к ним
// нужен только затем, чтобы система показала чужие сессии (MediaSessionManager.getActiveSessions).
// Включается один раз с ПК: cmd notification allow_listener app.glasstv.home/app.glasstv.home.Watch
// Работает в главном потоке, но почти ничего не делает: пара мелких запросов к сессии при паузе и раз в 30 с во время игры.
// Ссылок на экраны не держит
public class Watch extends NotificationListenerService {
    private static final String TAG = Usage.TAG;
    private static final long TICK_MS = 30_000;   // как часто сохранять позицию, пока что-то играет
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Watch live;   // живой слушатель: через него Go запускает воспроизведение в плеере

    private MediaSessionManager msm;
    private ComponentName me;
    private final Map<MediaSession.Token, Track> tracks = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::sync;

    // ===== Подключение =====

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        live = this;
        me = new ComponentName(this, Watch.class);
        msm = getSystemService(MediaSessionManager.class);
        try {
            msm.removeOnActiveSessionsChangedListener(onSessions);
            msm.addOnActiveSessionsChangedListener(onSessions, me);
            sync(msm.getActiveSessions(me));
            Log.i(TAG, "слушатель подключён, сессий под наблюдением: " + tracks.size());
        } catch (Exception e) {
            Log.w(TAG, "на медиасессии подписаться не вышло: " + e);
        }
    }

    @Override public void onListenerDisconnected() {
        disconnect();
        Log.i(TAG, "слушатель отключён");
        super.onListenerDisconnected();
        try { requestRebind(new ComponentName(this, Watch.class)); } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        if (live == this) live = null;
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(onSessions); } catch (Exception ignored) { }
        for (Track t : new ArrayList<>(tracks.values())) t.stop();
        tracks.clear();
    }

    private static boolean watched(String pkg) {
        return Last.ST.equals(pkg) || Last.JP.equals(pkg) || Last.VK.equals(pkg);
    }

    // Список живых сессий изменился: новые берём под наблюдение, ушедшие дописываем в запись и отпускаем
    private void sync(List<MediaController> list) {
        Set<MediaSession.Token> alive = new HashSet<>();
        if (list != null) {
            for (MediaController mc : list) {
                if (!watched(mc.getPackageName())) continue;
                MediaSession.Token tk = mc.getSessionToken();
                alive.add(tk);
                if (tracks.containsKey(tk)) continue;
                Track t = new Track(mc);
                tracks.put(tk, t);
                t.start();
                Log.i(TAG, "сессия: " + t.pkg + " взята под наблюдение");
            }
        }
        for (Iterator<Map.Entry<MediaSession.Token, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MediaSession.Token, Track> en = it.next();
            if (alive.contains(en.getKey())) continue;
            it.remove();
            en.getValue().stop();
            Log.i(TAG, "сессия: " + en.getValue().pkg + " ушла");
        }
    }

    // ===== Воспроизведение после запуска плеера =====

    // «Продолжить» на файле: Just Player сам открывает свой последний файл на сохранённой секунде, но на паузе.
    // Ждём, пока появится его сессия (1,5, 2,25 и 3 с после запуска), и жмём «играть», но только если в ней тот самый
    // файл из записи: «последний файл» плеера мог смениться (на секунду открыли другой), и тогда он сам включил бы чужой.
    // Не нашли или файл другой: ничего не жмём, плеер остаётся на паузе, и на экране виден тот файл, что в нём открылся
    static void playSoon(String pkg, String title) {
        final boolean[] done = {false};
        for (long d : new long[]{1500, 2250, 3000}) {
            MAIN.postDelayed(() -> {
                Watch w = live;
                if (!done[0] && w != null) done[0] = w.play(pkg, title);
            }, d);
        }
    }

    // true: дело сделано (нажали «играть» или уже играет), больше пробовать не надо
    private boolean play(String pkg, String title) {
        try {
            for (MediaController mc : msm.getActiveSessions(me)) {
                if (!pkg.equals(mc.getPackageName())) continue;
                MediaMetadata m = mc.getMetadata();
                String now = m == null ? "" : titleOf(m);
                // Название ещё не доехало или файл другой: не жмём, на следующей попытке посмотрим снова
                if (title.isEmpty() || now.isEmpty() || !now.equals(title)) {
                    Log.i(TAG, "продолжить: в " + pkg + " открыто «" + now + "», в записи «" + title + "», «играть» не нажато");
                    continue;
                }
                PlaybackState s = mc.getPlaybackState();
                if (s == null || s.getState() != PlaybackState.STATE_PLAYING) mc.getTransportControls().play();
                Log.i(TAG, "продолжить: " + pkg + " «" + now + "» нажато «играть»");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "сессию плеера найти не вышло: " + e);
        }
        return false;
    }

    // adb shell dumpsys activity service app.glasstv.home/.Watch
    @Override protected void dump(FileDescriptor fd, PrintWriter w, String[] args) {
        w.println("Слушатель подключён: " + (live == this) + ", сессий под наблюдением: " + tracks.size());
        for (Track t : tracks.values())
            w.println("  " + t.pkg + " «" + t.title + "» id=" + t.id + " позиция " + t.pos / 1000 + " из " + t.dur / 1000
                    + " с, идёт: " + t.playing + ", из Lampa: " + t.lampa);
        Last.Rec r = Last.read(this);
        w.println("Запись: " + (r == null ? "нет" : r));
        Last.Rec p = Last.pick(this);
        w.println("Капсула покажет: " + (p == null ? "ничего (скрыта)" : p + " -> " + String.join(" | ", Last.caption(p))));
        Usage.dump(w);
    }

    // ===== Одна медиасессия =====

    private final class Track extends MediaController.Callback {
        final MediaController mc;
        final String pkg;
        String title = "", id = "";
        long dur, pos = -1, at;   // pos: позиция в мс на момент at (elapsedRealtime), -1: ещё не знаем
        boolean playing, asked;
        boolean dirty;            // сессия сообщила об изменении, а в запись это ещё не попало
        Boolean lampa;            // Just Player: запущен из Lampa (null: ещё не выясняли)
        long started = System.currentTimeMillis();
        final Runnable late = this::changed;
        final Runnable tick = new Runnable() {
            @Override public void run() {
                save();
                if (playing) MAIN.postDelayed(this, TICK_MS);
            }
        };

        Track(MediaController mc) {
            this.mc = mc;
            this.pkg = mc.getPackageName();
        }

        // Берём сессию под наблюдение: только узнаём, что в ней сейчас. Писать можно, только если она играет. Сессия могла давно
        // стоять на паузе (SmartTube держит её, пока жив плеер), а запись «сейчас» подняла бы её над тем, что смотрели позже
        void start() {
            mc.registerCallback(this, MAIN);
            readMeta();
            readState();
            if (playing) changed();
        }

        // Сессия ушла: последняя запись по тому, что знаем. Пишем, только если она играла (позиция дотягивается до этой минуты)
        // или сообщила об изменении, которое ещё не записано. Стоявшую на паузе не трогаем: её пауза уже записана, а свежее
        // «сейчас» перебило бы то, что смотрели позже
        void stop() {
            MAIN.removeCallbacks(tick);
            MAIN.removeCallbacks(late);
            try { mc.unregisterCallback(this); } catch (Exception ignored) { }
            if (playing || dirty) save();
        }

        @Override public void onPlaybackStateChanged(PlaybackState s) {
            dirty = true;
            changed();
        }

        // Новый ролик приходит раньше своей позиции: даём состоянию догнать, иначе название нового встанет к старой секунде
        @Override public void onMetadataChanged(MediaMetadata m) {
            dirty = true;
            readMeta();
            MAIN.removeCallbacks(late);
            MAIN.postDelayed(late, 1500);
        }

        @Override public void onSessionDestroyed() {
            Log.i(TAG, "сессия " + pkg + " закрыта");
            stop();
            tracks.values().remove(this);
        }

        void changed() {
            save();
            MAIN.removeCallbacks(tick);
            if (playing) MAIN.postDelayed(tick, TICK_MS);
        }

        void readMeta() {
            MediaMetadata m = mc.getMetadata();
            if (m == null) return;
            String t = titleOf(m);
            String i = nz(m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            // SmartTube на паузе и при выходе оставляет в сессии только id, без названия (капсула писала «YouTube»):
            // тот же ролик, если совпал id или название, а пустое поле берём прежнее
            if (t.isEmpty() && !i.isEmpty() && i.equals(id)) t = title;
            if (i.isEmpty() && !t.isEmpty() && t.equals(title)) i = id;
            if (!t.equals(title) || !i.equals(id)) {
                // Другой ролик в той же сессии: про позицию и происхождение прежнего забываем
                if (!title.isEmpty() || !id.isEmpty()) {
                    pos = -1;
                    asked = false;
                    lampa = null;
                    started = System.currentTimeMillis();
                }
                title = t;
                id = i;
            }
            long d = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (d > 0 || dur <= 0) dur = d;
        }

        // Позиция: то, что сообщила сессия, плюс сколько прошло с её отметки, если идёт воспроизведение.
        // Остановка, ошибка или пропавшая сессия позицию не обнуляют: берём последнюю известную
        void readState() {
            PlaybackState s = mc.getPlaybackState();
            long now = SystemClock.elapsedRealtime();
            int k = s == null ? PlaybackState.STATE_NONE : s.getState();
            if (k == PlaybackState.STATE_PLAYING || k == PlaybackState.STATE_PAUSED || k == PlaybackState.STATE_BUFFERING
                    || k == PlaybackState.STATE_FAST_FORWARDING || k == PlaybackState.STATE_REWINDING) {
                boolean run = k == PlaybackState.STATE_PLAYING || k == PlaybackState.STATE_FAST_FORWARDING || k == PlaybackState.STATE_REWINDING;
                long p = s.getPosition();
                long mark = s.getLastPositionUpdateTime();
                if (run && p >= 0 && mark > 0) p += (long) ((now - mark) * Math.max(0f, s.getPlaybackSpeed()));
                if (p >= 0) pos = dur > 0 ? Math.min(p, dur) : p;
                playing = run;
            } else {
                if (playing && pos >= 0) pos += now - at;
                playing = false;
            }
            at = now;
        }

        // Записываем, если это настоящий просмотр
        void save() {
            dirty = false;
            readMeta();
            readState();
            if (pos < 0 || !eligible()) return;
            // Меньше минуты это не просмотр: заглянули на секунду в чужое видео или плеер ещё не доехал до сохранённой
            // секунды (открыли тот же ролик заново), а вчерашний фильм остался. Запись не трогаем
            if (pos < Last.MIN_POS) return;
            Last.Rec old = Last.read(Watch.this);
            boolean same = old != null && old.same(pkg, id, title);
            if (Last.JP.equals(pkg) && !same && !asked) {
                // Просмотр из Lampa или файл с диска? Выясняем в фоне один раз на ролик, ответ допишет запись
                asked = true;
                final String tt = title, ii = id;
                Usage.cameFromLampa(Watch.this, started, from -> MAIN.post(() -> {
                    if (!title.equals(tt) || !id.equals(ii)) return;
                    lampa = from;
                    save();
                }));
            }
            String kind = Last.ST.equals(pkg) ? Last.K_ST : Last.VK.equals(pkg) ? Last.K_VK
                    : (same && Last.K_LAMPA.equals(old.kind)) || Boolean.TRUE.equals(lampa) ? Last.K_LAMPA : Last.K_FILE;
            Last.Rec r = new Last.Rec(pkg, kind, title, id, pos, dur, System.currentTimeMillis());
            Last.write(Watch.this, r);
            Log.i(TAG, "запись: " + r + (playing ? " (идёт)" : ""));
        }

        // Что считаем просмотром: у SmartTube не короче трёх минут и не прямой эфир (длительность неизвестна), у плеера
        // длительность известна, у VK Видео сессия не говорит ничего, кроме позиции
        boolean eligible() {
            if (Last.ST.equals(pkg)) return dur >= Last.ST_MIN;
            if (Last.JP.equals(pkg)) return dur > 0;
            return true;
        }
    }

    // Название ролика или файла так, как его видит запись (и как его потом сверяет play)
    private static String titleOf(MediaMetadata m) {
        return first(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), m.getString(MediaMetadata.METADATA_KEY_TITLE));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String first(String a, String b) { return a != null && !a.isEmpty() ? a : nz(b); }
}
