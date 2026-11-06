package app.glasstv.volna;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;

// Мозг: собирает очередь для двух волн.
// «Моя волна»: 35% своё (лайки, плейлисты, история), 65% в том же духе: радио от любимых треков, ещё песни любимых исполнителей,
// домашняя лента аккаунта. «Не моё» гасит не только исполнителя, но и всё направление (язык и жанр) до конца сеанса.
// «Новое для вас»: только незнакомые треки и незнакомые исполнители из смежных жанров, чартов и новинок, жанры чередуются.
// Если в Aura выполнен вход, радио и домашняя лента идут от имени канала профиля, а его лайки становятся своими треками. Нет входа: открытые запросы.
final class Brain {
    static final int MY = 0, NEW = 1;
    static final String[] MODE_NAMES = {"Моя волна", "Новое для вас"};
    static final long MAX_LEN = 8 * 60_000, MIN_LEN = 45_000;

    // У второго вкуса много долгих песен (по 8-10 минут): им длину не режем
    long maxLen() { return s.profile.isEmpty() ? MAX_LEN : 12 * 60_000; }

    interface Ready { void onReady(int mode); }

    interface Personal { List<Track> feed(String kind, String seed); }   // подборка аккаунта профиля, null: нет входа или связи

    // Порядок «своё / похожее» на каждые 20 треков: 7 к 13, своё не реже чем через два похожих
    private static final boolean[] PATTERN = {true, false, false, true, false, false, true, false, false, true,
            false, false, true, false, false, true, false, false, true, false};

    // Исполнители, которых YouTube и так подсовывает пользователю (миксы): в «Новом» их не будет. В этой копии список пуст, впишите своих строчными буквами
    private static final Set<String> BUBBLE = new HashSet<>();

    // Корзины «Нового»: ключ, бейдж, жанровая метка, источники
    private static final Object[][] BUCKETS = {
            {"trend", "🔥 Новое: хип-хоп тренд", Tags.HIPHOP, new String[]{"genre:Hip-hop"}},
            {"fresh", "🆕 Свежий релиз", 0, new String[]{"new"}},
            {"electro", "🧭 Открытие жанра: электроника", Tags.ELECTRONIC, new String[]{"genre:Dance & electronic"}},
            {"rnb", "🧭 Открытие жанра: R&B", Tags.RNB, new String[]{"genre:R&B & soul"}},
            {"world", "🌍 Мировой хит", 0, new String[]{"chart:US", "chart:GB"}},
            {"indie", "🧭 Открытие жанра: альтернатива", Tags.ROCK, new String[]{"genre:Indie & alternative"}},
            {"handshake", "🧭 Новые артисты рядом с вашим вкусом", 0, new String[]{"radio"}},
            {"energy", "⚡ Новое с энергией", 0, new String[]{"genre:Energize", "genre:Workout"}},
            {"latin", "🧭 Открытие жанра: латино", Tags.LATIN, new String[]{"genre:Latin"}},
            {"ost", "🧭 Открытие жанра: музыка из игр", 0, new String[]{"genre:Gaming"}},
    };

    // «Новое» для второго вкуса: мягкие жанры, без хип-хопа, латино и чартов.
    // Основа: радио от треков самого вкуса, но только незнакомые исполнители.
    // Отдельного жанра для духовной музыки в списке YouTube Music нет, её и приносит радио от своих треков
    private static final Object[][] SOFT_BUCKETS = {
            {"handshake", "🧭 Новые артисты рядом с вашим вкусом", 0, new String[]{"radio"}},
            {"folk", "🧭 Открытие жанра: фолк и акустика", 0, new String[]{"genre:Folk & acoustic"}},
            {"rupop", "🧭 Открытие жанра: русский поп", 0, new String[]{"genre:Russian pop"}},
            {"feelgood", "☀ Для хорошего настроения", 0, new String[]{"genre:Feel good"}},
            {"decades", "📻 Хиты прошлых лет", 0, new String[]{"genre:Decades"}},
    };
    private final Object[][] buckets;

