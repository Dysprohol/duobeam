package com.duobeam.tv;

import java.util.Arrays;
import java.util.Locale;

/** Timing samples (ms) over the last ~4 s at 60 fps; reports p50/p95/max. Thread-safe. */
final class Stats {

    private final double[] v = new double[256];
    private int n, pos;

    synchronized void add(double ms) {
        v[pos] = ms;
        pos = (pos + 1) % v.length;
        if (n < v.length) n++;
    }

    synchronized double[] pct() {
        if (n == 0) return null;
        double[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return new double[] {s[n / 2], s[(n * 95) / 100], s[n - 1]};
    }

    synchronized void clear() {
        n = 0;
        pos = 0;
    }

    /** "p50/p95/max" or "-". */
    String line() {
        double[] p = pct();
        return p == null ? "-" : String.format(Locale.ROOT, "%.0f/%.0f/%.0f", p[0], p[1], p[2]);
    }
}
