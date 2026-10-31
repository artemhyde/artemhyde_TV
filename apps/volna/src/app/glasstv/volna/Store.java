package app.glasstv.volna;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Память «Волны»: база из аккаунта (seed.tsv), что уже играло, лайки, «не моё», вес исполнителей и жанров
final class Store {
    static final class Seed {
        final Track t;
        final String sources;
        Seed(Track t, String sources) { this.t = t; this.sources = sources; }
    }

    static final String MAIN = "", SECOND = "second";

    final String profile;
    final SharedPreferences p;
    final List<Seed> seed = new ArrayList<>();
    final Set<String> seedIds = new HashSet<>();
    final Set<String> knownArtists = ConcurrentHashMap.newKeySet();   // все, кого слушал: для «Нового» их нельзя
    final Map<String, long[]> played = new ConcurrentHashMap<>(); // id -> {когда последний раз, сколько раз}
    final Set<String> liked = ConcurrentHashMap.newKeySet();
    final Set<String> disliked = ConcurrentHashMap.newKeySet();
    final Map<String, Float> artistScore = new ConcurrentHashMap<>();
    final float[] tagWeight = new float[Tags.COUNT];
    final Map<String, String[]> likedMeta = new ConcurrentHashMap<>(); // лайкнутые в «Волне»: id -> {название, исполнитель}
    private boolean dirty;

    Store(Context c, String profile) {
        this.profile = profile;
        p = c.getSharedPreferences(profile.isEmpty() ? "volna2" : "volna2_" + profile, Context.MODE_PRIVATE);
        loadSeed(c);
        try {
            JSONObject pl = new JSONObject(p.getString("played", "{}"));
            for (Iterator<String> it = pl.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = pl.getJSONArray(k);
                played.put(k, new long[]{a.getLong(0), a.getLong(1)});
            }
            readSet("liked", liked);
            readSet("disliked", disliked);
            JSONObject as = new JSONObject(p.getString("artists", "{}"));
            for (Iterator<String> it = as.keys(); it.hasNext(); ) { String k = it.next(); artistScore.put(k, (float) as.getDouble(k)); }
            JSONArray tw = new JSONArray(p.getString("tags", "[]"));
            for (int i = 0; i < Math.min(tw.length(), Tags.COUNT); i++) tagWeight[i] = (float) tw.getDouble(i);
            JSONObject lm = new JSONObject(p.getString("likedMeta", "{}"));
            for (Iterator<String> it = lm.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = lm.getJSONArray(k);
                likedMeta.put(k, new String[]{a.getString(0), a.getString(1)});
            }
            JSONArray ka = new JSONArray(p.getString("knownArtists", "[]"));
            for (int i = 0; i < ka.length(); i++) knownArtists.add(ka.getString(i));
        } catch (Exception ignored) { }
    }

