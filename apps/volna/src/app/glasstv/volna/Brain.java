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
import java.util.concurrent.Executors;

// Мозг: собирает очередь для двух волн.
// «Моя волна»: 60% своё (лайки, плейлисты, история), 40% похожее (радио YouTube Music от любимых треков).
// «Новое для вас»: только незнакомые треки и незнакомые исполнители из смежных жанров, чартов и новинок, жанры чередуются.
final class Brain {
    static final int MY = 0, NEW = 1;
    static final String[] MODE_NAMES = {"Моя волна", "Новое для вас"};
    static final long MAX_LEN = 8 * 60_000, MIN_LEN = 45_000;

    // У второго вкуса много долгих песен (по 8-10 минут): им длину не режем
    long maxLen() { return s.profile.isEmpty() ? MAX_LEN : 12 * 60_000; }

    interface Ready { void onReady(int mode); }

    // Порядок «своё / похожее» на каждые 10 треков: 6 к 4
    private static final boolean[] PATTERN = {true, true, false, true, false, true, true, false, true, false};

    // Исполнители, которых YouTube и так подсовывает пользователю (миксы): в «Новом» их не будет. В этой копии список пуст, впишите своих строчными буквами
    private static final Set<String> BUBBLE = new HashSet<>();

    // Корзины «Нового»: ключ, бейдж, жанровая метка, источники
    private static final Object[][] BUCKETS = {
            {"trend", "🔥 Новое: хип-хоп тренд", Tags.HIPHOP, new String[]{"genre:Hip-hop", "genre:Russian hip-hop"}},
            {"fresh", "🆕 Свежий релиз", 0, new String[]{"new"}},
            {"electro", "🧭 Открытие жанра: электроника", Tags.ELECTRONIC, new String[]{"genre:Dance & electronic"}},
            {"rnb", "🧭 Открытие жанра: R&B", Tags.RNB, new String[]{"genre:R&B & soul"}},
            {"world", "🌍 Мировой хит", 0, new String[]{"chart:ZZ", "chart:US"}},
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
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
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

    Brain(Store s) {
        this.s = s;
        buckets = s.profile.isEmpty() ? BUCKETS : SOFT_BUCKETS;
        for (Object[] b : BUCKETS) bucketWeight.put((String) b[0], 1f);
        // Ближе к вкусу (рэп, свежие релизы, электроника, новые артисты рядом) чаще, далёкое реже
        bucketWeight.put("trend", 1.6f);
        bucketWeight.put("fresh", 1.3f);
        bucketWeight.put("electro", 1.3f);
        bucketWeight.put("handshake", 1.4f);
        bucketWeight.put("energy", 1.1f);
        bucketWeight.put("rnb", 0.7f);
        bucketWeight.put("indie", 0.7f);
        bucketWeight.put("latin", 0.7f);
        bucketWeight.put("ost", 0.6f);
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
            if (t != null) remember(t);
        }
        return t;
    }

    private boolean stillGood(Track t, int mode) {
        if (s.disliked.contains(t.id) || s.artistScore(t) < -1) return false;
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
        synchronized (this) {
            if (filling[mode] || buf[mode].size() >= 5) return;
            filling[mode] = true;
        }
        ex.execute(() -> {
            int got = 0;
            try {
                // Треки кладутся в очередь по одному: первый готовый сразу уходит в игру
                List<Track> batch = mode == MY ? genMy(10) : genNew(10);
                got = batch.size();
            } catch (Throwable e) {
                Log.w("volna", "подбор не удался", e);
            } finally {
                synchronized (this) { filling[mode] = false; }
                // Пусто (нет сети): не дёргаем движок по кругу, пробуем снова через полминуты
                if (got == 0) main.postDelayed(() -> refillIfLow(mode), 30_000);
            }
        });
    }

    // Пользователь сказал «не моё»: выкидываем из ближайшей очереди того же исполнителя
    synchronized void onDislike(Track t) {
        List<String> arts = t.artists();
        for (ArrayDeque<Track> q : new ArrayDeque[]{buf[MY], buf[NEW], similarPool}) purge(q, arts);
        for (ArrayDeque<Track> q : bucketPool.values()) purge(q, arts);
        if (!t.bucket.isEmpty()) bucketWeight.put(t.bucket, Math.max(0.2f, bucketWeight.getOrDefault(t.bucket, 1f) - 0.4f));
    }

    synchronized void onLike(Track t) {
        if (!t.bucket.isEmpty()) bucketWeight.put(t.bucket, Math.min(5f, bucketWeight.getOrDefault(t.bucket, 1f) + 0.5f));
    }

