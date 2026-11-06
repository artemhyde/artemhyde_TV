package app.glasstv.guard;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

// Присмотр за частным DNS (05.11). Через него ТВ узнаёт адреса сайтов. 05.11 бесплатный частный DNS перестал отвечать,
// а система по-прежнему считала его проверенным: на ТВ разом отвалилось всё онлайн (Lampa, «Волна», YouTube, погода).
// Раз в 5 минут при включённом экране (и после сна, и при смене сети): интернет по адресу есть, а имена не разрешаются
// три раза подряд за минуту, значит DNS умер. Тогда «Сторож» сам проверяет запасные защищённые DNS прямым запросом,
// ставит первый рабочий, начиная с выбранного (по умолчанию Mullvad с блокировкой рекламы), и показывает записку.
// На запасном раз в 30 минут проверяет выбранный и, как только тот ожил, возвращается. Если не отвечает ни один,
// ставит DNS роутера. После любой смены настройки DNS сначала минута тишины: пока система переключается, имена
// до полуминуты не разрешаются (проверено 05.11), и это не поломка.
// Порядок запасных по проверке 05.11 из дома: Mullvad режет рекламу и пускает каталог фильмов (TMDB); Cloudflare пускает
// всё, рекламу не режет; AdGuard режет рекламу, но отвечает на TMDB адресом 127.0.0.1 (каталог Lampa пустой без
// прокси); Google тоже отвечает на TMDB 127.0.0.1.
// Возвращается «Сторож» только туда, откуда сам ушёл: если человек сам выбрал другой DNS или «Автоматически», выбор не трогается
final class DnsWatch {
    static final class P {
        final String host, ip, name;
        P(String host, String ip, String name) { this.host = host; this.ip = ip; this.name = name; }
    }

    static final List<P> ALL = Arrays.asList(
            new P("adblock.dns.mullvad.net", "194.242.2.3", "Mullvad с блокировкой рекламы"),
            new P("one.one.one.one", "1.1.1.1", "Cloudflare"),
            new P("dns.adguard-dns.com", "94.140.14.14", "AdGuard"),
            new P("dns.google", "8.8.8.8", "Google"));
    // 06.11: основной Cloudflare. Mullvad отдаёт для lampa.mx адрес 188.114.96.1, который провайдер не пропускает
    static final String DEFAULT_PREF = "one.one.one.one";
    static final long EVERY = 5 * 60_000, CONFIRM = 30_000, SETTLE = 60_000, BACK_EVERY = 30 * 60_000;

    interface Out { void log(String s); void note(String s); }

    private final ContentResolver cr;
    private final SharedPreferences p;
    private final Out out;
    private int miss;
    private long backAt;
    private String seen;
    private final Random rnd = new Random();

    DnsWatch(ContentResolver cr, SharedPreferences p, Out out) {
        this.cr = cr;
        this.p = p;
        this.out = out;
    }

    String pref() { return p.getString("dnsPref", DEFAULT_PREF); }

    void setPref(String host) { p.edit().putString("dnsPref", host).apply(); }

    String current() {
        String mode = Settings.Global.getString(cr, "private_dns_mode");
        if (!"hostname".equals(mode)) return mode == null ? "opportunistic" : mode;
        String s = Settings.Global.getString(cr, "private_dns_specifier");
        return s == null ? "" : s;
    }

    // Один шаг присмотра (в фоновой нитке). Возвращает, через сколько мс проверить снова
    synchronized long check(boolean byHand) {
        String cur = current();
        if (!cur.equals(seen)) {   // настройку DNS только что сменили (человек или сам «Сторож»): дать системе переключиться
            seen = cur;
            miss = 0;
            if (!byHand) return SETTLE;
        }
        if (!ipUp()) { miss = 0; return EVERY; }   // интернета нет совсем: DNS тут ни при чём
        if (namesWork()) {
            if (miss > 0) out.log("DNS снова отвечает");
            miss = 0;
            maybeBack();
            return EVERY;
        }
        miss++;
        if (miss < 3 && !byHand) return CONFIRM;   // промах подтверждается ещё двумя через 30 с
        heal();
        miss = 0;
        seen = current();
        return EVERY;
    }

    // DNS умер: первый рабочий защищённый DNS, начиная с выбранного; не ответил ни один: DNS роутера
    private void heal() {
        String was = current();
        for (P c : order()) {
            if (!probe(c)) continue;
            if (c.host.equals(was)) {
                out.log("DNS " + c.name + " отвечает напрямую, а имена не разрешаются: ждём, система переподключится");
                return;
            }
            put(c.host);
            p.edit().putString("dnsAuto", c.host).apply();
            out.log("DNS " + was + " не отвечал, включён " + c.name);
            out.note("Интернет-адреса не открывались: DNS " + was + " перестал отвечать. «Сторож» включил " + c.name + ", всё снова работает");
            return;
        }
        Settings.Global.putString(cr, "private_dns_mode", "opportunistic");
        p.edit().putString("dnsAuto", "opportunistic").apply();
        out.log("Ни один защищённый DNS не ответил, включён DNS роутера");
        out.note("Защищённые DNS не отвечают. «Сторож» включил DNS роутера, интернет работает без блокировки рекламы");
    }

