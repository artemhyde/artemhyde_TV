package app.glasstv.guard;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

// Соединение с adbd этого же ТВ по 127.0.0.1:5555 (выпуск 1.2.0): вход по ключу «Сторожа» и одна служба exec. Без Android,
// поэтому проверяется и с ПК против виртуального ТВ. Чужой адрес сюда не передаётся: его выбирает только AdbSelf
final class AdbLink implements Closeable {
    interface Signer { byte[] sign(byte[] input) throws Exception; }

    static final int OK = 0, NEED_ALLOW = 1, REFUSED = 2;

    private final Socket s;
    private final DataInputStream in;
    private final OutputStream out;
    private int maxData = 4096;
    private int nextId = 1;

    private AdbLink(Socket s) throws IOException {
        this.s = s;
        this.in = new DataInputStream(s.getInputStream());
        this.out = s.getOutputStream();
    }

    static AdbLink open(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            return new AdbLink(s);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    // Вход. Ключ не знают: offerKey false сразу NEED_ALLOW; offerKey true показывает ТВ окно «Разрешить отладку» с ключом
    // «Сторожа» и ждёт ответа человека до waitMs (нет ответа или «Отмена»: NEED_ALLOW). REFUSED: adbd просит TLS (Android 11
    // в режиме отладки по Wi-Fi), так «Сторож» не ходит
    int handshake(Signer signer, byte[] publicKey, boolean offerKey, long waitMs) throws Exception {
        send(AdbRules.CNXN, AdbRules.VERSION, AdbRules.MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == AdbRules.CNXN) {
                maxData = Math.max(1024, Math.min(m.a1, AdbRules.MAX_PAYLOAD));
                return OK;
            }
            if (m.cmd == AdbRules.STLS) return REFUSED;
            if (m.cmd != AdbRules.AUTH || m.a0 != AdbRules.AUTH_TOKEN) throw new IOException("adbd ответил не по протоколу");
            if (!signed) {
                send(AdbRules.AUTH, AdbRules.AUTH_SIGNATURE, 0, signer.sign(AdbRules.signInput(m.data)));
                signed = true;
                continue;
            }
            // Подпись не приняли: ключ «Сторожа» этому ТВ не знаком
            if (!offerKey) return NEED_ALLOW;
            send(AdbRules.AUTH, AdbRules.AUTH_RSAPUBLICKEY, 0, publicKey);
            int before = s.getSoTimeout();
            s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1_000, waitMs)));
            try {
                Msg r = read();
                if (r.cmd == AdbRules.CNXN) {
                    maxData = Math.max(1024, Math.min(r.a1, AdbRules.MAX_PAYLOAD));
                    return OK;
                }
                return NEED_ALLOW;
            } catch (SocketTimeoutException | EOFException e) {
                return NEED_ALLOW;   // окно не нажали или «Отмена» (adbd закрывает соединение)
            } finally {
                s.setSoTimeout(before);
            }
        }
    }

    // Служба exec: данные (size байт) уходят на её вход, возвращается её вывод. Поток идёт кусками, каждый ждёт OKAY
    String exec(String service, InputStream data, long size, long timeoutMs) throws Exception {
        int local = nextId++;
        send(AdbRules.OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMs));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remote = 0;
        while (remote == 0) {
            Msg m = read();
            if (m.cmd == AdbRules.OKAY && m.a1 == local) remote = m.a0;
            else if (m.cmd == AdbRules.CLSE) throw new IOException("adbd не открыл службу");
            else if (m.cmd == AdbRules.WRTE) { output.write(m.data, 0, m.data.length); send(AdbRules.OKAY, local, m.a0, null); }
        }
        if (data != null && size > 0) {
            byte[] buf = new byte[maxData];
            long left = size;
            while (left > 0) {
                int k = data.read(buf, 0, (int) Math.min(buf.length, left));
                if (k < 0) throw new EOFException("файл кончился раньше размера");
                byte[] chunk = new byte[k];
                System.arraycopy(buf, 0, chunk, 0, k);
                send(AdbRules.WRTE, local, remote, chunk);
                left -= k;
                while (true) {   // ждём подтверждения куска; вывод службы по дороге собираем
                    Msg m = read();
                    if (m.cmd == AdbRules.OKAY && m.a1 == local) break;
                    if (m.cmd == AdbRules.WRTE) { output.write(m.data, 0, m.data.length); send(AdbRules.OKAY, local, remote, null); continue; }
                    if (m.cmd == AdbRules.CLSE) return output.toString("UTF-8");
                }
            }
        }
        while (true) {
            Msg m = read();
            if (m.cmd == AdbRules.WRTE) {
                output.write(m.data, 0, m.data.length);
                send(AdbRules.OKAY, local, remote, null);
            } else if (m.cmd == AdbRules.CLSE) {
                send(AdbRules.CLSE, local, remote, null);
                return output.toString("UTF-8");
            }
        }
    }

    @Override public void close() {
        try { s.close(); } catch (IOException ignored) { }
    }

    private static final class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        out.write(AdbRules.header(cmd, a0, a1, data));
        if (data != null && data.length > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[AdbRules.HEADER];
        in.readFully(h);
        int[] p = AdbRules.parse(h);
        Msg m = new Msg();
        m.cmd = p[0];
        m.a0 = p[1];
        m.a1 = p[2];
        m.data = new byte[p[3]];
        in.readFully(m.data);
        return m;
    }
}
