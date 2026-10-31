package app.glasstv.volna;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Открытые запросы к YouTube Music без входа: радио по треку, плейлисты, чарты, новинки, жанры.
// Это те же запросы, что делает сайт music.youtube.com в браузере; сам звук отсюда не берём, его играет Aura.
final class Net {
    static final String VER = "1.20261020.10.00";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36";

    interface Fn { void on(JSONObject o); }

    // Сеть пропала (ТВ уснул, нет интернета): после двух сбоев подряд ждём 30 с, потом дольше (до 5 минут),
    // а очередь тем временем берётся из своей базы. Один случайный таймаут не блокирует
    private static volatile long downUntil;
    private static volatile int failures;

    static boolean down() { return android.os.SystemClock.elapsedRealtime() < downUntil; }

    static JSONObject call(String endpoint, JSONObject body, String gl) throws Exception {
        if (down()) throw new Exception("сеть недоступна, жду");
        try {
            JSONObject j = callOnce(endpoint, body, gl);
            failures = 0;
            return j;
        } catch (Exception e) {
            int f = ++failures;
            if (f >= 2) downUntil = android.os.SystemClock.elapsedRealtime() + Math.min(300_000L, 30_000L << Math.min(4, f - 2));
            throw e;
        }
    }

    private static JSONObject callOnce(String endpoint, JSONObject body, String gl) throws Exception {
        body.put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", VER).put("hl", "en").put("gl", gl)));
        HttpURLConnection c = (HttpURLConnection) new URL("https://music.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false").openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Origin", "https://music.youtube.com");
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    // Обходит JSON и отдаёт каждый объект, лежащий под ключом key
    static void walk(Object o, String key, Fn fn) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Object hit = j.opt(key);
            if (hit instanceof JSONObject) fn.on((JSONObject) hit);
            Iterator<String> it = j.keys();
            while (it.hasNext()) walk(j.opt(it.next()), key, fn);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), key, fn);
        }
    }

    static String text(JSONObject r) {
        if (r == null) return "";
        String s = r.optString("simpleText", "");
        if (!s.isEmpty()) return s;
        JSONArray runs = r.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) b.append(runs.optJSONObject(i).optString("text"));
        return b.toString();
    }

    static String pageType(JSONObject run) {
        JSONObject be = run.optJSONObject("navigationEndpoint");
        be = be == null ? null : be.optJSONObject("browseEndpoint");
        if (be == null) return "";
        JSONObject cfg = be.optJSONObject("browseEndpointContextSupportedConfigs");
        cfg = cfg == null ? null : cfg.optJSONObject("browseEndpointContextMusicConfig");
        return cfg == null ? "" : cfg.optString("pageType");
    }

    // Строка списка (плейлист, чарт, альбом): id, название, исполнители, длительность
    static Track listItem(JSONObject it, String fallbackArtist) {
        final String[] vid = {null};
        JSONObject pid = it.optJSONObject("playlistItemData");
        if (pid != null) vid[0] = pid.optString("videoId", null);
        if (vid[0] == null) walk(it, "watchEndpoint", w -> { if (vid[0] == null && w.has("videoId")) vid[0] = w.optString("videoId"); });
        if (vid[0] == null) return null;
        JSONArray fc = it.optJSONArray("flexColumns");
        if (fc == null || fc.length() == 0) return null;
        String title = text(col(fc, 0));
        StringBuilder artists = new StringBuilder();
        String dur = "";
        for (int i = 1; i < fc.length(); i++) {
            JSONArray runs = col(fc, i).optJSONArray("runs");
            if (runs == null) continue;
            for (int k = 0; k < runs.length(); k++) {
                JSONObject r = runs.optJSONObject(k);
                String pt = pageType(r);
                if (pt.equals("MUSIC_PAGE_TYPE_ARTIST") || pt.equals("MUSIC_PAGE_TYPE_USER_CHANNEL")) {
                    if (artists.length() > 0) artists.append(" & ");
                    artists.append(r.optString("text"));
                } else if (r.optString("text").matches("\\d+:\\d\\d(:\\d\\d)?")) {
                    dur = r.optString("text");
                }
            }
        }
        if (artists.length() == 0) {
            String sub = fc.length() > 1 ? text(col(fc, 1)) : "";
            String first = sub.split(" • ")[0].trim();
            artists.append(first.isEmpty() || first.equals("Song") || first.equals("Video") ? fallbackArtist : first);
        }
        JSONArray fixed = it.optJSONArray("fixedColumns");
        if (fixed != null && fixed.length() > 0) {
            JSONObject f = fixed.optJSONObject(0).optJSONObject("musicResponsiveListItemFixedColumnRenderer");
            if (f != null) dur = text(f.optJSONObject("text"));
        }
        return new Track(vid[0], title, artists.toString(), Track.parseDuration(dur));
    }

    private static JSONObject col(JSONArray fc, int i) {
        JSONObject c = fc.optJSONObject(i);
        c = c == null ? null : c.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        c = c == null ? null : c.optJSONObject("text");
        return c == null ? new JSONObject() : c;
    }

    static List<Track> listItems(JSONObject j, String fallbackArtist) {
        List<Track> out = new ArrayList<>();
        walk(j, "musicResponsiveListItemRenderer", o -> { Track t = listItem(o, fallbackArtist); if (t != null) out.add(t); });
        return out;
    }

    // Радио YouTube Music от трека: сам трек и похожие на него (первым идёт сам трек)
    static List<Track> radio(String videoId) throws Exception {
        JSONObject j = call("next", new JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM" + videoId)
                .put("isAudioOnly", true), "US");
        List<Track> out = new ArrayList<>();
        walk(j, "playlistPanelVideoRenderer", o -> {
            String id = o.optString("videoId", null);
            if (id == null) return;
            String artist = text(o.optJSONObject("shortBylineText"));
            if (artist.isEmpty()) artist = text(o.optJSONObject("longBylineText")).split(" • ")[0];
            out.add(new Track(id, text(o.optJSONObject("title")), artist, Track.parseDuration(text(o.optJSONObject("lengthText")))));
        });
        return out;
    }

    static List<Track> playlist(String playlistId) throws Exception {
        List<Track> out = listItems(call("browse", new JSONObject().put("browseId", "VL" + playlistId), "US"), "");
        for (Track t : out) t.playlist = playlistId;
        return out;
    }

    // Чарты отдаются каруселями плейлистов («Daily Top Music Videos» и подобные): берём первый и грузим его
    static List<Track> charts(String country) throws Exception {
        JSONObject j = call("browse", new JSONObject().put("browseId", "FEmusic_charts")
                .put("formData", new JSONObject().put("selectedValues", new JSONArray().put(country))), country.equals("ZZ") ? "US" : country);
        List<Track> direct = listItems(j, "");
        if (!direct.isEmpty()) return direct;
        List<String> lists = new ArrayList<>();
        walk(j, "musicTwoRowItemRenderer", o -> {
            JSONObject ne = o.optJSONObject("navigationEndpoint");
            JSONObject be = ne == null ? null : ne.optJSONObject("browseEndpoint");
            String id = be == null ? "" : be.optString("browseId");
            if (id.startsWith("VLPL") || id.startsWith("VLRDCLAK")) lists.add(id.substring(2));
        });
        return lists.isEmpty() ? direct : playlist(lists.get(0));
    }

    // Новые альбомы и синглы: id страниц альбомов
    static List<String> newAlbums() throws Exception {
        JSONObject j = call("browse", new JSONObject().put("browseId", "FEmusic_new_releases"), "US");
        List<String> out = new ArrayList<>();
        walk(j, "musicTwoRowItemRenderer", o -> {
            JSONObject ne = o.optJSONObject("navigationEndpoint");
            JSONObject be = ne == null ? null : ne.optJSONObject("browseEndpoint");
            String id = be == null ? null : be.optString("browseId", null);
            if (id != null && id.startsWith("MPREb") && !out.contains(id)) out.add(id);
        });
        return out;
    }

    static List<Track> album(String browseId) throws Exception {
        JSONObject j = call("browse", new JSONObject().put("browseId", browseId), "US");
        final String[] artist = {""};
        walk(j, "musicResponsiveHeaderRenderer", h -> { if (artist[0].isEmpty()) artist[0] = text(h.optJSONObject("straplineTextOne")); });
        return listItems(j, artist[0]);
    }

    // Жанры и настроения: название (по-английски) -> параметр страницы
    static Map<String, String> genres() throws Exception {
        JSONObject j = call("browse", new JSONObject().put("browseId", "FEmusic_moods_and_genres"), "US");
        Map<String, String> out = new LinkedHashMap<>();
        walk(j, "musicNavigationButtonRenderer", o -> {
            JSONObject cc = o.optJSONObject("clickCommand");
            JSONObject be = cc == null ? null : cc.optJSONObject("browseEndpoint");
            if (be != null) out.put(text(o.optJSONObject("buttonText")), be.optString("params"));
        });
        return out;
    }

    // Плейлисты жанра: сначала подборки самого YouTube Music (RDCLAK), потом пользовательские
    static List<String> genrePlaylists(String params) throws Exception {
        JSONObject j = call("browse", new JSONObject().put("browseId", "FEmusic_moods_and_genres_category").put("params", params), "US");
        List<String> rd = new ArrayList<>(), pl = new ArrayList<>();
        walk(j, "musicTwoRowItemRenderer", o -> {
            JSONObject ne = o.optJSONObject("navigationEndpoint");
            JSONObject be = ne == null ? null : ne.optJSONObject("browseEndpoint");
            String id = be == null ? "" : be.optString("browseId");
            if (id.startsWith("VLRDCLAK")) { if (!rd.contains(id.substring(2))) rd.add(id.substring(2)); }
            else if (id.startsWith("VLPL")) { if (!pl.contains(id.substring(2))) pl.add(id.substring(2)); }
        });
        rd.addAll(pl);
        return rd;
    }
}
