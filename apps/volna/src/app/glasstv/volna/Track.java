package app.glasstv.volna;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Один трек очереди: что играть и откуда он взялся (для бейджа)
final class Track {
    static final int FAMILIAR = 0, SIMILAR = 1, FRESH = 2;

    final String id, title, artist;
    final long durationMs;
    String badge = "";
    int kind = FAMILIAR;
    int tags;
    String bucket = "";   // для «Нового»: из какой корзины жанров

    Track(String id, String title, String artist, long durationMs) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.durationMs = durationMs;
        this.tags = Tags.of(this.title, this.artist);
    }

    Track copy() {
        Track t = new Track(id, title, artist, durationMs);
        t.badge = badge;
        t.kind = kind;
        t.tags = tags;
        t.bucket = bucket;
        return t;
    }

    // Исполнители по отдельности, в нижнем регистре: для фильтра «новые артисты»
    List<String> artists() {
        List<String> out = new ArrayList<>();
        for (String a : artist.split("\\s*(?:&|,| x | X | feat\\. | ft\\. |•)\\s*")) {
            String s = a.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static long parseDuration(String s) {
        if (s == null) return 0;
        String[] p = s.trim().split(":");
        try {
            long v = 0;
            for (String x : p) v = v * 60 + Long.parseLong(x.trim());
            return v * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
