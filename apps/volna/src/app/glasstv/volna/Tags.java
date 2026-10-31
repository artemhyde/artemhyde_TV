package app.glasstv.volna;

import java.util.Locale;
import java.util.regex.Pattern;

// Грубые жанровые метки по названию и исполнителю: хватает, чтобы чередовать стили и учитывать лайки
final class Tags {
    static final int PHONK = 1, SLOWED = 2, HOODTRAP = 4, RU = 8, CHILL = 16, WORSHIP = 32,
            HIPHOP = 64, ELECTRONIC = 128, RNB = 256, ROCK = 512, POP = 1024, LATIN = 2048;
    static final int COUNT = 12;

    static final String[] NAMES = {"Фонк", "Slowed", "Hoodtrap", "Русское", "Чилл", "Worship",
            "Хип-хоп", "Электроника", "R&B", "Рок и инди", "Поп", "Латино"};

    // Слова ищутся целиком (не «rave» внутри «Brave», не «бог» внутри «Богдан»)
    private static final Pattern P_PHONK = rx("phonk|montagem|funk|baile|brazilian|batidão|batidao|automotivo");
    private static final Pattern P_SLOWED = rx("slowed|sped up|speed up|reverb|nightcore");
    private static final Pattern P_HOOD = rx("hoodtrap|jerk|jersey club|jersey");
    private static final Pattern P_CYR = Pattern.compile("[а-яё]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern P_CHILL = rx("lofi|lo-fi|chill|ambient|sleep|piano|acoustic|instrumental|type beat|guitar|calm");
    private static final Pattern P_WORSHIP = rx("jesus|worship|psalm|psalms|hymn|hymns|gospel|christian|иисус|иисуса|псалом|церковь|бог|бога|богу|господь|господи|христос|хвала");
    private static final Pattern P_HIPHOP = rx("rap|hip hop|hip-hop|trap|drill|рэп|реп|feat\\.|ft\\.");
    private static final Pattern P_ELECTRO = rx("house|edm|techno|electro|electronic|dance|bass|dubstep|hardtekk|club|trance|rave|dnb");
    private static final Pattern P_RNB = rx("r&b|rnb|soul");
    private static final Pattern P_ROCK = rx("rock|metal|punk|indie|alternative|grunge");
    private static final Pattern P_LATIN = rx("reggaeton|latin|bachata|perreo|cumbia");

    private static Pattern rx(String words) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + words + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static int of(String title, String artist) {
        String s = (title + " " + artist).toLowerCase(Locale.ROOT);
        int t = 0;
        if (P_PHONK.matcher(s).find()) t |= PHONK;
        if (P_SLOWED.matcher(s).find()) t |= SLOWED;
        if (P_HOOD.matcher(s).find()) t |= HOODTRAP;
        if (P_CYR.matcher(s).find()) t |= RU;
        if (P_CHILL.matcher(s).find()) t |= CHILL;
        if (P_WORSHIP.matcher(s).find()) t |= WORSHIP;
        if (P_HIPHOP.matcher(s).find()) t |= HIPHOP;
        if (P_ELECTRO.matcher(s).find()) t |= ELECTRONIC;
        if (P_RNB.matcher(s).find()) t |= RNB;
        if (P_ROCK.matcher(s).find()) t |= ROCK;
        if (P_LATIN.matcher(s).find()) t |= LATIN;
        return t;
    }

    // Главная метка трека для бейджа «Похоже: фонк» и чередования
    static String main(int tags) {
        int[] order = {WORSHIP, PHONK, HOODTRAP, HIPHOP, ELECTRONIC, RNB, ROCK, LATIN, CHILL, POP, RU, SLOWED};
        for (int b : order) if ((tags & b) != 0) return NAMES[Integer.numberOfTrailingZeros(b)];
        return "";
    }
}
