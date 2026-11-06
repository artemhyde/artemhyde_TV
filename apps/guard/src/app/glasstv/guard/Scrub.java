package app.glasstv.guard;

import java.util.regex.Pattern;

// Чистка отчёта от личного (план Codex, блок 6: «экспорт отчёта с удалением личных данных»), без Android: проверяет тест на ПК.
// Тексты записок с телефона, адреса в сети (IPv4, IPv6, MAC), почта, путь и параметры ссылок (там бывают ключи), длинные
// ключи и хэши. Имена сетей Wi-Fi, аккаунты и пароли в отчёт не попадают вовсе: отчёт их не собирает
final class Scrub {
    private Scrub() { }

    private static final Pattern NOTE = Pattern.compile("(Записка: )[^\\n]*");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern URL = Pattern.compile("\\b(https?://[^/\\s]+)[^\\s]*");
    private static final Pattern MAC = Pattern.compile("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6 = Pattern.compile("\\b[0-9A-Fa-f]{1,4}(:[0-9A-Fa-f]{0,4}){3,7}\\b");
    private static final Pattern KEY = Pattern.compile("\\b[A-Za-z0-9_\\-]{32,}\\b");

    static String clean(String s) {
        if (s == null) return "";
        s = NOTE.matcher(s).replaceAll("$1(текст скрыт)");
        s = EMAIL.matcher(s).replaceAll("(почта скрыта)");
        s = URL.matcher(s).replaceAll("$1/…");
        s = MAC.matcher(s).replaceAll("xx:xx:xx:xx:xx:xx");
        s = IPV4.matcher(s).replaceAll("x.x.x.x");
        s = IPV6.matcher(s).replaceAll("(адрес скрыт)");
        s = KEY.matcher(s).replaceAll("(ключ скрыт)");
        return s;
    }
}
