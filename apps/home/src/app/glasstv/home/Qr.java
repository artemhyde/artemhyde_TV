package app.glasstv.home;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Код QR для адреса страницы «Своё фото» (10.11): телефон наводит камеру и открывает страницу на ТВ.
// Свой маленький кодировщик по стандарту ISO/IEC 18004 (байтовый режим, коррекция M, версии 1–10), без библиотек.
// Чистая Java без Android: проверяется на ПК (test/QrTest.java сверяет с независимым кодировщиком)
final class Qr {
    private Qr() {}

    // Для уровня M: кодовых слов коррекции на блок и число блоков по версиям 1..10
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    // Матрица: true тёмный модуль. forcedMask -1: лучший шаблон маски по штрафам стандарта
    static boolean[][] encode(String text) { return encode(text.getBytes(StandardCharsets.UTF_8), -1); }

    static boolean[][] encode(byte[] data, int forcedMask) {
        int ver = 1;
        for (; ver <= 10; ver++) {
            int cap = dataCodewords(ver) * 8;
            int need = 4 + (ver < 10 ? 8 : 16) + data.length * 8;
            if (need <= cap) break;
        }
        if (ver > 10) throw new IllegalArgumentException("слишком длинный текст для кода");
        // Биты: режим «байты», длина, данные, конец, добивка до байта, чередование 0xEC 0x11
        List<Boolean> bits = new ArrayList<>();
        put(bits, 4, 4);
        put(bits, data.length, ver < 10 ? 8 : 16);
        for (byte b : data) put(bits, b & 0xFF, 8);
        int cap = dataCodewords(ver) * 8;
        put(bits, 0, Math.min(4, cap - bits.size()));
        put(bits, 0, (8 - bits.size() % 8) % 8);
        for (int pad = 0xEC; bits.size() < cap; pad ^= 0xEC ^ 0x11) put(bits, pad, 8);
        byte[] dataBytes = new byte[bits.size() / 8];
        for (int i = 0; i < bits.size(); i++) if (bits.get(i)) dataBytes[i >>> 3] |= 1 << (7 - (i & 7));
        byte[] all = addEcc(dataBytes, ver);
        int size = ver * 4 + 17;
        boolean[][] m = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        functionPatterns(m, fn, ver);
        drawCodewords(m, fn, all);
        int mask = forcedMask;
        if (mask < 0) {
            int best = Integer.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                applyMask(m, fn, k);
                formatBits(m, fn, k);
                int p = penalty(m);
                if (p < best) { best = p; mask = k; }
                applyMask(m, fn, k);   // маска XOR: второй раз снимает
            }
        }
        applyMask(m, fn, mask);
        formatBits(m, fn, mask);
        return m;
    }

    private static void put(List<Boolean> bits, int v, int n) {
        for (int i = n - 1; i >= 0; i--) bits.add(((v >>> i) & 1) != 0);
    }

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int na = ver / 7 + 2;
            r -= (25 * na - 10) * na - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) { return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]; }

    // Деление на блоки, коррекция Рида — Соломона для каждого, чередование байтов
    private static byte[] addEcc(byte[] data, int ver) {
        int nb = BLOCKS[ver], ecc = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortCount = nb - raw % nb, shortLen = raw / nb;
        byte[] gen = rsGenerator(ecc);
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0, k = 0; i < nb; i++) {
            int dl = shortLen - ecc + (i < shortCount ? 0 : 1);
            byte[] dat = new byte[dl];
            System.arraycopy(data, k, dat, 0, dl);
            k += dl;
            byte[] e = rsRemainder(dat, gen);
            byte[] blk = new byte[shortLen + 1];
            System.arraycopy(dat, 0, blk, 0, dl);
            // В коротком блоке место под лишний байт данных пропускается при чередовании
            System.arraycopy(e, 0, blk, blk.length - ecc, ecc);
            blocks.add(blk);
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < nb; j++) {
                if (i != shortLen - ecc || j >= shortCount) out[o++] = blocks.get(j)[i];
            }
        }
        return out;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static byte[] rsGenerator(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] gen) {
        byte[] r = new byte[gen.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= gfMul(gen[i] & 0xFF, f);
        }
        return r;
    }

    // ===== Служебные узоры =====

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    private static void functionPatterns(boolean[][] m, boolean[][] fn, int ver) {
        int size = m.length;
        for (int i = 0; i < size; i++) {   // полосы синхронизации
            set(m, fn, 6, i, i % 2 == 0);
            set(m, fn, i, 6, i % 2 == 0);
        }
        finder(m, fn, 3, 3);
        finder(m, fn, size - 4, 3);
        finder(m, fn, 3, size - 4);
        int[] al = alignPositions(ver, size);
        int n = al.length;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
                set(m, fn, al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        formatBits(m, fn, 0);   // место формата (биты перепишутся маской)
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(m, fn, a, c, b);
                set(m, fn, c, a, b);
            }
        }
    }

    private static void finder(boolean[][] m, boolean[][] fn, int x, int y) {
        int size = m.length;
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
            if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(m, fn, xx, yy, d != 2 && d != 4);
        }
    }

    private static int[] alignPositions(int ver, int size) {
        if (ver == 1) return new int[0];
        int na = ver / 7 + 2;
        int step = (ver * 8 + na * 3 + 5) / (na * 4 - 4) * 2;
        int[] r = new int[na];
        r[0] = 6;
        for (int i = na - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private static void formatBits(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        int data = (0 << 3) | mask;   // уровень M: биты 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(m, fn, 8, i, bit(bits, i));
        set(m, fn, 8, 7, bit(bits, 6));
        set(m, fn, 8, 8, bit(bits, 7));
        set(m, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, fn, 8, size - 15 + i, bit(bits, i));
        set(m, fn, 8, size - 8, true);   // всегда тёмный модуль
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    // Кодовые слова змейкой: пары столбцов справа налево, вверх и вниз, мимо служебных узоров
    private static void drawCodewords(boolean[][] m, boolean[][] fn, byte[] data) {
        int size = m.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        m[y][x] = bit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (fn[y][x]) continue;
            boolean inv;
            switch (mask) {
                case 0: inv = (x + y) % 2 == 0; break;
                case 1: inv = y % 2 == 0; break;
                case 2: inv = x % 3 == 0; break;
                case 3: inv = (x + y) % 3 == 0; break;
                case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
            }
            if (inv) m[y][x] = !m[y][x];
        }
    }

    // Штрафы стандарта: ряды одного цвета, квадраты 2x2, узоры как у искателя, перекос тёмного
    private static int penalty(boolean[][] m) {
        int size = m.length, r = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                boolean color = false;
                int run = 0;
                int[] hist = new int[7];
                for (int b = 0; b < size; b++) {
                    boolean c = pass == 0 ? m[a][b] : m[b][a];
                    if (c == color) {
                        run++;
                        if (run == 5) r += 3;
                        else if (run > 5) r++;
                    } else {
                        addHist(run, hist, size);
                        if (!color) r += countFinder(hist, size) * 40;
                        color = c;
                        run = 1;
                    }
                }
                r += terminate(color, run, hist, size) * 40;
            }
        }
        for (int y = 0; y < size - 1; y++) for (int x = 0; x < size - 1; x++) {
            boolean c = m[y][x];
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) r += 3;
        }
        int dark = 0;
        for (boolean[] row : m) for (boolean c : row) if (c) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        r += k * 10;
        return r;
    }

    private static void addHist(int run, int[] hist, int size) {
        if (hist[0] == 0) run += size;
        System.arraycopy(hist, 0, hist, 1, hist.length - 1);
        hist[0] = run;
    }

    private static int countFinder(int[] h, int size) {
        int n = h[1];
        boolean core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n;
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    }

    private static int terminate(boolean color, int run, int[] hist, int size) {
        if (color) { addHist(run, hist, size); run = 0; }
        run += size;
        addHist(run, hist, size);
        return countFinder(hist, size);
    }
}
