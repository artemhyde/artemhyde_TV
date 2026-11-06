package app.glasstv.volna;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// Стенд «Волны»: второй, тихий движок в том же процессе. Поддельный источник подборок (задержки 0,5/3/15 с, пусто, ошибка)
// и поддельный плеер вместо Aura: ни звука, ни запросов YouTube, настоящая музыка и память профилей не задеты.
// Проверяет внешние правила, а не код: последнее нажатие побеждает, после «Стоп» нет звука, профили не смешиваются,
// экран и звук показывают один трек, потоки и память не растут.
// Запуск: am broadcast -n app.glasstv.volna/.Cmd --es do bench --es suite modes|keys|profiles|stop|slow|end|all [--ei n 1000] [--ei seed 42]
// Отчёт: журнал volna-bench и файл /sdcard/Android/data/app.glasstv.volna/files/bench-<suite>.txt
final class Bench {
    static final String T = "volna-bench";
    private static volatile boolean running;

    static void start(Context c, String suite, int n, long seed) {
        if (running) { Log.w(T, "стенд уже идёт"); return; }
        running = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try { new Bench(app, seed).run(suite == null ? "all" : suite, n); }
            catch (Throwable e) { Log.e(T, "стенд упал", e); }
            finally { running = false; }
        }, "volna-bench").start();
    }

    // ===== Поведение поддельного источника =====

    static final int OK = 0, EMPTY = 1, ERROR = 2;

    static final class Way {
        volatile long ms;
        volatile int kind = OK;
        volatile double p15;   // доля ответов, которые идут 15 с
        Way(long ms) { this.ms = ms; }
        void set(long ms, int kind, double p15) { this.ms = ms; this.kind = kind; this.p15 = p15; }
        String text() { return (kind == EMPTY ? "пусто" : kind == ERROR ? "ошибка" : "ок") + " " + ms + " мс" + (p15 > 0 ? ", 15 с у " + Math.round(p15 * 100) + " %" : ""); }
    }

    final Context c;
    final long seed;
    final Random rnd;
    final Random srcRnd;
    final Handler h = new Handler(Looper.getMainLooper());
    final AtomicInteger seq = new AtomicInteger();
    final StringBuilder out = new StringBuilder();
    volatile long trackMs = 180_000;   // длительность треков источника
    Engine e;
    FakeSource src;
    FakeAura aura;

    Bench(Context c, long seed) {
        this.c = c;
        this.seed = seed;
        rnd = new Random(seed);
        srcRnd = new Random(seed * 31 + 7);
    }

    final class FakeSource implements Source {
        final Way my = new Way(500);    // радио и песни исполнителя: «Моя волна»
        final Way nw = new Way(1000);   // жанры, плейлисты, чарты, новинки: «Новое»
        final Way alt = new Way(1000);  // поиск замены
        final AtomicInteger calls = new AtomicInteger();

        private void pause(Way w) throws Exception {
            calls.incrementAndGet();
            long d = w.ms;
            if (w.p15 > 0 && srcRnd.nextDouble() < w.p15) d = 15_000;
            Thread.sleep(d);
            if (w.kind == ERROR) throw new Exception("стенд: источник ответил ошибкой");
        }

        private List<Track> tracks(Way w, String prefix, int k, String artist) throws Exception {
            pause(w);
            List<Track> out = new ArrayList<>();
            if (w.kind == EMPTY) return out;
            for (int i = 0; i < k; i++) {
                int x = seq.incrementAndGet();
                out.add(new Track(prefix + x, "Track " + x, artist != null ? artist : "Artist " + x, trackMs));
            }
            return out;
        }

        @Override public List<Track> radio(String id) throws Exception {
            List<Track> r = tracks(my, "R", 12, null);
            if (!r.isEmpty()) r.add(0, new Track(id, "seed", "seed", trackMs));
            return r;
        }
        @Override public List<Track> songsOf(String a) throws Exception { return tracks(my, "A", 6, a); }
        @Override public Map<String, String> genres() throws Exception {
            pause(nw);
            Map<String, String> m = new LinkedHashMap<>();
            if (nw.kind == EMPTY) return m;
            for (String g : new String[]{"Hip-hop", "Dance & electronic", "R&B & soul", "Indie & alternative", "Energize", "Workout",
                    "Latin", "Gaming", "Folk & acoustic", "Russian pop", "Feel good", "Decades"}) m.put(g, "p:" + g);
            return m;
        }
        @Override public List<String> genrePlaylists(String p) throws Exception {
            pause(nw);
            List<String> l = new ArrayList<>();
            if (nw.kind != EMPTY) for (int i = 0; i < 6; i++) l.add("PL" + seq.incrementAndGet());
            return l;
        }
        @Override public List<Track> playlist(String id) throws Exception { return tracks(nw, "P", 12, null); }
        @Override public List<Track> charts(String cc) throws Exception { return tracks(nw, "C", 12, null); }
        @Override public List<String> newAlbums() throws Exception {
            pause(nw);
            List<String> l = new ArrayList<>();
            if (nw.kind != EMPTY) for (int i = 0; i < 8; i++) l.add("MPREb" + seq.incrementAndGet());
            return l;
        }
        @Override public List<Track> album(String id) throws Exception { return tracks(nw, "B", 3, null); }
        @Override public Track alternative(Track t) throws Exception {
            pause(alt);
            return alt.kind == EMPTY ? null : new Track("X" + seq.incrementAndGet(), t.title, t.artist, t.durationMs);
        }
    }

    // ===== Поддельная Aura: ведёт себя как наш патч (метаданные сразу, звук после загрузки, жёсткий стоп, отказ play после стопа) =====

    static final class Ev {
        final long t;
        final String type, id;
        Ev(long t, String type, String id) { this.t = t; this.type = type; this.id = id; }
    }

    final class FakeAura implements Link {
        boolean hard = true, item, loaded, pwr, error, ended;
        String id = "", title = "", artist = "";
        long dur, posBase, posAt;
        int g;
        volatile long coldMs = 800, warmMs = 250, jitterMs;
        volatile double pFail, pNever;
        volatile boolean failOnce, neverOnce;
        final Set<String> preloaded = new HashSet<>();
        final Way feed = new Way(300);
        String audible;
        final List<Ev> evs = Collections.synchronizedList(new ArrayList<Ev>());
        private long lastPush;
        final Runnable tick = new Runnable() {
            @Override public void run() {
                h.postDelayed(this, 250);
                if (st() != PlaybackState.STATE_PLAYING) return;
                if (pos() >= dur) { freeze(); posBase = dur; ended = true; push(); return; }
                if (now() - lastPush >= 3000) push();   // Aura сообщает о себе раз в 3 с
            }
        };

        long now() { return SystemClock.elapsedRealtime(); }

        void ev(String type, String id) { evs.add(new Ev(now(), type, id)); }

        int st() {
            if (!item) return PlaybackState.STATE_NONE;
            if (error) return PlaybackState.STATE_ERROR;
            if (ended) return PlaybackState.STATE_STOPPED;
            if (!loaded) return pwr ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED;
            return pwr ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        }

        long pos() { return st() == PlaybackState.STATE_PLAYING ? Math.min(dur, posBase + now() - posAt) : posBase; }

        void freeze() { posBase = pos(); posAt = now(); }

        void sound() {
            boolean on = st() == PlaybackState.STATE_PLAYING;
            if (on && !id.equals(audible)) { if (audible != null) ev("quiet", audible); audible = id; ev("sound", id); }
            else if (!on && audible != null) { ev("quiet", audible); audible = null; }
        }

        void push() {
            sound();
            lastPush = now();
            PlaybackState.Builder b = new PlaybackState.Builder().setState(st(), pos(), 1f, now());
            if (st() == PlaybackState.STATE_ERROR) b.setErrorMessage("стенд: трек не играет");
            e.onState(b.build());
        }

        MediaMetadata meta() {
            return new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).build();
        }

        @Override public void play(String mediaId) {
            String[] p = mediaId.split("/");
            freeze();
            g++;
            hard = false;
            item = true;
            loaded = false;
            pwr = true;
            error = false;
            ended = false;
            id = p[1];
            title = p.length > 2 ? Uri.decode(p[2]) : id;
            artist = p.length > 3 ? Uri.decode(p[3]) : "";
            long d = p.length > 4 ? Long.parseLong(p[4]) : 0;
            dur = d > 0 ? d : trackMs;
            posBase = 0;
            posAt = now();
            ev("play", id);
            sound();   // прошлый трек замолк сразу: плеер сменил содержимое
            final int my = g;
            h.postDelayed(() -> { if (my == g) { e.onMeta(meta()); push(); } }, 30);
            final boolean fail = failOnce || rnd.nextDouble() < pFail;
            final boolean never = neverOnce || rnd.nextDouble() < pNever;
            failOnce = neverOnce = false;
            long wait = preloaded.remove(id) ? warmMs : coldMs + (jitterMs > 0 ? rnd.nextInt((int) jitterMs) : 0);
            if (never) return;
            h.postDelayed(() -> {
                if (my != g || hard) return;
                if (fail) { error = true; push(); return; }
                loaded = true;
                posBase = 0;
                posAt = now();
                push();
            }, wait);
        }

        @Override public void pause() { freeze(); pwr = false; push(); }

        @Override public void resume() {
            if (hard) { ev("rejected", id); return; }   // как патч Aura: после жёсткого стопа play не проходит
            if (!item || ended) return;
            freeze();
            pwr = true;
            push();
        }

        @Override public void seekTo(long ms) { freeze(); posBase = ms; ended = false; push(); }

        @Override public void stopPlayer() { g++; freeze(); item = false; loaded = false; pwr = false; push(); }

        @Override public void custom(String action, Bundle b) {
            if (Engine.HARD_STOP.equals(action)) {
                ev("hard", id);
                hard = true;
                g++;
                item = loaded = pwr = error = ended = false;
                push();
            } else if (Engine.PRELOAD.equals(action)) {
                final String pid = b.getString("id");
                h.postDelayed(() -> preloaded.add(pid), warmMs);
            }
        }

        @Override public void command(String action, Bundle args, ResultReceiver rr) {
            final String ch = args.getString("profile", "");
            final String who = ch.isEmpty() ? "main" : "second";
            final String kind = args.getString("kind", "");
            long d = feed.ms;
            if (feed.p15 > 0 && srcRnd.nextDouble() < feed.p15) d = 15_000;
            if (feed.kind == ERROR && !Engine.PROFILE.equals(action)) return;   // ответа нет: «Волна» ждёт до 8 с
            h.postDelayed(() -> {
                JSONObject o = new JSONObject();
                try {
                    if (feed.kind == EMPTY) o.put("ok", false).put("err", "NO_LOGIN");
                    else if (Engine.FEED.equals(action)) {
                        JSONArray items = new JSONArray();
                        int k = kind.equals("liked") ? 5 : 12;
                        for (int i = 0; i < k; i++) {
                            int x = seq.incrementAndGet();
                            String tid = kind.equals("liked") ? "L" + who + "-" + i : kind.equals("home") ? "H" + who + "-" + x : "F" + x;
                            items.put(new JSONObject().put("id", tid).put("t", "Track " + x).put("a", "Artist " + who + " " + x).put("d", trackMs / 1000));
                        }
                        o.put("ok", true).put("items", items);
                    } else o.put("ok", true);
                } catch (Exception ignored) { }
                Bundle b = new Bundle();
                b.putString("json", o.toString());
                rr.send(0, b);
            }, d);
        }

        // Процесс Aura умер: сессия закрылась, после подключения она чистая и молчит
        void die() {
            g++;
            freeze();
            item = loaded = pwr = error = ended = false;
            hard = true;
            sound();
            e.sessionGone();
        }

        int count(String type, long from) {
            int n = 0;
            synchronized (evs) { for (Ev x : evs) if (x.t >= from && x.type.equals(type)) n++; }
            return n;
        }

        List<Ev> since(long from, String type) {
            List<Ev> l = new ArrayList<>();
            synchronized (evs) { for (Ev x : evs) if (x.t >= from && (type == null || x.type.equals(type))) l.add(x); }
            return l;
        }
    }

    // ===== Связь потока стенда с главным потоком =====

    private <V> V get(Callable<V> f) {
        final AtomicReference<V> r = new AtomicReference<>();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        h.post(() -> {
            try { r.set(f.call()); } catch (Throwable t) { err.set(t); }
            done.countDown();
        });
        try { done.await(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (err.get() != null) throw new RuntimeException(err.get());
        return r.get();
    }

    private void on(Runnable r) { get(() -> { r.run(); return null; }); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private static long now() { return SystemClock.elapsedRealtime(); }

    // Кто что попросил у плеера: id -> трек (по меткам видно волну и профиль)
    final Map<String, Track> sent = Collections.synchronizedMap(new HashMap<String, Track>());
    final List<long[]> sendTimes = Collections.synchronizedList(new ArrayList<long[]>());
    final List<Long> cmdMs = Collections.synchronizedList(new ArrayList<Long>());
    long lastCmd;

    // Команда как с пульта; время её обработки на главном потоке идёт в отчёт
    private void cmd(String what) {
        on(() -> {
            long t0 = System.nanoTime();
            switch (what) {
                case "my": e.start(Brain.MY); break;
                case "new": e.start(Brain.NEW); break;
                case "next": e.next(); break;
                case "prev": e.prev(); break;
                case "toggle": e.toggle(); break;
                case "stop": e.stop(); break;
                case "main": e.setProfile(Store.MAIN); break;
                case "second": e.setProfile(Store.SECOND); break;
                default: break;
            }
            cmdMs.add((System.nanoTime() - t0) / 1000);
        });
        lastCmd = now();
    }

    private List<Track> seedOf(int n, long dur) {
        List<Track> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new Track("S" + i, "Seed song " + i, "Seed Artist " + (i % 15), dur));
        return l;
    }

    // Новый тихий движок с чистой памятью: основной профиль с базой, второй пустой (новый человек без данных)
    private void fresh(long dur) {
        on(() -> {
            if (e != null) e.close();
            for (String n : new String[]{"volnatest", "volnatest_second", "volnatest_ui"}) c.deleteSharedPreferences(n);
        });
        trackMs = dur;
        final Map<String, List<Track>> seeds = new HashMap<>();
        seeds.put(Store.MAIN, seedOf(40, dur));
        seeds.put(Store.SECOND, new ArrayList<Track>());
        src = new FakeSource();
        aura = new FakeAura();
        sent.clear();
        sendTimes.clear();
        cmdMs.clear();
        on(() -> {
            e = new Engine(c, false, src, seeds);

            e.testLink = aura;
            e.hook = (ev, t) -> {
                if (t != null && ev.equals("send")) { sent.put(t.id, t); sendTimes.add(new long[]{now(), t.srcMode, Store.SECOND.equals(t.srcProfile) ? 1 : 0}); }
            };
            e.attachTest(aura);
            h.removeCallbacks(aura.tick);
            h.post(aura.tick);
        });
    }

    private void done() {
        on(() -> {
            e.close();
            h.removeCallbacks(aura.tick);
            e.hook = null;
        });
    }

    // ===== Счёт =====

    static long pct(List<Long> v, double p) {
        if (v.isEmpty()) return -1;
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(p * s.size()) - 1 < 0 ? 0 : (int) Math.ceil(p * s.size()) - 1));
    }

    static String stat(String name, List<Long> v, String unit) {
        if (v.isEmpty()) return name + ": нет данных";
        return String.format(Locale.ROOT, "%s: n %d, p50 %d, p95 %d, p99 %d, max %d %s", name, v.size(), pct(v, 0.5), pct(v, 0.95), pct(v, 0.99), pct(v, 1.0), unit);
    }

    private void say(String s) {
        Log.i(T, s);
        out.append(s).append('\n');
    }

    private void fail(Map<String, Integer> errs, String kind, String detail) {
        errs.put(kind, errs.containsKey(kind) ? errs.get(kind) + 1 : 1);
        if (errs.get(kind) <= 5) say("  сбой " + kind + ": " + detail);
    }

    private String errText(Map<String, Integer> errs) {
        int sum = 0;
        for (int v : errs.values()) sum += v;
        return "ошибок " + sum + (errs.isEmpty() ? "" : " " + errs);
    }

    private String dump() {
        return get(() -> "волна=" + (e.driving ? e.mode : "нет") + " план=" + (e.planned == null ? null : e.planned.id + "/" + e.planned.srcMode)
                + " показ=" + e.shownId() + " звук=" + aura.audible + " aura=" + aura.st() + " ждёт=" + e.waiting() + " статус=" + e.status);
    }

    // Тот трек, что задуман последней командой, звучит, и экран показывает его же
    private String settled(int wantMode, String wantProfile, boolean wantPlaying) {
        return get(() -> {
            if (e.mode != wantMode) return "режим движка " + e.mode;
            if (!wantProfile.equals(e.store.profile)) return "профиль движка " + e.store.profile;
            if (!wantPlaying) return aura.audible == null && !e.playing() ? null : "должна быть пауза";
            if (aura.audible == null) return "тишина";
            Track p = e.planned;
            if (p == null || !p.id.equals(aura.audible)) return "звучит не план";
            Track s = sent.get(aura.audible);
            int m = s == null ? p.srcMode : s.srcMode;
            if (m != -1 && m != wantMode) return "звучит трек другой волны " + m;
            String prof = s == null ? p.srcProfile : s.srcProfile;
            if (prof != null && !prof.equals(wantProfile)) return "звучит трек другого профиля";
            if (!aura.audible.equals(e.shownId())) return "экран показывает другое";
            return null;
        });
    }

    // Ждать, пока сбудется последнее намерение; вернуть время от последней команды до верного звука или -1
    private long waitSettled(int wantMode, String wantProfile, boolean wantPlaying, long limitMs, String[] why) {
        long t0 = lastCmd;
        String w = null;
        long okSince = 0;
        while (now() - t0 < limitMs) {
            w = settled(wantMode, wantProfile, wantPlaying);
            if (w == null) {
                if (okSince == 0) okSince = now();
                if (now() - okSince >= 300) return okSince - t0;
            } else okSince = 0;
            sleep(50);
        }
        why[0] = w;
        return -1;
    }

    // Звук после команды, который не принадлежит последнему намерению: старый ответ подменил новый
    private int staleSounds(long from, int wantMode, String wantProfile) {
        int n = 0;
        for (Ev x : aura.since(from + 20, "sound")) {
            Track s = sent.get(x.id);
            if (s == null) continue;
            if ((s.srcMode != -1 && s.srcMode != wantMode) || (s.srcProfile != null && !s.srcProfile.equals(wantProfile))) n++;
        }
        return n;
    }

    private long firstSend(long from, int wantMode) {
        synchronized (sendTimes) {
            for (long[] x : sendTimes) if (x[0] >= from && (x[1] == wantMode || x[1] == -1)) return x[0] - from;
        }
        return -1;
    }

    private long[] health() {
        System.gc();
        System.runFinalization();
        System.gc();
        sleep(300);
        int pool = 0, all = 0, lanes = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            all++;
            if (t.getName().startsWith("pool-") || t.getName().startsWith("volna-")) pool++;
            if (t.getName().matches("volna-(my|new|acc)-.*")) lanes++;
        }
        File[] tasks = new File("/proc/self/task").listFiles();
        Runtime r = Runtime.getRuntime();
        return new long[]{tasks == null ? -1 : tasks.length, all, pool, (r.totalMemory() - r.freeMemory()) / 1024, Debug.getPss(), lanes};
    }

    private static String healthText(long[] v) {
        return "потоков процесса " + v[0] + ", java " + v[1] + ", из них подбора " + v[5] + ", куча " + v[3] / 1024 + " МБ, PSS " + v[4] / 1024 + " МБ";
    }

    // Какие потоки живут: имя без номеров -> сколько. Видно, чей рост
    static String threadNames() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String k = t.getName().replaceAll("[0-9]+", "#");
            m.put(k, m.containsKey(k) ? m.get(k) + 1 : 1);
        }
        return m.toString();
    }

    // ===== Сценарии =====

    void run(String suite, int n) {
        long t0 = now();
        say("стенд «Волны»: " + suite + ", seed " + seed + ", " + new java.util.Date() + ", сборка " + version());
        say("до начала: " + healthText(health()));
        say("потоки: " + threadNames());
        boolean all = suite.equals("all");
        if (all || suite.equals("modes")) modes(n > 0 ? n : 1000);
        if (all || suite.equals("keys")) keys(n > 0 ? n : 300);
        if (all || suite.equals("profiles")) profiles(n > 0 ? n : 100);
        if (all || suite.equals("stop")) stops(n > 0 ? n : 100);
        if (all || suite.equals("slow")) slow(n > 0 ? n : 20);
        if (all || suite.equals("end")) ends(n > 0 ? n : 100);
        done();
        say("после всего: " + healthText(health()));
        say("потоки: " + threadNames());
        say("итог стенда за " + (now() - t0) / 1000 + " с");
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null) {
                try (FileOutputStream f = new FileOutputStream(new File(dir, "bench-" + suite + ".txt"))) { f.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
            }
        } catch (Exception ex) { Log.w(T, "отчёт не записан", ex); }
        Log.i(T, "КОНЕЦ");
    }

    private String version() {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName + " от " + new java.util.Date(p.lastUpdateTime);
        } catch (Exception ex) { return "?"; }
    }

    // 1. Смены «Моя»/«Новое»: интервалы 50–500 мс, каждые 25 смен затишье и проверка последнего намерения
    void modes(int n) {
        fresh(180_000);
        src.my.set(500, OK, 0);
        src.nw.set(1000, OK, 0.03);
        aura.feed.set(500, OK, 0);
        aura.coldMs = 800;
        aura.jitterMs = 600;
        say("\nmodes: " + n + " смен, интервал 50–500 мс, затишье каждые 25; источник «Моя» " + src.my.text() + ", «Новое» " + src.nw.text()
                + ", аккаунт " + aura.feed.text() + ", плеер холодный 800–1400 мс, заранее 250 мс");
        cmd("my");
        sleep(3000);
        Map<String, Integer> errs = new LinkedHashMap<>();
        List<Long> choose = new ArrayList<>(), sound = new ArrayList<>();
        int want = Brain.MY, checks = 0;
        for (int i = 0; i < n; i++) {
            want = rnd.nextBoolean() ? Brain.MY : Brain.NEW;
            cmd(want == Brain.MY ? "my" : "new");
            if ((i + 1) % 25 == 0 || i == n - 1) {
                checks++;
                long from = lastCmd;
                String[] why = {null};
                long ms = waitSettled(want, Store.MAIN, true, 30_000, why);
                if (ms < 0) fail(errs, "не дождались", "после смены " + (i + 1) + ": " + why[0] + "; " + dump());
                else sound.add(ms);
                long ch = firstSend(from, want);
                if (ch >= 0) choose.add(ch);
                int st = staleSounds(from, want, Store.MAIN);
                if (st > 0) fail(errs, "звук старой волны", st + " раз после смены " + (i + 1));
            } else sleep(50 + rnd.nextInt(451));
        }
        say("modes: проверок " + checks + ", " + errText(errs));
        say("  " + stat("последнее нажатие → трек нужной волны отправлен плееру", choose, "мс"));
        say("  " + stat("последнее нажатие → звук нужной волны (поддельный плеер)", sound, "мс"));
        say("  " + stat("обработка команды на главном потоке", toMs(cmdMs), "мкс"));
        say("  запросов к источнику " + src.calls.get() + ", " + healthText(health()));
        done();
    }

    private static List<Long> toMs(List<Long> l) { synchronized (l) { return new ArrayList<>(l); } }

    // 2. «Дальше», «назад» и пауза во время загрузки: экран и звук совпадают, нет двойного старта, пауза не теряется
    void keys(int n) {
        fresh(180_000);
        src.my.set(300, OK, 0);
        src.nw.set(500, OK, 0);
        aura.feed.set(300, OK, 0);
        aura.coldMs = 600;
        aura.jitterMs = 1900;
        say("\nkeys: " + n + " серий по 1–5 нажатий (дальше 50 %, назад 25 %, пауза 25 %) через 50–400 мс; плеер грузит трек 600–2500 мс");
        cmd("my");
        sleep(3000);
        Map<String, Integer> errs = new LinkedHashMap<>();
        List<Long> settle = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = 1 + rnd.nextInt(5);
            Boolean wantPlaying = null;
            for (int j = 0; j < k; j++) {
                int r = rnd.nextInt(4);
                if (r <= 1) { cmd("next"); wantPlaying = true; }
                else if (r == 2) { cmd("prev"); wantPlaying = null; }   // «назад» включает прошлый трек (или в начало): судит движок
                else {
                    boolean before = get(() -> e.playing());
                    cmd("toggle");
                    wantPlaying = !before;
                }
                if (j < k - 1) sleep(50 + rnd.nextInt(351));
            }
            long from = lastCmd;
            boolean wp = wantPlaying != null ? wantPlaying : get(() -> e.playing());
            String[] why = {null};
            long ms = waitSettled(get(() -> e.mode), Store.MAIN, wp, 8000, why);
            if (ms < 0) fail(errs, wp ? "не заиграл задуманный" : "пауза потерялась", "серия " + (i + 1) + ": " + why[0] + "; " + dump());
            else settle.add(ms);
            int starts = aura.count("sound", from + 20);
            if (starts > 1) fail(errs, "двойной старт", starts + " стартов звука после последнего нажатия, серия " + (i + 1));
            // перед следующей серией музыка должна играть
            if (!wp) { cmd("toggle"); sleep(200); }
            sleep(200);
        }
        say("keys: " + errText(errs));
        say("  " + stat("последнее нажатие → задуманное состояние (звук или пауза) устоялось", settle, "мс"));
        say("  " + stat("обработка команды на главном потоке", toMs(cmdMs), "мкс"));
        done();
    }

    // 3. Профили: смены во время подбора и игры. Лайки не переходят между профилями, потоки и память не растут
    void profiles(int n) {
        fresh(180_000);
        src.my.set(1000, OK, 0);
        src.nw.set(1500, OK, 0);
        aura.feed.set(1500, OK, 0.05);
        aura.coldMs = 800;
        say("\nprofiles: " + n + " смен профиля через 0,3–3 с, иногда смена волны; второй профиль пустой (новый человек); аккаунт " + aura.feed.text()
                + ", «Моя» " + src.my.text() + ", «Новое» " + src.nw.text());
        cmd("my");
        sleep(4000);
        long[] h0 = health();
        int made0 = Brain.made.get();
        say("  в начале: " + healthText(h0));
        Map<String, Integer> errs = new LinkedHashMap<>();
        List<Long> sound = new ArrayList<>();
        String prof = Store.MAIN;
        long[] hMid = null;
        for (int i = 0; i < n; i++) {
            prof = prof.equals(Store.MAIN) ? Store.SECOND : Store.MAIN;
            cmd(prof.equals(Store.MAIN) ? "main" : "second");
            if (rnd.nextInt(10) < 3) { sleep(100 + rnd.nextInt(400)); cmd(rnd.nextBoolean() ? "my" : "new"); }
            if ((i + 1) % 20 == 0) {
                final String wp = prof;
                int wm = get(() -> e.mode);
                long from = lastCmd;
                String[] why = {null};
                long ms = waitSettled(wm, wp, true, 30_000, why);
                if (ms < 0) fail(errs, "не дождались", "после смены " + (i + 1) + ": " + why[0] + "; " + dump());
                else sound.add(ms);
                int st = staleSounds(from, wm, wp);
                if (st > 0) fail(errs, "звук чужого профиля или волны", st + " раз");
                if (i + 1 == n / 2) hMid = health();
            } else sleep(300 + rnd.nextInt(2700));
        }
        sleep(20_000);   // всё, что было в пути, приходит
        long[] h1 = health();
        // Лайки аккаунта помечены профилем: основной не должен получить лайков второго и наоборот
        Store main = new Store(c, Store.MAIN, "volnatest", seedOf(40, 180_000));
        Store second = new Store(c, Store.SECOND, "volnatest", new ArrayList<Track>());
        int wrongMain = 0, wrongSecond = 0;
        for (String id : main.liked) if (id.startsWith("Lsecond")) wrongMain++;
        for (String id : second.liked) if (id.startsWith("Lmain")) wrongSecond++;
        if (wrongMain > 0) fail(errs, "лайки второго профиля в основном", wrongMain + " шт.");
        if (wrongSecond > 0) fail(errs, "лайки основного во втором", wrongSecond + " шт.");
        int alive = Brain.open.get();
        say("profiles: " + errText(errs));
        say("  " + stat("смена профиля → звук нужного профиля", sound, "мс"));
        say("  " + stat("обработка команды на главном потоке", toMs(cmdMs), "мкс"));
        say("  подборов создано " + (Brain.made.get() - made0) + ", не закрыто сейчас " + alive + " (включая настоящий)");
        if (hMid != null) say("  в середине: " + healthText(hMid));
        say("  в конце: " + healthText(h1));
        // Рост считаем по потокам подбора и открытым подборам: экран «Волны» и обложки заводят свои потоки, к подбору они не относятся
        long growth = h1[5] - h0[5];
        if (h1[5] > 6) fail(errs, "рост потоков подбора", "+" + growth + " за " + n + " смен, сейчас " + h1[5]);
        if (alive > 2) fail(errs, "подборы не закрыты", alive + " открыто");
        say("  потоков подбора было " + h0[5] + ", стало " + h1[5] + "; всех потоков процесса " + h0[0] + " → " + h1[0] + ", кучи " + (h1[3] - h0[3]) / 1024 + " МБ");
        done();
    }

    // 4. «Стоп» в каждой стадии: подбор, адрес потока, игра, восстановление после смерти Aura, поиск замены, долгая загрузка, подключение
    void stops(int n) {
        fresh(180_000);
        src.my.set(300, OK, 0);
        src.nw.set(3000, OK, 0);
        src.alt.set(2000, OK, 0);
        aura.feed.set(300, OK, 0);
        aura.coldMs = 800;
        say("\nstop: " + n + " остановок по стадиям; после каждой слушаем, не появится ли звук");
        String[] stages = {"подбор", "адрес", "игра", "восстановление", "замена", "загрузка без конца", "подключение"};
        Map<String, Integer> errs = new LinkedHashMap<>();
        Map<String, Integer> per = new LinkedHashMap<>();
        List<Long> restart = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String stage = stages[i % stages.length];
            per.put(stage, per.containsKey(stage) ? per.get(stage) + 1 : 1);
            // исходное: играет «Моя волна»
            aura.coldMs = 800;
            cmd("my");
            String[] why = {null};
            if (waitSettled(Brain.MY, Store.MAIN, true, 10_000, why) < 0) { fail(errs, "не заиграло перед проверкой", why[0] + "; " + dump()); continue; }
            long listen = 5000;
            switch (stage) {
                case "подбор":
                    on(() -> e.brain.testClear());
                    cmd("new");
                    sleep(300 + rnd.nextInt(2200));
                    listen = 12_000;
                    break;
                case "адрес":
                    aura.coldMs = 3000;
                    cmd("next");
                    sleep(200 + rnd.nextInt(2300));
                    break;
                case "игра":
                    sleep(500 + rnd.nextInt(3000));
                    listen = 3000;
                    break;
                case "восстановление":
                    on(() -> aura.die());
                    sleep(200 + rnd.nextInt(1000));
                    listen = 6000;
                    break;
                case "замена":
                    aura.failOnce = true;
                    cmd("next");
                    sleep(900 + rnd.nextInt(1000));
                    break;
                case "загрузка без конца":
                    aura.neverOnce = true;
                    cmd("next");
                    sleep(1000 + rnd.nextInt(13_000));
                    listen = 17_000;
                    break;
                case "подключение":
                    on(() -> { e.testReconnectMs = 4000; aura.die(); });
                    sleep(100);
                    cmd("next");
                    sleep(300 + rnd.nextInt(700));
                    listen = 6000;
                    break;
            }
            cmd("stop");
            long ts = lastCmd;
            sleep(listen);
            int late = aura.count("sound", ts + 20);
            if (late > 0) fail(errs, "звук после стопа", "стадия «" + stage + "», стартов " + late);
            boolean quiet = get(() -> !e.driving && aura.audible == null);
            if (!quiet) fail(errs, "не тишина после стопа", "стадия «" + stage + "»: " + dump());
            // после стопа следующее «Включить» обязано заиграть быстро
            on(() -> e.testReconnectMs = 300);
            cmd("my");
            long ms = waitSettled(Brain.MY, Store.MAIN, true, 20_000, why);
            if (ms < 0) fail(errs, "после стопа не включается", "стадия «" + stage + "»: " + why[0] + "; " + dump());
            else restart.add(ms);
        }
        say("stop: стадий " + per + ", " + errText(errs));
        say("  " + stat("после стопа «Включить» → звук", restart, "мс"));
        done();
    }

    // 5. Медленный и пустой источник: 0,5/3/15 с, пусто, ошибка. «Новое» не ждёт работу «Моей», ожидание видно и ограничено
    void slow(int n) {
        say("\nslow: условия 0,5 с, 3 с, 15 с, пусто, ошибка; повторов " + Math.max(1, n / 5) + " (15 с: вдвое меньше)");
        long[][] conds = {{500, OK}, {3000, OK}, {15_000, OK}, {500, EMPTY}, {500, ERROR}};
        Map<String, Integer> errs = new LinkedHashMap<>();
        for (long[] cd : conds) {
            int reps = Math.max(1, n / 5);
            if (cd[0] == 15_000) reps = Math.max(1, reps / 2);
            List<Long> newSound = new ArrayList<>(), mySound = new ArrayList<>(), statusMs = new ArrayList<>();
            int noSound = 0;
            String name = (cd[1] == EMPTY ? "пусто" : cd[1] == ERROR ? "ошибка" : (cd[0] / 1000.0) + " с");
            for (int r = 0; r < reps; r++) {
                fresh(180_000);
                src.my.set(cd[0], (int) cd[1], 0);
                src.nw.set(cd[0], (int) cd[1], 0);
                aura.feed.set(cd[0], (int) cd[1], 0);
                cmd("my");   // «Моя» играет своё сразу, а её подбор (аккаунт, радио) идёт медленно
                sleep(400);
                cmd("new");
                long t0 = lastCmd;
                // ожидание должно быть видно сразу
                long shown = -1;
                while (now() - t0 < 1500) {
                    boolean w = get(() -> e.waiting() && !e.status.isEmpty());
                    if (w || aura.audible != null) { shown = now() - t0; break; }
                    sleep(20);
                }
                if (shown >= 0) statusMs.add(shown);
                else fail(errs, "ожидание не видно", name);
                String[] why = {null};
                long limit = cd[1] == OK ? cd[0] * 4 + 4000 : 8000;
                long ms = waitSettled(Brain.NEW, Store.MAIN, true, limit, why);
                if (ms >= 0) newSound.add(ms);
                else {
                    noSound++;
                    if (cd[1] == OK) fail(errs, "«Новое» не заиграло за " + limit / 1000 + " с", name + ": " + why[0] + "; " + dump());
                }
                cmd("my");
                long my = waitSettled(Brain.MY, Store.MAIN, true, 5000, why);
                if (my < 0) fail(errs, "«Моя» не вернулась за 5 с", name + ": " + why[0]);
                else mySound.add(my);
                done();
            }
            say("  " + name + ": " + stat("«Новое» до звука", newSound, "мс") + "; без звука " + noSound + " из " + reps);
            say("    " + stat("ожидание показано через", statusMs, "мс") + "; " + stat("назад в «Мою» до звука", mySound, "мс"));
        }
        say("slow: " + errText(errs));
    }

    // 6. Естественный конец трека: треки по 6 с. Нет двойного «дальше», конец не съеден, очередь не встаёт
    void ends(int n) {
        fresh(6000);
        src.my.set(300, OK, 0);
        aura.feed.set(300, OK, 0);
        aura.coldMs = 400;
        say("\nend: " + n + " переходов по концу трека, треки по 6 с, адрес следующего готовится заранее");
        cmd("my");
        Map<String, Integer> errs = new LinkedHashMap<>();
        List<Long> gaps = new ArrayList<>(), played = new ArrayList<>();
        long t0 = now();
        long limit = t0 + n * 9000L;
        while (aura.count("sound", t0) < n + 1 && now() < limit) sleep(200);
        List<Ev> all = aura.since(t0, null);
        Ev lastSound = null;
        Map<String, Integer> sendsPerTrack = new HashMap<>();
        for (Ev x : all) {
            if (x.type.equals("play")) sendsPerTrack.put(x.id, sendsPerTrack.containsKey(x.id) ? sendsPerTrack.get(x.id) + 1 : 1);
            if (x.type.equals("sound")) lastSound = x;
            if (x.type.equals("quiet") && lastSound != null && lastSound.id.equals(x.id)) played.add(x.t - lastSound.t);
        }
        // пауза между концом одного и звуком следующего
        Ev q = null;
        for (Ev x : all) {
            if (x.type.equals("quiet")) q = x;
            if (x.type.equals("sound") && q != null) { gaps.add(x.t - q.t); q = null; }
        }
        int sounds = aura.count("sound", t0);
        if (sounds < n + 1) fail(errs, "очередь встала", "звуков " + sounds + " из " + (n + 1) + "; " + dump());
        int doubles = 0;
        for (int v : sendsPerTrack.values()) if (v > 1) doubles++;
        if (doubles > 0) fail(errs, "трек запрошен дважды", doubles + " раз");
        int eaten = 0;
        for (long p : played) if (p < 6000 - 1200) eaten++;
        if (eaten > 1) fail(errs, "съеден конец трека", eaten + " треков короче 4,8 с");   // первый может начаться с середины
        say("end: " + errText(errs));
        say("  " + stat("звучал трек", played, "мс") + "; " + stat("тишина между треками", gaps, "мс"));
        done();
    }
}