    final Store s;
    Ready ready;
    Personal personal;
    Source source = Source.NET;
    static final java.util.concurrent.atomic.AtomicInteger made = new java.util.concurrent.atomic.AtomicInteger();
    final int gen = made.incrementAndGet();   // номер подбора: по нему в журнале видно, чей ответ пришёл
    static final java.util.concurrent.atomic.AtomicInteger open = new java.util.concurrent.atomic.AtomicInteger();   // сколько подборов не закрыто
    String tag = "volna";
    private long accountSyncAt;
    // Свой поток у каждой волны и у аккаунта: «Новое» не ждёт долгий подбор «Моей», и наоборот. Без дела поток засыпает через 20 с,
    // при закрытии подбора все три останавливаются. Больше трёх потоков на подбор не бывает
    private final ExecutorService exMy = lane("my"), exNew = lane("new"), exAcc = lane("acc");
    private volatile boolean closed;
    private volatile boolean syncing;
    static final int BUF_MAX = 15;   // готовых треков в очереди волны не больше
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Track>[] buf = new ArrayDeque[]{new ArrayDeque<Track>(), new ArrayDeque<Track>()};
    private final boolean[] filling = new boolean[2];
    private int patternPos;
    private final ArrayDeque<Track> similarPool = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Track>> bucketPool = new HashMap<>();
    private final Map<String, Float> bucketWeight = new HashMap<>();
    private final Map<String, List<String>> genreLists = new HashMap<>();
    private final Set<String> usedLists = new HashSet<>();
    private Map<String, String> genreParams;
    private List<String> albums;
    private final ArrayDeque<String> recentBuckets = new ArrayDeque<>();   // последние три жанра «Нового»
    private final ArrayDeque<String> recentArtists = new ArrayDeque<>();
    private final Set<String> queued = Collections.synchronizedSet(new HashSet<String>());

    // Во вкусе почти нет русских песен: «Новое» их не предлагает вовсе, оно про западные хиты и новые направления
    private final boolean westernNew;

    private ExecutorService lane(String name) {
        java.util.concurrent.ThreadPoolExecutor x = new java.util.concurrent.ThreadPoolExecutor(1, 1, 20, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(), r -> new Thread(r, "volna-" + name + "-" + gen));
        x.allowCoreThreadTimeOut(true);
        return x;
    }

    boolean closed() { return closed; }

    // Закрыть подбор (сменили профиль): потоки остановлены, отложенные повторы сняты, ответы и «готово» больше никуда не идут.
    // Возвращает готовые очереди двух волн: если вернутся в этот профиль, новый подбор начнёт с них
    // Что подбор успел собрать: очереди двух волн, пулы радио и подборок, когда подтягивался аккаунт.
    // При возврате в профиль новый подбор начинает с этого и не ждёт сеть заново
    static final class Parked {
        final long at = android.os.SystemClock.elapsedRealtime();
        List<Track> my, nw, similar;
        Map<String, List<Track>> buckets = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        Map<String, String> genres;
        long accountSyncAt;
    }

    synchronized Parked close() {
        Parked p = new Parked();
        p.my = new ArrayList<>(buf[MY]);
        p.nw = new ArrayList<>(buf[NEW]);
        p.similar = new ArrayList<>(similarPool);
        for (Map.Entry<String, ArrayDeque<Track>> e : bucketPool.entrySet()) p.buckets.put(e.getKey(), new ArrayList<>(e.getValue()));
        p.lists.putAll(genreLists);
        p.genres = genreParams;
        p.accountSyncAt = accountSyncAt;
        if (closed) return p;
        closed = true;
        ready = null;
        personal = null;
        changed = null;
        main.removeCallbacksAndMessages(null);
        exMy.shutdownNow();
        exNew.shutdownNow();
        exAcc.shutdownNow();
        open.decrementAndGet();
        return p;
    }

