package app.glasstv.guard;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

// Протокол adb для установки обновлений HYDE на Android 9–11 (выпуск 1.2.0, AdbLink, AdbSelf): без Android, проверка на ПК
// (test/AdbRulesTest.java). Заголовок сообщения, запись открытого ключа в формате adb, что подписывать, команда установки
// и разбор её ответа. «Сторож» умеет через adb одно: поставить APK HYDE, уже проверенный ключом выпуска
final class AdbRules {
    private AdbRules() { }

    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257, STLS = 0x534c5453;
    static final int VERSION = 0x01000001;          // версия протокола, с которой контрольную сумму можно не проверять
    static final int MAX_PAYLOAD = 256 * 1024;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final int HEADER = 24;
    static final int MAX_DATA = 1024 * 1024;        // больше adbd не присылает; длиннее значит не adb

    // Начало подписи PKCS#1 для SHA-1 (DigestInfo): adbd проверяет подпись маркера как подпись готового хэша SHA-1
    static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    static byte[] header(int cmd, int arg0, int arg1, byte[] data) {
        int len = data == null ? 0 : data.length;
        byte[] h = new byte[HEADER];
        put(h, 0, cmd);
        put(h, 4, arg0);
        put(h, 8, arg1);
        put(h, 12, len);
        put(h, 16, checksum(data));
        put(h, 20, ~cmd);
        return h;
    }

    // {команда, arg0, arg1, длина данных}; неверный заголовок: исключение
    static int[] parse(byte[] h) {
        int cmd = get(h, 0), len = get(h, 12), magic = get(h, 20);
        if (magic != ~cmd) throw new IllegalArgumentException("не заголовок adb");
        if (len < 0 || len > MAX_DATA) throw new IllegalArgumentException("длина данных " + len);
        return new int[]{cmd, get(h, 4), get(h, 8), len};
    }

    static int checksum(byte[] d) {
        int s = 0;
        if (d != null) for (byte b : d) s += b & 0xff;
        return s;
    }

    // Что подписать ключом «Сторожа» (NONEwithRSA, дополнение PKCS#1): начало DigestInfo SHA-1 и сам маркер adbd (20 байт)
    static byte[] signInput(byte[] token) {
        byte[] r = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, r, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, r, SHA1_PREFIX.length, token.length);
        return r;
    }

    // Открытый ключ в формате adb (android_pubkey): длина модуля в словах, n0inv, модуль и R^2 mod n младшими байтами вперёд,
    // показатель; всё в base64, через пробел имя, в конце нулевой байт. Это строка в окне «Разрешить отладку по USB?»
    static byte[] publicKey(BigInteger n, BigInteger e, String name) {
        int words = (n.bitLength() + 31) / 32;
        byte[] s = new byte[4 + 4 + words * 4 + words * 4 + 4];
        put(s, 0, words);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        put(s, 4, n0inv.intValue());
        le(n, s, 8, words * 4);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        le(rr, s, 8 + words * 4, words * 4);
        put(s, 8 + words * 8, e.intValue());
        String text = Base64.getEncoder().encodeToString(s) + " " + name;
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[t.length + 1];
        System.arraycopy(t, 0, r, 0, t.length);
        return r;
    }

    // Единственная команда «Сторожа»: поставить файл поверх, установщиком записать себя. Размер ровно столько байт, сколько
    // придёт следом (файл передаётся потоком, на диск ТВ его кладёт сама установка)
    static String installService(long size, String installer) {
        if (size <= 0) throw new IllegalArgumentException("размер файла");
        if (!installer.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw new IllegalArgumentException("установщик");
        return "exec:cmd package install -r -i " + installer + " -S " + size;
    }

    // Ответ установки: null, если «Success», иначе текст ошибки (Failure [...] или что пришло)
    static String installResult(String out) {
        String t = out == null ? "" : out.trim();
        if (t.equals("Success") || t.endsWith("\nSuccess")) return null;
        if (t.isEmpty()) return "установка ничего не ответила";
        int i = t.indexOf("Failure");
        return i >= 0 ? t.substring(i).split("\n")[0].trim() : t.split("\n")[0].trim();
    }

    private static void le(BigInteger v, byte[] dst, int off, int len) {
        byte[] be = v.toByteArray();   // старшими вперёд, возможно с лишним нулём впереди
        for (int i = 0; i < len; i++) {
            int src = be.length - 1 - i;
            dst[off + i] = src >= 0 ? be[src] : 0;
        }
    }

    static void put(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    static int get(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }
}