    // На запасном: раз в 30 минут проверить выбранный и вернуться на него
    private void maybeBack() {
        String pref = pref(), cur = current();
        if (pref.equals(cur)) return;
        if (!cur.equals(p.getString("dnsAuto", ""))) return;   // этот DNS поставил не «Сторож», а человек
        long t = System.currentTimeMillis();
        if (t < backAt) return;
        backAt = t + BACK_EVERY;
        for (P c : ALL) {
            if (c.host.equals(pref) && probe(c)) {
                put(c.host);
                p.edit().remove("dnsAuto").apply();
                out.log("Выбранный DNS " + c.name + " снова отвечает, «Сторож» вернул его");
                return;
            }
        }
    }

    private void put(String host) {
        Settings.Global.putString(cr, "private_dns_specifier", host);
        Settings.Global.putString(cr, "private_dns_mode", "hostname");
    }

    private List<P> order() {
        List<P> l = new ArrayList<>();
        String pref = pref();
        for (P c : ALL) if (c.host.equals(pref)) l.add(c);
        for (P c : ALL) if (!c.host.equals(pref)) l.add(c);
        return l;
    }

    // Интернет по адресу (без DNS): соединение с 1.1.1.1 или 8.8.8.8 на порт 443
    static boolean ipUp() {
        for (String ip : new String[]{"1.1.1.1", "8.8.8.8"}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, 443), 3_000);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    // Имена через систему. Случайное имя из кэша не достать, поэтому спрашиваем каждый раз новое. Берём имена, которые
    // существуют при любой приставке (*.github.io у GitHub, *.blogspot.com у Google): любой живой DNS отвечает адресом за
    // доли секунды. Несуществующие имена не годятся: DNS роутера на них просто молчит, как мёртвый (проверено 05.11).
    // Хватает одного из двух, чтобы сбой у одной компании не выглядел смертью DNS
    boolean namesWork() {
        String r = Integer.toHexString(rnd.nextInt());
        return resolve("g" + r + ".github.io") == 2 || resolve("g" + r + ".blogspot.com") == 2;
    }

    // 0: молчит дольше 6 с, 1: быстро ответил ошибкой, 2: разрешилось в адрес
    private static int resolve(String name) {
        final int[] r = {0};
        final boolean[] done = {false};
        Thread t = new Thread(() -> {
            int v;
            try { v = InetAddress.getAllByName(name).length > 0 ? 2 : 1; } catch (Exception e) { v = 1; }
            synchronized (done) { r[0] = v; done[0] = true; done.notifyAll(); }
        }, "guard-dns");
        t.setDaemon(true);
        t.start();
        synchronized (done) {
            long end = System.currentTimeMillis() + 6_000;
            while (!done[0]) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { done.wait(left); } catch (InterruptedException e) { break; }
            }
            return done[0] ? r[0] : 0;
        }
    }

    // Прямой запрос к DNS по защищённому каналу (DNS over TLS, порт 853) мимо системы: имя music.youtube.com должно
    // разрешиться в настоящий адрес. Сертификат сверяется с именем DNS, как это делает сама система
    static boolean probe(P c) {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(c.ip, 853), 4_000);
            raw.setSoTimeout(4_000);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, c.host, 853, true);
            SSLParameters sp = s.getSSLParameters();
            sp.setServerNames(java.util.Collections.singletonList(new SNIHostName(c.host)));
            s.setSSLParameters(sp);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(c.host, s.getSession())) return false;
            byte[] q = query("music.youtube.com");
            OutputStream o = s.getOutputStream();
            o.write(new byte[]{(byte) (q.length >> 8), (byte) q.length});
            o.write(q);
            o.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = in.readUnsignedShort();
            byte[] r = new byte[len];
            in.readFully(r);
            int rcode = r[3] & 0x0F, an = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
            if (rcode != 0 || an == 0) return false;
            // Ответ не должен быть заглушкой 0.0.0.0 или 127.x: берём последние 4 байта (последняя запись типа A)
            int a = r[len - 4] & 0xFF;
            return a != 0 && a != 127;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] query(String name) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0x12); b.write(0x34); b.write(0x01); b.write(0x00);
        b.write(0); b.write(1); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0); b.write(0);
        for (String part : name.split("\\.")) {
            b.write(part.length());
            byte[] x = part.getBytes();
            b.write(x, 0, x.length);
        }
        b.write(0); b.write(0); b.write(1); b.write(0); b.write(1);
        return b.toByteArray();
    }
}