    private void loadSeed(Context c) {
        int res = profile.isEmpty() ? R.raw.seed : R.raw.seed_second;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length < 5) continue;
                Track t = new Track(f[0], f[1], f[2], Track.parseDuration(f[3]));
                seed.add(new Seed(t, f[4]));
                seedIds.add(t.id);
                knownArtists.addAll(t.artists());
            }
        } catch (Exception ignored) { }
    }

    private void readSet(String key, Set<String> into) throws Exception {
        JSONArray a = new JSONArray(p.getString(key, "[]"));
        for (int i = 0; i < a.length(); i++) into.add(a.getString(i));
    }

    // Все треки вкуса для экрана «Треки»: сначала лайки, потом плейлисты, библиотека, история
    List<Track> library() {
        List<Track> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String[]> e : likedMeta.entrySet()) {
            Track t = new Track(e.getKey(), e.getValue()[0], e.getValue()[1], 0);
            t.badge = "❤ Лайк в «Волне»";
            if (seen.add(t.id)) out.add(t);
        }
        String[][] order = {{"liked", "★ Лайк"}, {"pl:", "★ Плейлист"}, {"library", "★ Библиотека"}, {"h", "↺ История"}};
        for (String[] o : order) {
            for (Seed sd : seed) {
                if (seen.contains(sd.t.id) || disliked.contains(sd.t.id)) continue;
                for (String src : sd.sources.split(",")) {
                    if (!src.startsWith(o[0])) continue;
                    Track t = new Track(sd.t.id, sd.t.title, sd.t.artist, sd.t.durationMs);
                    t.badge = o[0].equals("pl:") ? "★ «" + src.substring(3) + "»" : o[1];
                    out.add(t);
                    seen.add(t.id);
                    break;
                }
            }
        }
        for (Seed sd : seed) if (seen.add(sd.t.id) && !disliked.contains(sd.t.id)) out.add(sd.t);
        return out;
    }

    // Вкуса нет совсем: ни базы, ни лайков (второй профиль до загрузки его плейлистов)
    boolean empty() { return seed.isEmpty() && likedMeta.isEmpty(); }

    // Трек, который пользователь уже знает: из базы, игравший, лайкнутый или отвергнутый
    boolean known(String id) { return seedIds.contains(id) || played.containsKey(id) || liked.contains(id) || disliked.contains(id); }

    boolean recentlyPlayed(String id, long withinMs) {
        long[] v = played.get(id);
        return v != null && System.currentTimeMillis() - v[0] < withinMs;
    }

    void played(Track t) {
        long[] v = played.get(t.id);
        long now = System.currentTimeMillis();
        // Повторная отметка того же запуска (при выдаче и при старте в Aura) не считается вторым прослушиванием
        played.put(t.id, new long[]{now, v == null ? 1 : now - v[0] < 60_000 ? v[1] : v[1] + 1});
        knownArtists.addAll(t.artists());
        if (played.size() > 4000) trimPlayed();
        dirty = true;
    }

    private void trimPlayed() {
        List<Map.Entry<String, long[]>> e = new ArrayList<>(played.entrySet());
        e.sort((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]));
        for (int i = 0; i < e.size() - 3000; i++) played.remove(e.get(i).getKey());
    }

    void like(Track t) {
        liked.add(t.id);
        disliked.remove(t.id);
        likedMeta.put(t.id, new String[]{t.title, t.artist});
        for (String a : t.artists()) artistScore.put(a, score(a) + 1.5f);
        bumpTags(t.tags, 0.35f);
        dirty = true;
        save();
    }

    void dislike(Track t) {
        disliked.add(t.id);
        liked.remove(t.id);
        for (String a : t.artists()) artistScore.put(a, score(a) - 2f);
        bumpTags(t.tags, -0.4f);
        dirty = true;
        save();
    }

    float score(String artist) { Float f = artistScore.get(artist); return f == null ? 0 : f; }

    float artistScore(Track t) {
        float s = 0;
        for (String a : t.artists()) s += score(a);
        return s;
    }

    float tagScore(int tags) {
        float s = 0;
        for (int i = 0; i < Tags.COUNT; i++) if ((tags & (1 << i)) != 0) s += tagWeight[i];
        return s;
    }

    private void bumpTags(int tags, float d) {
        for (int i = 0; i < Tags.COUNT; i++)
            if ((tags & (1 << i)) != 0) tagWeight[i] = Math.max(-2f, Math.min(3f, tagWeight[i] + d));
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        try {
            JSONObject pl = new JSONObject();
            for (Map.Entry<String, long[]> e : played.entrySet()) pl.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            JSONObject as = new JSONObject();
            for (Map.Entry<String, Float> e : artistScore.entrySet()) as.put(e.getKey(), (double) e.getValue());
            JSONArray tw = new JSONArray();
            for (float f : tagWeight) tw.put((double) f);
            JSONObject lm = new JSONObject();
            for (Map.Entry<String, String[]> e : likedMeta.entrySet()) lm.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            JSONArray ka = new JSONArray();
            for (String a : knownArtists) ka.put(a);
            p.edit().putString("played", pl.toString()).putString("liked", new JSONArray(liked).toString())
                    .putString("disliked", new JSONArray(disliked).toString()).putString("artists", as.toString())
                    .putString("tags", tw.toString()).putString("likedMeta", lm.toString())
                    .putString("knownArtists", ka.toString()).apply();
        } catch (Exception ignored) { }
    }

    int mode() { return p.getInt("mode", Brain.MY); }

    void mode(int m) { p.edit().putInt("mode", m).apply(); }
}