    synchronized void adopt(Parked p) {
        int[] took = new int[2];
        List<Track>[] q = new List[]{p.my, p.nw};
        for (int m = 0; m < 2; m++)
            for (Track t : q[m]) {
                if (buf[m].size() >= BUF_MAX || queued.contains(t.id)) continue;
                t.srcGen = gen;
                buf[m].addLast(t);
                queued.add(t.id);
                took[m]++;
            }
        similarPool.addAll(p.similar);
        for (Map.Entry<String, List<Track>> e : p.buckets.entrySet()) bucketPool.put(e.getKey(), new ArrayDeque<>(e.getValue()));
        genreLists.putAll(p.lists);
        genreParams = p.genres;
        accountSyncAt = p.accountSyncAt;
        Log.i(tag, "подбор " + gen + ": из кэша профиля «Моя» " + took[MY] + ", «Новое» " + took[NEW] + ", похожих " + similarPool.size());
    }

    Brain(Store s) {
        this.s = s;
        open.incrementAndGet();
        int ru = 0;
        for (Store.Seed sd : s.seed) if ((sd.t.tags & Tags.RU) != 0) ru++;
        westernNew = s.seed.size() > 20 && ru < s.seed.size() * 0.15;
        buckets = s.profile.isEmpty() ? BUCKETS : SOFT_BUCKETS;
        for (Object[] b : BUCKETS) bucketWeight.put((String) b[0], 1f);
        // Ближе к вкусу (рэп, свежие релизы, электроника, новые артисты рядом) чаще, далёкое реже
        // «Новое» выводит из привычного: мировые хиты, свежие релизы, зарубежный хип-хоп и электроника чаще, радио от своих реже
        bucketWeight.put("trend", 1.6f);
        bucketWeight.put("fresh", 1.4f);
        bucketWeight.put("world", 1.4f);
        bucketWeight.put("electro", 1.3f);
        bucketWeight.put("handshake", 0.9f);
        bucketWeight.put("energy", 1.0f);
        bucketWeight.put("rnb", 0.9f);
        bucketWeight.put("indie", 1.0f);
        bucketWeight.put("latin", 0.5f);
        bucketWeight.put("ost", 0.4f);
        // Веса выше подобраны под первый вкус. У второго вкуса радио от своих песен в основе, остальное приправа
        if (!s.profile.isEmpty()) {
            bucketWeight.clear();
            bucketWeight.put("handshake", 4f);
            bucketWeight.put("folk", 1f);
            bucketWeight.put("rupop", 0.8f);
            bucketWeight.put("feelgood", 0.6f);
            bucketWeight.put("decades", 0.5f);
        }
    }

    // Следующий трек волны. Для «Моей волны» всегда что-то есть; для «Нового» может быть null, пока идёт подбор
    Track next(int mode) {
        Track t;
        // Пока трек лежал в очереди, его могли отвергнуть или он стал знакомым: перепроверяем при выдаче
        while (true) {
            synchronized (this) { t = buf[mode].pollFirst(); }
            if (t == null) break;
            queued.remove(t.id);
            if (stillGood(t, mode)) break;
        }
        refillIfLow(mode);
        if (t == null && mode == MY) {
            synchronized (this) { t = familiar(); }
            if (t != null) {
                remember(t);
                t.srcMode = mode;
                t.srcProfile = s.profile;
                t.srcGen = gen;
            }
        }
        return t;
    }

    // Стенд: очереди пусты, как у только что открытого подбора
    synchronized void testClear() {
        for (ArrayDeque<Track> q : buf) q.clear();
        for (ArrayDeque<Track> q : bucketPool.values()) q.clear();
        queued.clear();
    }

    private boolean stillGood(Track t, int mode) {
        if (s.disliked.contains(t.id) || s.dead.contains(t.id) || s.artistScore(t) < -1) return false;
        if (t.kind != Track.FAMILIAR && banned(t, mode)) return false;
        if (mode == NEW) {
            if (s.known(t.id)) return false;
            for (String a : t.artists()) if (s.knownArtists.contains(a) || s.score(a) < 0) return false;
        } else if (t.kind == Track.SIMILAR && s.recentlyPlayed(t.id, 24 * 3600_000L)) {
            return false;
        }
        return true;
    }

    Track peek(int mode) {
        synchronized (this) { return buf[mode].peekFirst(); }
    }

