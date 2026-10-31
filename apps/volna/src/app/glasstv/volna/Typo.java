package app.glasstv.volna;

import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.widget.TextView;

// Русская типографика для экрана: короткие слова не висят в конце строки, тире длинное и не отрывается,
// строки абзаца выравниваются по длине (без одинокого слова в хвосте)
final class Typo {
    private static final char NBSP = ' ';

    static String fix(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\s\\u00A0]+[-–—][\\s\\u00A0]+", NBSP + "— ");
        // Однобуквенные и двухбуквенные слова приклеиваются к следующему: «и», «в», «не», «на», «за»...
        s = s.replaceAll("(?<![\\p{L}\\p{N}])(\\p{L}{1,2}) ", "$1" + NBSP);
        s = s.replaceAll("(?iu)(?<![\\p{L}])(для|над|под|при|без|про|что|как|его|ибо) ", "$1" + NBSP);
        // Повтор: после склейки рядом могли оказаться два коротких слова подряд
        s = s.replaceAll("(?<![\\p{L}\\p{N}])(\\p{L}{1,2}) ", "$1" + NBSP);
        return s;
    }

    // «1 Пет 4:8», «Мк 11:24»: номер книги и главу не разрывать
    static String ref(String r) {
        if (r == null) return "";
        return r.replaceAll("^(\\d) ", "$1" + NBSP).replaceAll(" (?=\\d)", String.valueOf(NBSP));
    }

    static void balanced(TextView t) {
        t.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
        t.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }
}
