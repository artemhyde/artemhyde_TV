package app.glasstv.home;

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

    // Книги полными словами: «Псалом 22:1» понятнее, чем «Пс 22:1»
    private static final String[][] BOOKS = {
            {"Пс", "Псалом"}, {"Ин", "Иоанна"}, {"Мф", "Матфея"}, {"Мк", "Марка"}, {"Лк", "Луки"}, {"Рим", "Римлянам"},
            {"Притч", "Притчи"}, {"Кор", "Коринфянам"}, {"Флп", "Филиппийцам"}, {"Ис", "Исаия"}, {"Еф", "Ефесянам"},
            {"Фес", "Фессалоникийцам"}, {"Евр", "Евреям"}, {"Чис", "Числа"}, {"Кол", "Колоссянам"}, {"Иак", "Иакова"},
            {"Пет", "Петра"}, {"Откр", "Откровение"}, {"Иер", "Иеремия"}, {"Плач", "Плач Иеремии"}, {"Гал", "Галатам"},
            {"Тим", "Тимофею"}, {"Нав", "Иисус Навин"}, {"Втор", "Второзаконие"}, {"Еккл", "Екклесиаст"},
            {"Мих", "Михей"}, {"Авв", "Аввакум"}, {"Соф", "Софония"}, {"Быт", "Бытие"}, {"Исх", "Исход"}};

    // «1 Петра 4:8», «Марка 11:24»: номер книги, название и главу не разрывать
    static String ref(String r) {
        if (r == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d )?(\\S+) (.*)$").matcher(r.trim());
        if (m.matches()) {
            String book = m.group(2);
            for (String[] b : BOOKS) if (b[0].equals(book)) { book = b[1]; break; }
            r = (m.group(1) == null ? "" : m.group(1)) + book + " " + m.group(3);
        }
        return r.replace(' ', NBSP);
    }

    static void balanced(TextView t) {
        t.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
        t.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }
}