    private void purge(ArrayDeque<Track> q, List<String> arts) {
        for (Iterator<Track> it = q.iterator(); it.hasNext(); ) {
            Track x = it.next();
            for (String a : x.artists()) if (arts.contains(a)) { it.remove(); queued.remove(x.id); break; }
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
        for (int i = 0; i < n; i++) {
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
        synchronized (this) { buf[mode].addLast(t); }
        if (first) main.post(() -> { if (ready != null) ready.onReady(mode); });
    }

    // Своё: случайный выбор с весами (лайки и плейлисты сильнее, свежая история сильнее старой)
    private Track familiar() {
        List<Track> cand = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (long fresh : new long[]{8 * 3600_000L, 2 * 3600_000L, 0}) {
            for (Store.Seed sd : s.seed) addFamiliar(cand, w, sd.t, sd.sources, fresh);
            for (Map.Entry<String, String[]> e : s.likedMeta.entrySet())
                if (!s.seedIds.contains(e.getKey()))
                    addFamiliar(cand, w, new Track(e.getKey(), e.getValue()[0], e.getValue()[1], 0), "volna", fresh);
            if (!cand.isEmpty()) break;
        }
        if (cand.isEmpty()) return null;
        Track t = cand.get(pick(w));
        Track copy = new Track(t.id, t.title, t.artist, t.durationMs);
        copy.kind = Track.FAMILIAR;
        copy.badge = t.badge;
        return copy;
    }

    private void addFamiliar(List<Track> cand, List<Double> w, Track t, String sources, long freshMs) {
        if (s.disliked.contains(t.id) || queued.contains(t.id) || t.durationMs > maxLen()) return;
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
        double k = Math.max(0.15, 1 + 0.25 * s.artistScore(t) + 0.2 * s.tagScore(t.tags));
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
        if (similarPool.size() < 4) fillSimilar();
        Track best = null;
        double bestScore = -1e9;
        int looked = 0;
        synchronized (this) {
            for (Iterator<Track> it = similarPool.iterator(); it.hasNext(); ) {
                Track t = it.next();
                if (queued.contains(t.id) || s.disliked.contains(t.id) || s.recentlyPlayed(t.id, 24 * 3600_000L)) { it.remove(); continue; }
                if (looked++ > 8) break;
                if (recentArtist(t, 3)) continue;
                double sc = s.artistScore(t) + s.tagScore(t.tags) + rnd.nextDouble();
                if (sc > bestScore) { bestScore = sc; best = t; }
            }
            if (best != null) similarPool.remove(best);
        }
        return best;
    }

    private void fillSimilar() {
        for (int k = 0; k < 2; k++) {
            Track seedT;
            synchronized (this) { seedT = familiar(); }
            if (seedT == null) return;
            try {
                List<Track> r = Net.radio(seedT.id);
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
                Log.w("volna", "радио не загрузилось", e);
            }
        }
    }

    // ===== «Новое для вас» =====

    private List<Track> genNew(int n) {
        List<Track> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < n && guard++ < n * 4) {
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

    private String chooseBucket() {
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
        synchronized (this) {
            for (Iterator<Track> it = pool.iterator(); it.hasNext(); ) {
                Track t = it.next();
                it.remove();
                if (!isFresh(t)) continue;
                if (recentArtist(t, 6)) continue;
                t.kind = Track.FRESH;
                t.bucket = key;
                t.badge = (String) b[1];
                t.tags |= (Integer) b[2];
                return t;
            }
        }
        return null;
    }

    // Жёсткий фильтр новизны: ни трека, ни исполнителя пользователь раньше не слышал
    private boolean isFresh(Track t) {
        if (s.known(t.id) || queued.contains(t.id)) return false;
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
                    if (genreParams == null) genreParams = Net.genres();
                    String params = genreParams.get(g);
                    lists = params == null ? new ArrayList<String>() : Net.genrePlaylists(params);
                    genreLists.put(g, lists);
                }
                List<String> fresh = new ArrayList<>();
                for (String l : lists) if (!usedLists.contains(l)) fresh.add(l);
                if (fresh.isEmpty()) { for (String l : lists) usedLists.remove(l); fresh.addAll(lists); }
                // Подборки YouTube Music идут первыми, из них берём чаще
                for (int i = 0; i < 2 && !fresh.isEmpty(); i++) {
                    String l = fresh.remove(rnd.nextInt(Math.min(fresh.size(), 6)));
                    usedLists.add(l);
                    got.addAll(Net.playlist(l));
                }
            } else if (src.startsWith("chart:")) {
                got.addAll(Net.charts(src.substring(6)));
            } else if (src.equals("new")) {
                if (albums == null || albums.isEmpty()) albums = Net.newAlbums();
                Collections.shuffle(albums, rnd);
                for (int i = 0; i < 4 && !albums.isEmpty(); i++) {
                    List<Track> a = Net.album(albums.remove(0));
                    if (!a.isEmpty()) got.add(a.get(Math.min(a.size() - 1, rnd.nextInt(Math.min(3, a.size())))));
                }
            } else if (src.equals("radio")) {
                // «Два рукопожатия»: радио от своего трека, но только незнакомые исполнители
                for (int k = 0; k < 2; k++) {
                    Track seedT;
                    synchronized (this) { seedT = familiar(); }
                    if (seedT != null) got.addAll(Net.radio(seedT.id));
                }
            }
        } catch (Exception e) {
            Log.w("volna", "корзина " + key + " не загрузилась", e);
        }
        Collections.shuffle(got, rnd);
        synchronized (this) { for (Track t : got) if (isFresh(t)) pool.add(t); }
    }
}
