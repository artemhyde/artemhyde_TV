package app.glasstv.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Date;
import java.util.regex.Pattern;

// Последний настоящий просмотр: фильм, серия, видео. Одна запись в prefs «watch»: пишет её Watch,
// читает главный экран для капсулы «Продолжить». Здесь же правила, когда капсулу не показывать, и её подпись
final class Last {
    static final String ST = "org.smarttube.stable", JP = "com.brouken.player", VK = "com.vk.tv", LAMPA = "top.rootu.lampa";
    // Вид просмотра: от него зависит, как вернуть на место
    static final String K_ST = "smarttube", K_FILE = "file", K_LAMPA = "lampa", K_VK = "vk";
    static final long MAX_AGE = 7 * 86_400_000L;   // старше недели не предлагаем
    static final long MIN_POS = 60_000;            // меньше минуты это не просмотр
    static final long MIN_LEFT = 120_000;          // осталось меньше двух минут: досмотрели
    static final float DONE = 0.96f;               // или прошло больше 96 %
    static final long ST_MIN = 180_000;            // ролики SmartTube короче трёх минут и прямые эфиры не считаются
    private static final String PREFS = "watch";
    private static final Pattern EXT = Pattern.compile(
            "(?i)\\.(mkv|mp4|m4v|avi|mov|ts|m2ts|mts|webm|wmv|flv|mpg|mpeg|vob|3gp|ogv)$");

    static final class Rec {
        final String pkg, kind, title, id;
        final long pos, dur, at;   // позиция и длительность в мс, время записи (часы устройства)

        Rec(String pkg, String kind, String title, String id, long pos, long dur, long at) {
            this.pkg = pkg;
            this.kind = kind;
            this.title = title;
            this.id = id;
            this.pos = pos;
            this.dur = dur;
            this.at = at;
        }

        // Тот же ролик или файл, что в записи
        boolean same(String pkg, String id, String title) {
            return this.pkg.equals(pkg) && this.id.equals(id) && this.title.equals(title);
        }

        @Override public String toString() {
            return kind + " " + pkg + " «" + title + "» id=" + id + " " + pos / 1000 + "/" + dur / 1000 + " с, записано " + new Date(at);
        }
    }

    static Rec read(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pkg = p.getString("pkg", "");
        if (pkg.isEmpty()) return null;
        return new Rec(pkg, p.getString("kind", ""), p.getString("title", ""), p.getString("id", ""),
                p.getLong("pos", 0), p.getLong("dur", 0), p.getLong("at", 0));
    }

    // Все поля одной правкой: экран не увидит запись наполовину новую
    static void write(Context c, Rec r) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("pkg", r.pkg).putString("kind", r.kind).putString("title", r.title).putString("id", r.id)
                .putLong("pos", r.pos).putLong("dur", r.dur).putLong("at", r.at).apply();
    }

    // Что предложить. Запись от Watch, а если после неё Lampa долго простояла на экране (её встроенный плеер сессии
    // не даёт), то просмотр в Lampa без названия. null: предлагать нечего
    static Rec pick(Context c) {
        Rec r = read(c);
        long now = System.currentTimeMillis();
        long le = Usage.lampaEnd();
        if (le > 0 && now - le <= MAX_AGE && (r == null || le > r.at)) return new Rec(LAMPA, K_LAMPA, "", "", 0, 0, le);
        return r != null && shown(r, now) ? r : null;
    }

    // Когда капсулу не показывать: запись старая, это не просмотр (меньше минуты), или уже досмотрели
    static boolean shown(Rec r, long now) {
        if (now - r.at > MAX_AGE || r.pos < MIN_POS) return false;
        return r.dur <= 0 || (r.dur - r.pos >= MIN_LEFT && r.pos <= r.dur * DONE);
    }

    // Подпись капсулы: {название, хвост}. Хвост «осталось N мин» капсула держит на виду, длинное название ужимает
    static String[] caption(Rec r) {
        String left = r.dur > 0 ? "осталось " + Math.max(1, Math.round((r.dur - r.pos) / 60_000f)) + " мин" : "";
        switch (r.kind) {
            case K_ST: return new String[]{r.title.isEmpty() ? "YouTube" : r.title, left};
            case K_FILE: return new String[]{r.title.isEmpty() ? "Файл" : name(r.title), left};
            case K_LAMPA:
                return r.title.isEmpty() ? new String[]{"Lampa · История", ""} : new String[]{name(r.title), left};
            case K_VK: return new String[]{"VK Видео", ""};
            default: return new String[]{r.title.isEmpty() ? "Видео" : r.title, ""};
        }
    }

    // Имя файла для людей: без расширения, без пометок раздачи в скобках ([WEB-DL 1080p], (2019)), а если пробелов нет,
    // плюсы, точки и подчёркивания между словами становятся пробелами (так приходят имена торрент-файлов из Lampa)
    static String name(String s) {
        String raw = s;
        s = EXT.matcher(s).replaceFirst("");
        if (s.indexOf(' ') < 0) s = s.replace('+', ' ').replace('.', ' ').replace('_', ' ');
        s = s.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? raw : s;
    }
}
