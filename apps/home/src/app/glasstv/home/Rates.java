package app.glasstv.home;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Курсы своей валюты (10.11, выпуск 1.1.1; отзыв первого пользователя: «курсы валют добавлять даёт только белорусские»).
// Источник по стране города («auto») или выбранный вручную: Беларусь Нацбанк (nbrb), Россия Банк России (cbr), Украина НБУ (nbu,
// запасной monobank: сайт НБУ закрыт для части сетей), Казахстан Нацбанк (nbk), остальные страны ЕЦБ (ecb). У ЕЦБ рубля нет
// с марта 2022 года, поэтому для «остальных» основа евро. Строка на экране как прежде у белорусского рубля: сколько своих денег
// за доллар, евро и третью валюту. Здесь только выбор источника и разбор ответов, без Android: проверяет тест на ПК
final class Rates {
    private Rates() { }

    static final String NONE = "none", AUTO = "auto", NBRB = "nbrb", CBR = "cbr", NBU = "nbu", NBK = "nbk", ECB = "ecb";
    // Криптовалюта (10.11, просьба «вместо курса валют курс криптовалюты»): биткоин, эфир и солана в долларах (пары к
    // USDT, это тот же доллар) с открытой ленты цен Binance. Подписи источника она не требует (решение: никакой
    // «рекламы» на экране, поэтому CoinGecko убран: его бесплатный доступ требует подписи). Основной адрес data-api.binance.vision
    // (только рыночные данные), запасной api.binance.com. TON не взят: на Binance он остановлен с 06.10.2026, цена там застыла
    static final String CRYPTO = "crypto";
    static final String BINANCE_URL = "https://data-api.binance.vision/api/v3/ticker/price?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22,%22SOLUSDT%22%5D";
    static final String BINANCE_URL2 = "https://api.binance.com/api/v3/ticker/price?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22,%22SOLUSDT%22%5D";

    static final String NBRB_URL = "https://api.nbrb.by/exrates/rates/%s?parammode=2";
    static final String CBR_URL = "https://www.cbr.ru/scripts/XML_daily.asp";
    static final String NBU_URL = "https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json";
    static final String MONO_URL = "https://api.monobank.ua/bank/currency";
    static final String NBK_URL = "https://nationalbank.kz/rss/rates_all.xml";
    static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";

    // Одна позиция строки: знак валюты, курс в своих деньгах, приписка («за 100»)
    static final class Item {
        final String sym, tail;
        final double v;
        Item(String sym, double v, String tail) { this.sym = sym; this.v = v; this.tail = tail; }
    }

    // Источник с учётом «auto»: по стране города (код ISO из поиска города), неизвестная страна: ЕЦБ
    static String source(String setting, String cc) {
        if (setting == null || !AUTO.equals(setting)) return setting == null ? NONE : setting;
        switch (cc == null ? "" : cc.toUpperCase(Locale.ROOT)) {
            case "BY": return NBRB;
            case "RU": return CBR;
            case "UA": return NBU;
            case "KZ": return NBK;
            default: return ECB;
        }
    }

    static String title(String src) {
        switch (src == null ? "" : src) {
            case NBRB: return "Нацбанк Беларуси";
            case CBR: return "Банк России";
            case NBU: return "Нацбанк Украины";
            case NBK: return "Нацбанк Казахстана";
            case ECB: return "Европейский ЦБ";
            case AUTO: return "по стране города";
            case CRYPTO: return "криптовалюта";
            default: return "не показывать";
        }
    }

    // Валюта страны из списка ЕЦБ; евро, нет в списке или страна неизвестна: EUR
    private static final Map<String, String> ECB_CUR = new HashMap<>();
    static {
        String[] m = {"US", "USD", "JP", "JPY", "CZ", "CZK", "DK", "DKK", "GB", "GBP", "HU", "HUF", "PL", "PLN", "RO", "RON",
                "SE", "SEK", "CH", "CHF", "LI", "CHF", "IS", "ISK", "NO", "NOK", "TR", "TRY", "AU", "AUD", "BR", "BRL",
                "CA", "CAD", "CN", "CNY", "HK", "HKD", "ID", "IDR", "IL", "ILS", "IN", "INR", "KR", "KRW", "MX", "MXN",
                "MY", "MYR", "NZ", "NZD", "PH", "PHP", "SG", "SGD", "TH", "THB", "ZA", "ZAR"};
        for (int i = 0; i < m.length; i += 2) ECB_CUR.put(m[i], m[i + 1]);
    }

