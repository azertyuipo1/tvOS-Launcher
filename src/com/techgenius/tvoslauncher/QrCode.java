package com.techgenius.tvoslauncher;

import java.nio.charset.StandardCharsets;

/**
 * Générateur de QR code minimal (mode octets, correction M, versions 1 à 10), sans bibliothèque.
 * Algorithme d'après le générateur de référence de Nayuki (MIT). Sert au QR code Wi-Fi de l'accueil.
 */
final class QrCode {
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    boolean get(int x, int y) { return modules[y][x]; }

    /** Chaîne Wi-Fi standard (iPhone et Android l'ouvrent avec l'appareil photo). */
    static String wifiPayload(String ssid, String pass) {
        boolean open = pass == null || pass.isEmpty();
        return "WIFI:T:" + (open ? "nopass" : "WPA") + ";S:" + esc(ssid) + ";" + (open ? "" : "P:" + esc(pass) + ";") + ";";
    }

    private static String esc(String s) { return s.replaceAll("([\\\\;,:\"])", "\\\\$1"); }

    /** null si le texte est trop long pour la version 10. */
    static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 10; ver++) {
            int capacityBits = dataCodewords(ver) * 8;
            int needBits = 4 + 8 + data.length * 8; // compteur sur 8 bits jusqu'à la version 9
            if (ver == 10) needBits += 8;
            if (needBits <= capacityBits) return build(ver, data);
        }
        return null;
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

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private static QrCode build(int ver, byte[] data) {
        // Flux de bits : mode octets, longueur, données, terminateur, remplissage.
        int cap = dataCodewords(ver) * 8;
        BitBuf bb = new BitBuf(cap);
        bb.append(0x4, 4);
        bb.append(data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, cap - bb.len));
        bb.append(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] dataCw = new byte[cap / 8];
        for (int i = 0; i < bb.len; i++) if (bb.bits[i]) dataCw[i >>> 3] |= (byte) (1 << (7 - (i & 7)));

        byte[] all = addEcc(ver, dataCw);
        QrCode q = new QrCode(ver);
        q.drawFunctionPatterns(ver);
        q.drawCodewords(all);
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            q.applyMask(m);
            q.drawFormat(m);
            long p = q.penalty();
            if (p < bestPenalty) { bestPenalty = p; best = m; }
            q.applyMask(m); // annule (XOR)
        }
        q.applyMask(best);
        q.drawFormat(best);
        return q;
    }

    private static byte[] addEcc(int ver, byte[] data) {
        int numBlocks = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] div = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int n = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[n];
            System.arraycopy(data, k, dat, 0, n);
            k += n;
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, n);
            byte[] ecc = rsRemainder(dat, div);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) out[k++] = blocks[j][i];
            }
        }
        return out;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private void setF(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            setF(6, i, i % 2 == 0);
            setF(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (ver >= 2) {
            int na = ver / 7 + 2;
            int step = (ver * 8 + na * 3 + 5) / (na * 4 - 4) * 2;
            int[] pos = new int[na];
            pos[0] = 6;
            for (int i = na - 1, p = size - 7; i >= 1; i--, p -= step) pos[i] = p;
            for (int i = 0; i < na; i++) {
                for (int j = 0; j < na; j++) {
                    if ((i == 0 && j == 0) || (i == 0 && j == na - 1) || (i == na - 1 && j == 0)) continue;
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            setF(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        drawFormat(0); // réserve les emplacements
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                setF(a, c, b);
                setF(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setF(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private void drawFormat(int mask) {
        int data = (0 << 3) | mask; // correction M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setF(8, i, bit(bits, i));
        setF(8, 7, bit(bits, 6));
        setF(8, 8, bit(bits, 7));
        setF(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setF(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setF(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setF(8, size - 15 + i, bit(bits, i));
        setF(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Pénalité simplifiée (suites, blocs 2×2, motifs de repérage, équilibre) pour choisir le masque. */
    private long penalty() {
        long p = 0;
        int dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) p += 3; else if (run > 5) p++;
                    } else run = 1;
                }
                for (int b = 0; b + 6 < size; b++) {
                    boolean m = true;
                    boolean[] pat = {true, false, true, true, true, false, true};
                    for (int k = 0; k < 7 && m; k++) m = (pass == 0 ? modules[a][b + k] : modules[b + k][a]) == pat[k];
                    if (m) p += 40;
                }
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += Math.max(0, k) * 10L;
        return p;
    }

    private static final class BitBuf {
        final boolean[] bits;
        int len = 0;
        BitBuf(int cap) { bits = new boolean[cap]; }
        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--) bits[len++] = ((val >>> i) & 1) != 0;
        }
    }

    /** Image du QR code (modules noirs sur fond blanc, marge de 2 modules). */
    android.graphics.Bitmap toBitmap(int px) {
        int quiet = 2, n = size + 2 * quiet;
        int scale = Math.max(1, px / n);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(n * scale, n * scale, android.graphics.Bitmap.Config.ARGB_8888);
        bmp.eraseColor(0xFFFFFFFF);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (modules[y][x])
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++)
                            bmp.setPixel((x + quiet) * scale + dx, (y + quiet) * scale + dy, 0xFF141414);
        return bmp;
    }
}
