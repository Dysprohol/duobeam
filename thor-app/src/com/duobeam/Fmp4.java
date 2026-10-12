package com.duobeam;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal fragmented MP4 muxer for HEVC: init segment (ftyp+moov) plus one fragment (moof+mdat) per frame.
 * Port of tools/fmp4.py. Video timescale is 1 MHz because the TV player divides tfdt by 1e6.
 * Optional track 2: AAC-LC or Opus, 48 kHz stereo (timescale 48000), one fragment per audio frame.
 */
final class Fmp4 {

    static final int TIMESCALE = 1_000_000;

    private int seq;

    static final class Init {
        final byte[] data;
        final String codec;

        Init(byte[] data, String codec) {
            this.data = data;
            this.codec = codec;
        }
    }

    private static final class Buf {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Buf u8(int v) { out.write(v & 0xFF); return this; }
        Buf u16(int v) { u8(v >> 8); return u8(v); }
        Buf u32(long v) { u16((int) (v >> 16)); return u16((int) v); }
        Buf u64(long v) { u32(v >>> 32); return u32(v & 0xFFFFFFFFL); }
        Buf zeros(int n) { for (int i = 0; i < n; i++) out.write(0); return this; }
        Buf str(String s) { for (int i = 0; i < s.length(); i++) out.write(s.charAt(i)); return this; }
        Buf bytes(byte[] b) { out.write(b, 0, b.length); return this; }
        byte[] get() { return out.toByteArray(); }
    }

    private static byte[] box(String type, byte[]... payload) {
        int len = 8;
        for (byte[] p : payload) len += p.length;
        Buf b = new Buf().u32(len).str(type);
        for (byte[] p : payload) b.bytes(p);
        return b.get();
    }

    private static byte[] fullbox(String type, int version, int flags, byte[]... payload) {
        byte[][] all = new byte[payload.length + 1][];
        all[0] = new Buf().u32(((long) version << 24) | flags).get();
        System.arraycopy(payload, 0, all, 1, payload.length);
        return box(type, all);
    }