    static String ecbCurrency(String cc) {
        String c = cc == null ? null : ECB_CUR.get(cc.toUpperCase(Locale.ROOT));
        return c == null ? "EUR" : c;
    }

    // ===== Разбор ответов =====

    static List<Item> nbrb(double usd, double eur, double rub100) {
        List<Item> l = new ArrayList<>();
        l.add(new Item("$", usd, ""));
        l.add(new Item("€", eur, ""));
        l.add(new Item("₽", rub100, " за 100"));
        return l;
    }

    // Банк России, XML_daily.asp: <CharCode>USD</CharCode><Nominal>1</Nominal>…<Value>82,7536</Value>. Рублей за единицу
    static List<Item> cbr(String xml) {
        Map<String, Double> r = new HashMap<>();
        Matcher m = Pattern.compile("<CharCode>([A-Z]{3})</CharCode>\\s*<Nominal>(\\d+)</Nominal>.*?<Value>([0-9]+[,.][0-9]+)</Value>", Pattern.DOTALL)
                .matcher(xml);
        while (m.find()) r.put(m.group(1), num(m.group(3)) / Integer.parseInt(m.group(2)));
        return pick(r, "USD", "$", "EUR", "€", "CNY", "¥");
    }

    // НБУ: [{"r030":840,"txt":"…","rate":41.49,"cc":"USD",…}, …]. Гривен за единицу
    static List<Item> nbu(String json) {
        Map<String, Double> r = new HashMap<>();
        for (String o : objects(json)) {
            String cc = field(o, "cc"), rate = field(o, "rate");
            if (cc != null && rate != null) r.put(cc, num(rate));
        }
        return pick(r, "USD", "$", "EUR", "€", "PLN", "zł");
    }

    // monobank: пары к гривне (980) по числовым кодам ISO; середина между покупкой и продажей или кросс-курс
    static List<Item> monobank(String json) {
        Map<String, Double> r = new HashMap<>();
        for (String o : objects(json)) {
            if (!"980".equals(field(o, "currencyCodeB"))) continue;
            String a = field(o, "currencyCodeA"), buy = field(o, "rateBuy"), sell = field(o, "rateSell"), cross = field(o, "rateCross");
            double v = buy != null && sell != null ? (num(buy) + num(sell)) / 2 : cross != null ? num(cross) : Double.NaN;
            r.put("840".equals(a) ? "USD" : "978".equals(a) ? "EUR" : "985".equals(a) ? "PLN" : String.valueOf(a), v);
        }
        return pick(r, "USD", "$", "EUR", "€", "PLN", "zł");
    }

    // Нацбанк Казахстана, rates_all.xml: <item><title>USD</title>…<description>448.98</description><quant>1</quant>. Тенге за единицу
    static List<Item> nbk(String xml) {
        Map<String, Double> r = new HashMap<>();
        Matcher it = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(xml);
        while (it.find()) {
            String s = it.group(1);
            String t = tag(s, "title"), d = tag(s, "description"), q = tag(s, "quant");
            if (t == null || d == null) continue;
            int n = 1;
            try { if (q != null) n = Math.max(1, Integer.parseInt(q.trim())); } catch (NumberFormatException ignored) { }
            r.put(t.trim(), num(d) / n);
        }
        return pick(r, "USD", "$", "EUR", "€", "RUB", "₽");
    }

    // ЕЦБ, eurofxref-daily.xml: <Cube currency='USD' rate='1.1193'/>, единиц валюты за евро. Своя валюта L из списка ЕЦБ:
    // евро и доллар в L (у США евро и фунт). Евро, нет в списке или страна неизвестна: доллар и фунт в евро
    static List<Item> ecb(String xml, String cc) {
        Map<String, Double> r = new HashMap<>();
        Matcher m = Pattern.compile("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]").matcher(xml);
        while (m.find()) r.put(m.group(1), num(m.group(2)));
        List<Item> l = new ArrayList<>();
        String cur = ecbCurrency(cc);
        Double own = r.get(cur), usd = r.get("USD"), gbp = r.get("GBP");
        if (!"EUR".equals(cur) && own != null) {
            l.add(new Item("€", own, ""));
            if ("USD".equals(cur)) { if (gbp != null) l.add(new Item("£", own / gbp, "")); }
            else if (usd != null) l.add(new Item("$", own / usd, ""));
            return l;
        }
        if (usd != null) l.add(new Item("$", 1 / usd, ""));
        if (gbp != null) l.add(new Item("£", 1 / gbp, ""));
        return l;
    }