    // Что волна приготовила дальше: для списка «Очередь» в плеере
    synchronized java.util.List<Track> upcoming(int mode, int n) {
        java.util.List<Track> out = new ArrayList<>();
        for (Track t : buf[mode]) { if (out.size() >= n) break; out.add(t); }
        return out;
    }

    // Трек включили руками из списка: убрать его из очередей, чтобы не сыграл второй раз подряд
    synchronized void drop(String id) {
        for (ArrayDeque<Track> q : buf) q.removeIf(t -> t.id.equals(id));
        queued.remove(id);
    }

    void refillIfLow(int mode) {
        // Аккаунт отдельно и не по пути к первому треку: его лайки и лента придут, когда придут
        syncSoon();
        synchronized (this) {
            if (closed || filling[mode] || buf[mode].size() >= 5) return;
            filling[mode] = true;
        }
        try {
            (mode == MY ? exMy : exNew).execute(() -> {
                int got = 0;
                try {
                    // Треки кладутся в очередь по одному: первый готовый сразу уходит в игру
                    List<Track> batch = mode == MY ? genMy(10) : genNew(10);
                    got = batch.size();
                } catch (Throwable e) {
                    if (!closed) Log.w(tag, "подбор не удался: " + e);
                } finally {
                    synchronized (this) { filling[mode] = false; }
                    // Пусто (нет сети): не дёргаем движок по кругу, пробуем снова через полминуты. Закрытый подбор не пробует
                    if (got == 0 && !closed) main.postDelayed(() -> refillIfLow(mode), 30_000);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            synchronized (this) { filling[mode] = false; }
        }
    }

    private void syncSoon() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (closed || syncing || personal == null || (accountSyncAt != 0 && now - accountSyncAt < 30 * 60_000)) return;
        syncing = true;
        try {
            exAcc.execute(() -> {
                try { syncAccount(); }
                catch (Throwable e) { if (!closed) Log.w(tag, "аккаунт не подтянулся: " + e); }
                finally { syncing = false; }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            syncing = false;
        }
    }

    // ===== Направления: язык (кириллица или нет) и главный жанр. «Не моё» гасит направление до конца сеанса =====

    private final Map<String, Float> cluster = new HashMap<>();

    static String lang(Track t) { return (t.tags & Tags.RU) != 0 ? "ru" : "lat"; }

    private static String key(Track t) { return "c:" + lang(t) + ":" + Tags.mainBit(t.tags); }

    // Язык целиком весит вполовину от направления
    synchronized float penalty(Track t) {
        return cluster.getOrDefault(key(t), 0f) + 0.5f * cluster.getOrDefault("l:" + lang(t), 0f);
    }

    // В «Новом» направление выключается с первого «не моё», в «Моей волне» со второго: там оно чаще всего своё, первый раз только реже
    boolean banned(Track t, int mode) { return penalty(t) >= (mode == NEW ? 2 : 4); }

    // Пользователь сказал «не моё»: из очередей уходит этот исполнитель и всё того же направления (например, русская попса целиком)
    synchronized void onDislike(Track t) {
        List<String> arts = t.artists();
        cluster.merge(key(t), 2f, Float::sum);
        cluster.merge("l:" + lang(t), 1f, Float::sum);
        purge(buf[MY], arts, MY);
        purge(similarPool, arts, MY);
        purge(buf[NEW], arts, NEW);
        for (ArrayDeque<Track> q : bucketPool.values()) purge(q, arts, NEW);
        if (!t.bucket.isEmpty()) bucketWeight.put(t.bucket, Math.max(0.2f, bucketWeight.getOrDefault(t.bucket, 1f) - 0.4f));
        Log.i(tag, "не моё: направление " + key(t) + " = " + cluster.get(key(t)) + ", язык " + lang(t) + " = " + cluster.get("l:" + lang(t)));
    }

    synchronized void onLike(Track t) {
        if (!t.bucket.isEmpty()) bucketWeight.put(t.bucket, Math.min(5f, bucketWeight.getOrDefault(t.bucket, 1f) + 0.5f));
        // Лайк возвращает направлению вес, если раньше его гасили
        cluster.computeIfPresent(key(t), (k, v) -> Math.max(0f, v - 1f));
        cluster.computeIfPresent("l:" + lang(t), (k, v) -> Math.max(0f, v - 0.5f));
    }

    // ===== Аккаунт профиля: лайки и домашняя лента, не чаще раза в полчаса =====

    // Связь с аккаунтом появилась (Aura подключилась и узнала профиль): подтянуть лайки и ленту сразу, не дожидаясь подбора
    void accountReady() { syncSoon(); }

    private void syncAccount() {
        long now = android.os.SystemClock.elapsedRealtime();
        Personal p = personal;
        if (closed || p == null || (accountSyncAt != 0 && now - accountSyncAt < 30 * 60_000)) return;
        List<Track> likes = p.feed("liked", null);
        if (likes == null || closed) return;   // нет входа или связи: попробуем при следующем подборе. Закрытый подбор чужое не пишет
        accountSyncAt = now;
        s.mergeAccountLikes(likes);
        List<Track> home = p.feed("home", null);
        if (home == null || closed) return;
        Collections.shuffle(home, rnd);
        synchronized (this) {
            int n = 0;
            for (Track t : home) {
                if (n >= 12 || s.disliked.contains(t.id) || queued.contains(t.id) || s.recentlyPlayed(t.id, 24 * 3600_000L)) continue;
                if (t.durationMs > maxLen() || s.artistScore(t) < -1) continue;
                t.kind = Track.SIMILAR;
                t.badge = "⚡ Из вашей ленты";
                similarPool.add(t);
                n++;
            }
        }
    }

    // Радио от трека: от имени профиля, если есть вход
    private List<Track> radioOf(String id) throws Exception {
        Personal p = personal;
        List<Track> r = p == null ? null : p.feed("radio", id);
        if (closed) return new ArrayList<>();
        return r == null || r.isEmpty() ? source.radio(id) : r;
    }

    private void purge(ArrayDeque<Track> q, List<String> arts, int mode) {
        for (Iterator<Track> it = q.iterator(); it.hasNext(); ) {
            Track x = it.next();
            boolean drop = x.kind != Track.FAMILIAR && banned(x, mode);
            for (String a : x.artists()) if (arts.contains(a)) drop = true;
            if (drop) { it.remove(); queued.remove(x.id); }
        }
    }

    private synchronized void remember(Track t) {
        for (String a : t.artists()) {
            recentArtists.addLast(a);
            while (recentArtists.size() > 8) recentArtists.pollFirst();
        }
    }

    private synchronized boolean recentArtist(Track t, int depth) {
        Object[] arr = recentArtists.toArray();
        for (String a : t.artists())
            for (int i = Math.max(0, arr.length - depth); i < arr.length; i++) if (a.equals(arr[i])) return true;
        return false;
    }

    // ===== «Моя волна» =====

    private List<Track> genMy(int n) {
        List<Track> out = new ArrayList<>();
        // Похожих ещё нет (новый подбор, сменили профиль): сначала несколько своих, они без сети, и очередь на экране полна сразу.
        // Похожие встают следом, как только придёт радио
        int quick;
        synchronized (this) { quick = similarPool.isEmpty() && buf[MY].size() < 4 ? 4 - buf[MY].size() : 0; }
        for (int i = 0; i < quick && !closed; i++) {
            Track t;
            synchronized (this) { t = familiar(); }
            if (t == null) break;
            queued.add(t.id);
            remember(t);
            out.add(t);
            emit(MY, t, out.size() == 1);
        }
        for (int i = out.size(); i < n && !closed; i++) {
            boolean own = PATTERN[patternPos++ % PATTERN.length];
            Track t = null;
            if (!own) t = similar();
            synchronized (this) { if (t == null) t = familiar(); }
            if (t == null) continue;
            queued.add(t.id);
            remember(t);
            out.add(t);
            emit(MY, t, out.size() == 1);
        }
        return out;
    }

    // Готовый трек сразу в очередь; первый из пачки будит движок, если тот ждёт
    private void emit(int mode, Track t, boolean first) {
        t.srcMode = mode;
        t.srcProfile = s.profile;
        t.srcGen = gen;
        synchronized (this) {
            if (closed) return;   // ответ пришёл после закрытия: никуда
            if (buf[mode].size() >= BUF_MAX) { queued.remove(t.id); return; }
            buf[mode].addLast(t);
        }
        // Будит движок каждый трек, а не только первый из пачки: очередь могли выбрать до дна посреди подбора (быстро листали),
        // и тогда движок висел на «Подбираю…», хотя треки уже лежали в очереди
        main.post(() -> { Ready r = ready; if (r != null && !closed) r.onReady(mode); });
        // Экран узнаёт о новом треке в очереди сразу, а не со следующим сообщением плеера (серия сводится в одно обновление)
        main.removeCallbacks(tellChanged);
        main.postDelayed(tellChanged, 120);
    }

    Runnable changed;   // в очереди появились треки
    private final Runnable tellChanged = () -> { Runnable c = changed; if (c != null && !closed) c.run(); };

    // Своё: случайный выбор с весами (лайки и плейлисты сильнее, свежая история сильнее старой)
    private Track familiar() {
        List<Track> cand = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (long fresh : new long[]{8 * 3600_000L, 2 * 3600_000L, 0}) {
            for (Store.Seed sd : s.seed) addFamiliar(cand, w, sd.t, sd.sources, fresh);
            for (Map.Entry<String, String[]> e : s.likedMeta.entrySet())
                if (!s.seedIds.contains(e.getKey()))
                    addFamiliar(cand, w, s.likedTrack(e.getKey(), e.getValue()), "volna", fresh);
            if (!cand.isEmpty()) break;
        }
        if (cand.isEmpty()) return null;
        Track copy = cand.get(pick(w)).copy();
        copy.kind = Track.FAMILIAR;
        return copy;
    }

    private void addFamiliar(List<Track> cand, List<Double> w, Track t, String sources, long freshMs) {
        if (s.disliked.contains(t.id) || s.dead.contains(t.id) || queued.contains(t.id) || t.durationMs > maxLen()) return;
        if (freshMs > 0 && s.recentlyPlayed(t.id, freshMs)) return;
        if (recentArtist(t, 3)) return;
        double base = 0;
        String badge = "↺ Из вашей истории";
        for (String src : sources.split(",")) {
            double v;
            String b;
            switch (src) {
                case "liked": v = 3.0; b = "★ Из ваших лайков"; break;
                case "volna": v = 3.0; b = "❤ Вы лайкнули в «Волне»"; break;
                case "library": v = 2.0; b = "★ Из вашей библиотеки"; break;
                case "hSep": v = 1.6; b = "↺ Вы слушали в сентябре"; break;
                case "hAug": v = 1.3; b = "↺ Вы слушали в августе"; break;
                case "hJul": v = 1.0; b = "↺ Вы слушали в июле"; break;
                case "hJun": v = 0.8; b = "↺ Вы слушали в июне"; break;
                default:
                    if (src.startsWith("pl:")) { v = 2.0; b = "★ Из плейлиста «" + src.substring(3) + "»"; }
                    else { v = 0.8; b = badge; }
            }
            if (v > base) { base = v; badge = b; } else base += 0.3;
        }
        if (s.liked.contains(t.id) && base < 3) { base = 3; badge = "❤ Вы лайкнули в «Волне»"; }
        double k = Math.max(0.15, 1 + 0.25 * s.artistScore(t) + 0.2 * s.tagScore(t.tags)) * Math.max(0.25, 1 - 0.25 * penalty(t));
        t.badge = badge;
        cand.add(t);
        w.add(base * k);
    }

    private int pick(List<Double> w) {
        double sum = 0;
        for (double v : w) sum += v;
        double r = rnd.nextDouble() * sum;
        for (int i = 0; i < w.size(); i++) { r -= w.get(i); if (r <= 0) return i; }
        return w.size() - 1;
    }

    // Похожее: радио YouTube Music от любимых треков, без отвергнутого и недавно игравшего
    private Track similar() {
        int have;
        synchronized (this) { have = similarPool.size(); }
        if (have < 4 && !closed) fillSimilar();
        return pickSimilar();
    }

    private Track pickSimilar() {
        Track best = null;
        double bestScore = -1e9;
        int looked = 0;
        synchronized (this) {
            for (Iterator<Track> it = similarPool.iterator(); it.hasNext(); ) {
                Track t = it.next();
                if (queued.contains(t.id) || s.disliked.contains(t.id) || s.recentlyPlayed(t.id, 24 * 3600_000L)) { it.remove(); continue; }
                if (looked++ > 8) break;
                if (recentArtist(t, 3)) continue;
                double sc = s.artistScore(t) + s.tagScore(t.tags) + rnd.nextDouble() - 2 * penalty(t);
                if (sc > bestScore) { bestScore = sc; best = t; }
            }
            if (best != null) similarPool.remove(best);
        }
        return best;
    }

    private int fills;

    private void fillSimilar() {
        // Через раз ещё песни любимого исполнителя, которых пользователь не знает: тот же человек, другая песня
        if (fills++ % 2 == 1) moreFromArtist();
        for (int k = 0; k < 2; k++) {
            Track seedT;
            synchronized (this) { seedT = familiar(); }
            if (seedT == null) return;
            try {
                List<Track> r = radioOf(seedT.id);
                List<Track> add = new ArrayList<>();
                for (int i = 1; i < r.size(); i++) {
                    Track t = r.get(i);
                    if (s.disliked.contains(t.id) || queued.contains(t.id) || s.recentlyPlayed(t.id, 24 * 3600_000L)) continue;
                    if (t.durationMs > maxLen() || s.artistScore(t) < -1) continue;
                    t.kind = Track.SIMILAR;
                    String m = Tags.main(t.tags);
                    t.badge = m.isEmpty() ? "⚡ Похоже на ваш вкус" : "⚡ Похоже на ваш вкус · " + m;
                    add.add(t);
                }
                Collections.shuffle(add, rnd);
                synchronized (this) {
                    Set<String> have = new HashSet<>();
                    for (Track x : similarPool) have.add(x.id);
                    int n = 0;
                    for (Track x : add) if (n < 8 && have.add(x.id)) { similarPool.add(x); n++; }
                }
            } catch (Exception e) {
                Log.w(tag, "радио не загрузилось: " + e);
            }
        }
    }

    private void moreFromArtist() {
        Track seedT;
        synchronized (this) { seedT = familiar(); }
        if (seedT == null || seedT.artists().isEmpty()) return;
        String artist = seedT.artists().get(0);
        try {
            List<Track> got = source.songsOf(artist);
            List<Track> add = new ArrayList<>();
            for (Track t : got) {
                if (s.known(t.id) || queued.contains(t.id) || s.dead.contains(t.id) || t.durationMs > maxLen()) continue;
                if (!t.artists().contains(artist)) continue;
                t.kind = Track.SIMILAR;
                t.badge = "★ Ещё у " + seedT.artist.split(",| & ")[0].trim();
                add.add(t);
            }
            Collections.shuffle(add, rnd);
            synchronized (this) { for (int i = 0; i < Math.min(3, add.size()); i++) similarPool.add(add.get(i)); }
        } catch (Exception e) {
            Log.w(tag, "песни исполнителя не загрузились: " + e.getMessage());
        }
    }

    // ===== «Новое для вас» =====

    private List<Track> genNew(int n) {
        List<Track> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < n && guard++ < n * 4 && !closed) {
            String b = chooseBucket();
            Track t = drawFresh(b);
            recentBuckets.addLast(b);
            while (recentBuckets.size() > 3) recentBuckets.pollFirst();
            if (t == null) continue;
            queued.add(t.id);
            remember(t);
            out.add(t);
            emit(NEW, t, out.size() == 1);
        }
        return out;
    }

    private synchronized String chooseBucket() {
        List<Double> w = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Object[] b : buckets) {
            String k = (String) b[0];
            // Жанр не повторяется раньше, чем через три трека. У второго вкуса радио от своих песен можно подряд
            if (recentBuckets.contains(k) && !(buckets == SOFT_BUCKETS && k.equals("handshake"))) continue;
            int tag = (Integer) b[2];
            double v = bucketWeight.get(k) * Math.max(0.3, 1 + 0.3 * (tag == 0 ? 0 : s.tagScore(tag)));
            keys.add(k);
            w.add(v);
        }
        return keys.get(pick(w));
    }

    private Object[] bucket(String key) {
        for (Object[] b : buckets) if (b[0].equals(key)) return b;
        return buckets[0];
    }

    private Track drawFresh(String key) {
        ArrayDeque<Track> pool;
        synchronized (this) {
            pool = bucketPool.get(key);
            if (pool == null) { pool = new ArrayDeque<>(); bucketPool.put(key, pool); }
        }
        if (pool.size() < 3) fillBucket(key, pool);
        Object[] b = bucket(key);
        Track t = null;
        synchronized (this) {
            for (Iterator<Track> it = pool.iterator(); it.hasNext(); ) {
                Track x = it.next();
                it.remove();
                if (!isFresh(x) || recentArtist(x, 6)) continue;
                t = x;
                break;
            }
        }
        if (t == null) return null;
        t.kind = Track.FRESH;
        t.bucket = key;
        t.badge = (String) b[1];
        t.tags |= (Integer) b[2];
        return t;
    }

    // Жёсткий фильтр новизны: ни трека, ни исполнителя пользователь раньше не слышал
    private boolean isFresh(Track t) {
        if (s.known(t.id) || queued.contains(t.id) || s.dead.contains(t.id) || banned(t, NEW)) return false;
        if (westernNew && (t.tags & Tags.RU) != 0) return false;
        if (t.durationMs > 0 && (t.durationMs > maxLen() || t.durationMs < MIN_LEN)) return false;
        for (String a : t.artists()) if (s.knownArtists.contains(a) || (s.profile.isEmpty() && BUBBLE.contains(a)) || s.score(a) < 0) return false;
        return !t.artists().isEmpty();
    }

    private void fillBucket(String key, ArrayDeque<Track> pool) {
        String[] sources = (String[]) bucket(key)[3];
        List<Track> got = new ArrayList<>();
        try {
            String src = sources[rnd.nextInt(sources.length)];
            if (src.startsWith("genre:")) {
                String g = src.substring(6);
                List<String> lists = genreLists.get(g);
                if (lists == null) {
                    if (genreParams == null) genreParams = source.genres();
                    String params = genreParams.get(g);
                    lists = params == null ? new ArrayList<String>() : source.genrePlaylists(params);
                    genreLists.put(g, lists);
                }
                List<String> fresh = new ArrayList<>();
                for (String l : lists) if (!usedLists.contains(l)) fresh.add(l);
                if (fresh.isEmpty()) { for (String l : lists) usedLists.remove(l); fresh.addAll(lists); }
                // Подборки YouTube Music идут первыми, из них берём чаще
                for (int i = 0; i < 2 && !fresh.isEmpty(); i++) {
                    String l = fresh.remove(rnd.nextInt(Math.min(fresh.size(), 6)));
                    usedLists.add(l);
                    got.addAll(source.playlist(l));
                }
            } else if (src.startsWith("chart:")) {
                got.addAll(source.charts(src.substring(6)));
            } else if (src.equals("new")) {
                if (albums == null || albums.isEmpty()) albums = source.newAlbums();
                Collections.shuffle(albums, rnd);
                for (int i = 0; i < 4 && !albums.isEmpty(); i++) {
                    List<Track> a = source.album(albums.remove(0));
                    if (!a.isEmpty()) got.add(a.get(Math.min(a.size() - 1, rnd.nextInt(Math.min(3, a.size())))));
                }
            } else if (src.equals("radio")) {
                // «Два рукопожатия»: радио от своего трека, но только незнакомые исполнители
                for (int k = 0; k < 2; k++) {
                    Track seedT;
                    synchronized (this) { seedT = familiar(); }
                    if (seedT != null) got.addAll(radioOf(seedT.id));
                }
            }
        } catch (Exception e) {
            Log.w(tag, "корзина " + key + " не загрузилась: " + e);
        }
        Collections.shuffle(got, rnd);
        synchronized (this) { for (Track t : got) if (isFresh(t)) pool.add(t); }
    }
}