    static List<byte[]> splitAnnexB(byte[] buf, int off, int len) {
        List<byte[]> nals = new ArrayList<>();
        int end = off + len, start = -1, i = off;
        while (i + 3 <= end) {
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                if (start >= 0) {
                    int e = (i > off && buf[i - 1] == 0) ? i - 1 : i;
                    if (e > start) nals.add(copy(buf, start, e));
                }
                start = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        if (start >= 0 && end > start) nals.add(copy(buf, start, end));
        return nals;
    }

    private static byte[] copy(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    static int nalType(byte[] nal) {
        return (nal[0] >> 1) & 0x3F;
    }

    private static byte[] unescape(byte[] nal) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int zeros = 0;
        for (byte x : nal) {
            int b = x & 0xFF;
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    static Init init(byte[] config, int width, int height) {
        return init(config, width, height, null);
    }

    /** audio: null = no audio, "aac", "opus" or "pcm" (pcm adds no track to moov, only a marker in the codec string). */
    static Init init(byte[] config, int width, int height, String audio) {
        byte[] vps = null, sps = null, pps = null;
        for (byte[] n : splitAnnexB(config, 0, config.length)) {
            switch (nalType(n)) {
                case 32: vps = n; break;
                case 33: sps = n; break;
                case 34: pps = n; break;
                default: break;
            }
        }
        if (vps == null || sps == null || pps == null) throw new IllegalArgumentException("missing VPS/SPS/PPS");
        byte[] r = unescape(sps);
        // general profile_tier_level follows the 2-byte NAL header and 1 byte (vps_id, max_sub_layers, nesting)
        int ptlFirst = r[3] & 0xFF, level = r[14] & 0xFF;
        Buf hv = new Buf().u8(1).u8(ptlFirst);
        for (int i = 4; i < 14; i++) hv.u8(r[i]); // 4 bytes compatibility flags + 6 bytes constraint flags
        hv.u8(level).u16(0xF000).u8(0xFC).u8(0xFD).u8(0xF8).u8(0xF8).u16(0)
                .u8((1 << 3) | (1 << 2) | 3) // 1 temporal layer, temporal id nested, 4-byte NAL length
                .u8(3);
        for (byte[] n : new byte[][] {vps, sps, pps}) hv.u8(0x80 | nalType(n)).u16(1).u16(n.length).bytes(n);

        byte[] matrix = new Buf().u32(0x10000).u32(0).u32(0).u32(0).u32(0x10000).u32(0).u32(0).u32(0).u32(0x40000000).get();
        byte[] ftyp = box("ftyp", new Buf().str("iso6").u32(0).str("iso6").str("mp41").str("hvc1").get());
        byte[] mvhd = fullbox("mvhd", 0, 0, new Buf().u32(0).u32(0).u32(TIMESCALE).u32(0).u32(0x10000).u16(0x100).zeros(10)
                .bytes(matrix).zeros(24).u32(audio != null ? 3 : 2).get());
        byte[] tkhd = fullbox("tkhd", 0, 3, new Buf().u32(0).u32(0).u32(1).u32(0).u32(0).zeros(8).u16(0).u16(0).u16(0).u16(0)
                .bytes(matrix).u32((long) width << 16).u32((long) height << 16).get());
        byte[] mdhd = fullbox("mdhd", 0, 0, new Buf().u32(0).u32(0).u32(TIMESCALE).u32(0).u16(0x55C4).u16(0).get());
        byte[] hdlr = fullbox("hdlr", 0, 0, new Buf().u32(0).str("vide").zeros(12).str("DuoBeam").u8(0).get());
        byte[] vmhd = fullbox("vmhd", 0, 1, new Buf().zeros(8).get());
        byte[] dinf = box("dinf", fullbox("dref", 0, 0, new Buf().u32(1).get(), fullbox("url ", 0, 1)));
        byte[] entry = box("hvc1", new Buf().zeros(6).u16(1).zeros(16).u16(width).u16(height).u32(0x480000).u32(0x480000)
                .u32(0).u16(1).zeros(32).u16(0x18).u16(0xFFFF).get(), box("hvcC", hv.get()));
        byte[] stbl = box("stbl", fullbox("stsd", 0, 0, new Buf().u32(1).get(), entry),
                fullbox("stts", 0, 0, new Buf().u32(0).get()), fullbox("stsc", 0, 0, new Buf().u32(0).get()),
                fullbox("stsz", 0, 0, new Buf().u32(0).u32(0).get()), fullbox("stco", 0, 0, new Buf().u32(0).get()));
        byte[] trak = box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", vmhd, dinf, stbl)));
        byte[] trex1 = fullbox("trex", 0, 0, new Buf().u32(1).u32(1).u32(0).u32(0).u32(0).get());
        String codec = "hvc1." + (ptlFirst & 0x1F) + ".6.L" + level + ".B0";
        byte[] moov;
        if ("pcm".equals(audio)) {
            moov = box("moov", mvhd, trak, box("mvex", trex1));
            codec += ",pcm";
        } else if (audio != null) {
            boolean opus = "opus".equals(audio);
            byte[] trex2 = fullbox("trex", 0, 0, new Buf().u32(2).u32(1).u32(ThorAudio.frameSamples(opus)).u32(0).u32(0x02000000).get());
            moov = box("moov", mvhd, trak, audioTrak(dinf, opus), box("mvex", trex1, trex2));
            codec += opus ? ",opus" : ",mp4a.40.2";
        } else {
            moov = box("moov", mvhd, trak, box("mvex", trex1));
        }
        return new Init(new Buf().bytes(ftyp).bytes(moov).get(), codec);
    }

    private static byte[] audioTrak(byte[] dinf, boolean opus) {
        byte[] matrix = new Buf().u32(0x10000).u32(0).u32(0).u32(0).u32(0x10000).u32(0).u32(0).u32(0).u32(0x40000000).get();
        byte[] tkhd = fullbox("tkhd", 0, 3, new Buf().u32(0).u32(0).u32(2).u32(0).u32(0).zeros(8).u16(0).u16(1).u16(0x0100).u16(0)
                .bytes(matrix).u32(0).u32(0).get());
        byte[] mdhd = fullbox("mdhd", 0, 0, new Buf().u32(0).u32(0).u32(ThorAudio.RATE).u32(0).u16(0x55C4).u16(0).get());
        byte[] hdlr = fullbox("hdlr", 0, 0, new Buf().u32(0).str("soun").zeros(12).str("DuoBeam").u8(0).get());
        byte[] smhd = fullbox("smhd", 0, 0, new Buf().u16(0).u16(0).get());
        // esds: ES_Descriptor > DecoderConfig (AAC, audio) > AudioSpecificConfig 0x1190 (LC, 48 kHz, 2 channels) + SLConfig
        byte[] asc = {0x11, (byte) 0x90};
        byte[] dsi = new Buf().u8(0x05).u8(asc.length).bytes(asc).get();
        byte[] dcd = new Buf().u8(0x40).u8(0x15).u8(0).u16(0).u32(160_000).u32(160_000).bytes(dsi).get();
        byte[] dcdFull = new Buf().u8(0x04).u8(dcd.length).bytes(dcd).get();
        byte[] es = new Buf().u16(2).u8(0).bytes(dcdFull).u8(0x06).u8(1).u8(0x02).get();
        byte[] esds = fullbox("esds", 0, 0, new Buf().u8(0x03).u8(es.length).bytes(es).get());
        byte[] sampleEntry = new Buf().zeros(6).u16(1).zeros(8).u16(ThorAudio.CHANNELS).u16(16).u16(0).u16(0)
                .u32((long) ThorAudio.RATE << 16).get();
        // Opus in MP4: dOps (version 0, channels, pre-skip 312 = typical encoder delay, sample rate, gain 0, mapping family 0)
        byte[] dops = box("dOps", new Buf().u8(0).u8(ThorAudio.CHANNELS).u16(312).u32(ThorAudio.RATE).u16(0).u8(0).get());
        byte[] mp4a = opus ? box("Opus", sampleEntry, dops) : box("mp4a", sampleEntry, esds);
        byte[] stbl = box("stbl", fullbox("stsd", 0, 0, new Buf().u32(1).get(), mp4a),
                fullbox("stts", 0, 0, new Buf().u32(0).get()), fullbox("stsc", 0, 0, new Buf().u32(0).get()),
                fullbox("stsz", 0, 0, new Buf().u32(0).u32(0).get()), fullbox("stco", 0, 0, new Buf().u32(0).get()));
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", smhd, dinf, stbl)));
    }

    /** Audio frame -> moof+mdat for track 2; dts and duration in samples (48 kHz). */
    synchronized byte[] audioFragment(byte[] aac, long dts, int samples) {
        seq++;
        byte[] mfhd = fullbox("mfhd", 0, 0, new Buf().u32(seq).get());
        byte[] tfhd = fullbox("tfhd", 0, 0x020000, new Buf().u32(2).get());
        byte[] tfdt = fullbox("tfdt", 1, 0, new Buf().u64(dts).get());
        int trunLen = 12 + 4 + 4 + 12;
        int moofLen = 8 + mfhd.length + 8 + tfhd.length + tfdt.length + trunLen;
        byte[] trun = fullbox("trun", 0, 0x000701, new Buf().u32(1).u32(moofLen + 8).u32(samples).u32(aac.length).u32(0x02000000).get());
        byte[] moof = box("moof", mfhd, box("traf", tfhd, tfdt, trun));
        return new Buf().bytes(moof).bytes(box("mdat", aac)).get();
    }

    /** Annex-B frame -> moof+mdat; in-band VPS/SPS/PPS/AUD are dropped (parameter sets live in hvcC). */
    synchronized byte[] fragment(byte[] frame, int off, int len, long dts, int duration, boolean key) {
        Buf data = new Buf();
        for (byte[] n : splitAnnexB(frame, off, len)) {
            int t = nalType(n);
            if (t >= 32 && t <= 35) continue;
            data.u32(n.length).bytes(n);
        }
        byte[] mdatData = data.get();
        seq++;
        int flags = key ? 0x02000000 : 0x01010000;
        byte[] mfhd = fullbox("mfhd", 0, 0, new Buf().u32(seq).get());
        byte[] tfhd = fullbox("tfhd", 0, 0x020000, new Buf().u32(1).get()); // default-base-is-moof
        byte[] tfdt = fullbox("tfdt", 1, 0, new Buf().u64(dts).get());
        int trunLen = 12 + 4 + 4 + 12;
        int moofLen = 8 + mfhd.length + 8 + tfhd.length + tfdt.length + trunLen;
        byte[] trun = fullbox("trun", 0, 0x000701, new Buf().u32(1).u32(moofLen + 8).u32(duration).u32(mdatData.length).u32(flags).get());
        byte[] moof = box("moof", mfhd, box("traf", tfhd, tfdt, trun));
        if (moof.length != moofLen) throw new IllegalStateException("moof " + moof.length + " != " + moofLen);
        return new Buf().bytes(moof).bytes(box("mdat", mdatData)).get();
    }

    static byte[] toArray(ByteBuffer bb, int off, int len) {
        byte[] b = new byte[len];
        bb.position(off);
        bb.get(b, 0, len);
        return b;
    }
}