    // Binance: [{"symbol":"BTCUSDT","price":"73250.00000000"}, …]. Строка: «BTC 73 250 $ · ETH 2 029 $ · SOL 82,26 $».
    // Нет биткоина: ответ не тот (страница ошибки, ограничение), пусто
    static List<Item> binance(String json) {
        Map<String, Double> r = new HashMap<>();
        for (String o : objects(json)) {
            String s = field(o, "symbol"), p = field(o, "price");
            if (s != null && p != null) r.put(s, num(p));
        }
        List<Item> l = new ArrayList<>();
        if (!ok(r.get("BTCUSDT"))) return l;
        l.add(new Item("BTC", r.get("BTCUSDT"), " $"));
        if (ok(r.get("ETHUSDT"))) l.add(new Item("ETH", r.get("ETHUSDT"), " $"));
        if (ok(r.get("SOLUSDT"))) l.add(new Item("SOL", r.get("SOLUSDT"), " $"));
        return l;
    }

    private static boolean ok(Double v) { return v != null && !v.isNaN() && !v.isInfinite() && v > 0; }

    // Число для строки: крупные без копеек и с пробелами между тысячами («97 450»), остальные с двумя знаками («3,25»)
    static String format(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1000) {
            // Разделитель тысяч у русской локали бывает разным (неразрывный, узкий, обычный пробел): везде тонкий
            StringBuilder b = new StringBuilder(String.format(Locale.forLanguageTag("ru"), "%,.0f", v));
            for (int i = 0; i < b.length(); i++) if (Character.isSpaceChar(b.charAt(i))) b.setCharAt(i, '\u2009');
            return b.toString();
        }
        return String.format(Locale.forLanguageTag("ru"), "%.2f", v);
    }

    // ===== Хранение строки между запусками: «$|82.75|;€|92.39|» =====

    static String encode(List<Item> l) {
        StringBuilder b = new StringBuilder();
        for (Item i : l) {
            if (b.length() > 0) b.append(';');
            b.append(i.sym.replace("|", "").replace(";", "")).append('|').append(i.v).append('|').append(i.tail.replace("|", "").replace(";", ""));
        }
        return b.toString();
    }

    static List<Item> decode(String s) {
        List<Item> l = new ArrayList<>();
        if (s == null || s.isEmpty()) return l;
        for (String part : s.split(";")) {
            String[] f = part.split("\\|", -1);
            if (f.length != 3) continue;
            try { l.add(new Item(f[0], Double.parseDouble(f[1]), f[2])); } catch (NumberFormatException ignored) { }
        }
        return l;
    }

    // ===== Мелочи =====

    // Пары «код, знак» по порядку; нет кода или курс не число: пропуск. Пусто: источник ответил не то
    private static List<Item> pick(Map<String, Double> r, String... codeSym) {
        List<Item> l = new ArrayList<>();
        for (int i = 0; i + 1 < codeSym.length; i += 2) {
            Double v = r.get(codeSym[i]);
            if (v != null && !v.isNaN() && !v.isInfinite() && v > 0) l.add(new Item(codeSym[i + 1], v, ""));
        }
        return l;
    }

    // Плоские объекты {…} массива JSON (вложенных у этих источников нет) и значение поля: число или строка без кавычек
    private static List<String> objects(String json) {
        List<String> l = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json);
        while (m.find()) l.add(m.group());
        return l;
    }

    private static String field(String o, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\"([^\"]*)\"|-?[0-9.eE+]+)").matcher(o);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private static String tag(String s, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.').replace(" ", "").replace(" ", "")); }
        catch (NumberFormatException e) { return Double.NaN; }
    }
}
