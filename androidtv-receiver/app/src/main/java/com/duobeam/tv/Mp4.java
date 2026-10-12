package com.duobeam.tv;

import java.io.ByteArrayOutputStream;

/**
 * Minimal reading of the fMP4 pieces the sender sends: init segment (hvcC -> HEVC parameter sets, picture size)
 * and fragments (tfdt time, mdat payload). Video NAL units are 4-byte length prefixed -> converted to Annex-B.
 */
final class Mp4 {

    static final byte[] START = {0, 0, 0, 1};

    private Mp4() {
    }

    static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static long u64(byte[] b, int o) {
        return (u32(b, o) << 32) | u32(b, o + 4);
    }

    /** Index of a 4-character box type, or -1. */
    static int find(byte[] b, int from, int to, String type) {
        byte c0 = (byte) type.charAt(0), c1 = (byte) type.charAt(1), c2 = (byte) type.charAt(2), c3 = (byte) type.charAt(3);
        for (int i = from; i + 4 <= to; i++) {
            if (b[i] == c0 && b[i + 1] == c1 && b[i + 2] == c2 && b[i + 3] == c3) return i;
        }
        return -1;
    }

    static final class Init {
        int width, height;
        byte[] csd; // VPS+SPS+PPS, Annex-B
    }

    static Init parseInit(byte[] b, int off, int len) {
        int end = off + len;
        int hc = find(b, off, end, "hvcC");
        if (hc < 0) throw new IllegalArgumentException("no hvcC");
        Init r = new Init();
        // hvc1 sample entry: width/height sit 58/56 bytes before the hvcC type
        r.width = u16(b, hc - 58);
        r.height = u16(b, hc - 56);
        int p = hc + 4 + 22, arrays = b[p++] & 0xFF;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int a = 0; a < arrays; a++) {
            p++; // NAL type
            int num = u16(b, p);
            p += 2;
            for (int j = 0; j < num; j++) {
                int n = u16(b, p);
                p += 2;
                out.write(START, 0, 4);
                out.write(b, p, n);
                p += n;
            }
        }
        r.csd = out.toByteArray();
        return r;
    }

    /** tfdt base decode time of a fragment (in the track's timescale), or -1. */
    static long tfdt(byte[] b, int off, int len) {
        int i = find(b, off, Math.min(off + len, off + 256), "tfdt");
        return i < 0 ? -1 : u64(b, i + 8);
    }

    /** {offset, length} of the mdat payload, or null. */
    static int[] mdat(byte[] b, int off, int len) {
        int i = find(b, off, off + len, "mdat");
        if (i < 4) return null;
        long size = u32(b, i - 4);
        return new int[] {i + 4, (int) Math.min(size - 8, off + len - (i + 4))};
    }

    /** Length-prefixed NAL units -> Annex-B (optional prefix, e.g. parameter sets before a keyframe). */
    static byte[] annexB(byte[] b, int off, int len, byte[] prefix) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 64);
        if (prefix != null) out.write(prefix, 0, prefix.length);
        int p = off, end = off + len;
        while (p + 4 <= end) {
            int n = (int) u32(b, p);
            if (n <= 0 || p + 4 + n > end) break;
            out.write(START, 0, 4);
            out.write(b, p + 4, n);
            p += 4 + n;
        }
        return out.toByteArray();
    }
}
