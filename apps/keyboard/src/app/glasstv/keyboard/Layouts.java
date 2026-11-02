package app.glasstv.keyboard;

import java.util.ArrayList;

// Раскладки. Ширина ряда 24 единицы, одна буква 2 единицы. Клавиши-рамка (регистр, стирание, язык, пробел, «Готово») на одних и тех же
// местах в любом языке, цифры всегда в одном ряду: курсор при смене языка остаётся на месте и ничего не прыгает.
// Сверху ряд знаков, он зависит от поля: в пароле знаки пароля, в адресе знаки адреса, в почте @ и домены
final class Layouts {
    private Layouts() { }

    static final int RU = 0, EN = 1;

    // Поле: что в нём обычно набирают
    static final int F_TEXT = 0, F_URL = 1, F_PASSWORD = 2, F_EMAIL = 3, F_NUMBER = 4;

    // Что делает клавиша, если это не просто знак
    static final int TEXT = 0, BACKSPACE = 1, SHIFT = 2, LANG = 3, LAYER = 4, LEFT = 5, RIGHT = 6, SPACE = 7, ENTER = 8, EYE = 9, NEWLINE = 10, PAGE = 11, ABC = 12;

    static final class Key {
        final String text;     // что вставляется (знак, домен) или подпись
        final int kind, x, w;  // x и w в единицах
        final boolean quick;   // ряд знаков сверху: чуть тише остальных

        Key(String text, int kind, int x, int w, boolean quick) {
            this.text = text;
            this.kind = kind;
            this.x = x;
            this.w = w;
            this.quick = quick;
        }

        boolean isLetter() { return kind == TEXT && text.length() == 1 && Character.isLetter(text.charAt(0)); }
    }

    private static final String Q_TEXT_RU = "? ! : ; ( ) \" « » № @ #";
    private static final String Q_TEXT_EN = "! : ; ( ) \" / @ # & + =";
    private static final String Q_URL = "https:// www. .com .ru / : ? = & # % _";
    // Те же знаки над теми же цифрами, что Shift+цифра на обычной клавиатуре
    private static final String Q_PASSWORD = "~ ! @ # $ % ^ & * ( ) _";

    private static final String[][] SYMBOLS = {
            {"~ ! @ # $ % ^ & * ( ) _", "+ = [ ] { } \\ | < > /", "' \" ` : ; ? € £ ₽"},
            {"№ § ° • « » „ “ ” ‘ ’ …", "¥ ¢ ± × ÷ ≈ ≠ © ® ™ ¿", "¡ ‹ › ₴ ₸ ¤ ← → ↑"},
    };

    // layer: 0 буквы, 1 и 2 страницы знаков
    static Key[][] build(int field, int lang, int layer, boolean multiline) { return build(field, lang, layer, multiline, false); }

    static Key[][] build(int field, int lang, int layer, boolean multiline, boolean pin) {
        if (field == F_NUMBER) return number(pin);
        ArrayList<Key[]> rows = new ArrayList<>(6);
        rows.add(quick(field, lang));
        rows.add(digits(lang));
        if (layer > 0) symbolRows(rows, layer, multiline);
        else if (lang == RU) russian(rows, multiline);
        else english(rows, multiline);
        rows.add(new Key[]{
                new Key("", LANG, 0, 3, false),
                new Key("", LAYER, 3, 2, false),
                new Key("◀", LEFT, 5, 2, false),
                new Key("▶", RIGHT, 7, 2, false),
                new Key("⌫", BACKSPACE, 9, 3, false),
                new Key("пробел", SPACE, 12, 6, false),
                new Key("", EYE, 18, 2, false),
                new Key("", ENTER, 20, 4, false),
        });
        return rows.toArray(new Key[0][]);
    }

