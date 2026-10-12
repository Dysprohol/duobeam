package com.duobeam;

import java.util.Arrays;
import java.util.Locale;

/** Collects timing samples (microseconds) and reports p50/p95/max in ms; reset on every report. Thread-safe. */
final class Stats {

    private long[] v = new long[1024];
    private int n;

    synchronized void add(long us) {
        if (n == v.length) v = Arrays.copyOf(v, n * 2);
        v[n++] = us;
    }

    /** "p50/p95/max" in ms, or "-" without samples. */
    synchronized String takeLine() {
        if (n == 0) return "-";
        long[] s = Arrays.copyOf(v, n);
        n = 0;
        Arrays.sort(s);
        return String.format(Locale.ROOT, "%.1f/%.1f/%.1f", s[s.length / 2] / 1000.0, s[(s.length * 95) / 100] / 1000.0,
                s[s.length - 1] / 1000.0);
    }
}
