package app.glasstv.volna;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Текст песни с открытой базы LRCLIB: синхронный, если есть, иначе обычный.
// Для slowed/sped up версий время строк растягивается под длину трека
final class Lyrics {
    static final class Line {
        final long t;
        final String s;
        Line(long t, String s) { this.t = t; this.s = s; }
    }

    final List<Line> lines = new ArrayList<>();
    boolean synced;

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern NOISE = Pattern.compile(
            "\\s*[\\(\\[][^\\)\\]]*(slowed|sped|speed|reverb|official|lyric|audio|video|visuali|prod|feat|ft\\.|remix|version|edit|mix|instrumental)[^\\)\\]]*[\\)\\]]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EDIT = Pattern.compile("slowed|sped up|speed up|nightcore|reverb", Pattern.CASE_INSENSITIVE);

    static final Lyrics NOT_FOUND = new Lyrics();

    // null: сеть подвела (можно повторить); NOT_FOUND: такой песни в базе нет
    static Lyrics fetch(String title, String artist, long durMs) {
        boolean edit = EDIT.matcher(title).find();
        String t = NOISE.matcher(title).replaceAll("").replace('|', ' ').trim();
        String a = artist.split("\\s*(?:&|,| x | feat\\. )\\s*")[0].trim();
        if (t.contains(" - ") && a.isEmpty()) { a = t.substring(0, t.indexOf(" - ")); t = t.substring(t.indexOf(" - ") + 3); }
        if (t.toLowerCase().startsWith(a.toLowerCase() + " - ")) t = t.substring(a.length() + 3);
        try {
            JSONArray res = get("https://lrclib.net/api/search?track_name=" + enc(t) + "&artist_name=" + enc(a));
            if (res.length() == 0) res = get("https://lrclib.net/api/search?q=" + enc(t + " " + a));
            JSONObject best = null;
            double bestScore = -1e9;
            for (int i = 0; i < res.length(); i++) {
                JSONObject o = res.getJSONObject(i);
                if (o.optBoolean("instrumental")) continue;
                boolean syn = !o.optString("syncedLyrics", "").isEmpty() && !o.isNull("syncedLyrics");
                boolean plain = !o.optString("plainLyrics", "").isEmpty() && !o.isNull("plainLyrics");
                if (!syn && !plain) continue;
                double d = o.optDouble("duration", 0) * 1000;
                // Не та песня: другое название, или длина сильно другая у обычной (не slowed) версии
                String on = o.optString("trackName", "").toLowerCase(), want = t.toLowerCase();
                if (!on.isEmpty() && !on.contains(want) && !want.contains(on)) continue;
                if (!edit && durMs > 0 && d > 0 && Math.abs(durMs - d) > 10_000) continue;
                double score = (syn ? 100 : 0) - (durMs > 0 && d > 0 ? Math.abs(durMs - d) / 1000.0 : 30);
                if (score > bestScore) { bestScore = score; best = o; }
            }
            if (best == null) return NOT_FOUND;
            Lyrics l = new Lyrics();
            String syn = best.isNull("syncedLyrics") ? "" : best.optString("syncedLyrics", "");
            if (!syn.isEmpty()) {
                double d = best.optDouble("duration", 0) * 1000;
                double k = 1;
                // Растягиваем время строк только у slowed/sped up версий и только в правдоподобных пределах
                if (edit && durMs > 0 && d > 0) {
                    k = durMs / d;
                    if (k < 0.65 || k > 1.6 || Math.abs(k - 1) < 0.03) k = 1;
                }
                for (String row : syn.split("\n")) {
                    Matcher m = TAG.matcher(row);
                    List<Long> times = new ArrayList<>();
                    int end = 0;
                    while (m.find()) {
                        times.add((long) ((Long.parseLong(m.group(1)) * 60_000 + Double.parseDouble(m.group(2)) * 1000) * k));
                        end = m.end();
                    }
                    String text = row.substring(end).trim();
                    for (long tm : times) l.lines.add(new Line(tm, text.isEmpty() ? "♪" : text));
                }
                l.lines.sort((x, y) -> Long.compare(x.t, y.t));
                l.synced = !l.lines.isEmpty();
            }
            if (l.lines.isEmpty()) {
                for (String row : best.optString("plainLyrics", "").split("\n")) l.lines.add(new Line(-1, row.trim()));
            }
            return l.lines.isEmpty() ? NOT_FOUND : l;
        } catch (Exception e) {
            android.util.Log.w("volna", "текст песни не загрузился", e);
            return null;
        }
    }

    int indexAt(long pos) {
        if (!synced) return -1;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) { if (lines.get(i).t <= pos + 250) idx = i; else break; }
        return idx;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    // LRCLIB часто отвечает 503 под нагрузкой: до четырёх попыток с растущей паузой
    private static JSONArray get(String url) throws Exception {
        Exception last = null;
        for (int i = 0; i < 4; i++) {
            try {
                return getOnce(url);
            } catch (Exception e) {
                last = e;
                Thread.sleep(800L * (i + 1));
            }
        }
        throw last;
    }

    private static JSONArray getOnce(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", Config.USER_AGENT);
            int code = c.getResponseCode();
            if (code == 404) return new JSONArray();
            if (code != 200) throw new Exception("LRCLIB " + code);
            return new JSONArray(Net.read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }
}