    private static Key[] quick(int field, int lang) {
        if (field == F_EMAIL) {
            return new Key[]{q("@", 0, 2), q("_", 2, 2), q("+", 4, 2), q("@gmail.com", 6, 3), q("@icloud.com", 9, 3),
                    q("@mail.ru", 12, 3), q("@yandex.ru", 15, 3), q(".com", 18, 3), q(".ru", 21, 3)};
        }
        String line = field == F_URL ? Q_URL : field == F_PASSWORD ? Q_PASSWORD : lang == RU ? Q_TEXT_RU : Q_TEXT_EN;
        String[] parts = line.split(" ");
        Key[] out = new Key[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = q(parts[i], i * 2, 2);
        return out;
    }

    private static Key q(String s, int x, int w) { return new Key(s, TEXT, x, w, true); }

    private static Key t(String s, int x, int w) { return new Key(s, TEXT, x, w, false); }

    private static Key[] digits(int lang) {
        Key[] row = new Key[12];
        row[0] = t(lang == RU ? "ё" : "'", 0, 2);
        String d = "1234567890";
        for (int i = 0; i < 10; i++) row[i + 1] = t(d.substring(i, i + 1), 2 + i * 2, 2);
        row[11] = t("-", 22, 2);
        return row;
    }

    // Перенос строки только там, где он есть: в одной строке вторая кнопка рядом с буквами лишь выбрасывала из поля случайным нажатием
    private static Key[] withNewline(Key[] row, int x, int w, boolean multiline) {
        return multiline ? plus(row, new Key("⏎", NEWLINE, x, w, false)) : row;
    }

    private static void russian(ArrayList<Key[]> rows, boolean multiline) {
        rows.add(line("й ц у к е н г ш щ з х ъ", 0));
        rows.add(withNewline(line("ф ы в а п р о л д ж э", 0), 22, 2, multiline));
        Key[] three = line("я ч с м и т ь б ю", 2);
        rows.add(wrap(new Key("⇧", SHIFT, 0, 2, false), three, t(",", 20, 2), t(".", 22, 2)));
    }

    private static void english(ArrayList<Key[]> rows, boolean multiline) {
        rows.add(line("q w e r t y u i o p", 2));
        // Столбцы ровные, как в русской: куда прыгнет стрелка, видно по картинке
        rows.add(withNewline(line("a s d f g h j k l", 2), 20, 4, multiline));
        Key[] three = line("z x c v b n m", 4);
        rows.add(wrap(new Key("⇧", SHIFT, 0, 4, false), three, t("?", 18, 2), t(",", 20, 2), t(".", 22, 2)));
    }

    private static void symbolRows(ArrayList<Key[]> rows, int layer, boolean multiline) {
        String[] s = SYMBOLS[layer - 1];
        rows.add(line(s[0], 0));
        rows.add(withNewline(line(s[1], 0), 22, 2, multiline));
        rows.add(wrap(new Key(layer + "/2", PAGE, 0, 2, false), line(s[2], 2), t(",", 20, 2), t(".", 22, 2)));
    }

    private static Key[][] number(boolean pin) {
        return new Key[][]{
                {t("-", 0, 2), t("1", 2, 2), t("2", 4, 2), t("3", 6, 2), t("4", 8, 2), t("5", 10, 2), t("6", 12, 2), t("7", 14, 2),
                        t("8", 16, 2), t("9", 18, 2), t("0", 20, 2), new Key("⌫", BACKSPACE, 22, 2, false)},
                {pin ? new Key("", EYE, 0, 2, false) : t(".", 0, 2), t(",", 2, 2), t("+", 4, 2), t("*", 6, 2), t("#", 8, 2), t("/", 10, 2), t(":", 12, 2),
                        new Key("◀", LEFT, 14, 2, false), new Key("▶", RIGHT, 16, 2, false),
                        new Key("ABC", ABC, 18, 2, false), new Key("", ENTER, 20, 4, false)},
        };
    }

    private static Key[] line(String s, int firstX) {
        String[] parts = s.split(" ");
        Key[] out = new Key[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = t(parts[i], firstX + i * 2, 2);
        return out;
    }

    private static Key[] plus(Key[] row, Key last) {
        Key[] out = new Key[row.length + 1];
        System.arraycopy(row, 0, out, 0, row.length);
        out[row.length] = last;
        return out;
    }

    private static Key[] wrap(Key first, Key[] middle, Key... tail) {
        Key[] out = new Key[1 + middle.length + tail.length];
        out[0] = first;
        System.arraycopy(middle, 0, out, 1, middle.length);
        System.arraycopy(tail, 0, out, 1 + middle.length, tail.length);
        return out;
    }
}
