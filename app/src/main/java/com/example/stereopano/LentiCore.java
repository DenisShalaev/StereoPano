package com.example.stereopano;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Linsenraster-Druck (Lentikular): erzeugt aus linkem/rechtem Auge (Fenster der Panorama) N Ansichten und verschachtelt
 * sie streifenweise passend zum Linsenraster. Ausgabe als PNG mit DPI-Angabe, zeilenweise (nie das ganze Bild im Speicher).
 * Reines Java ohne Android-Abhaengigkeit.
 *
 * Ansichten: Aus einer Disparitaetskarte (Blockvergleich, links->rechts) wird die Ansicht t (0 = links, 1 = rechts) per
 * Rueckwaertsverschiebung aus beiden Bildern gemischt.
 */
final class LentiCore {
    private LentiCore() {}

    interface Progress { boolean update(double frac); }   // false = abbrechen

    static final class Params {
        int outW, outH;            // Druckgroesse in Pixeln
        double dpi;                // Druckerauflosung
        double lpi;                // tatsaechliche Linsen pro Zoll
        int views;                 // Anzahl Ansichten
        boolean reverse = true;    // Reihenfolge der Streifen unter der Linse umkehren
        double depth = 1.0;        // Staerke der Tiefe (Disparitaet * depth)
        int marginX, marginY;      // Rand mit bunten Ausrichtungsstreifen (Pixel je Seite); 0 = keine
        int gap;                   // weisser Abstand zwischen Streifen und Bild (Pixel)
    }

    // ------------------------------------------------------------------ Disparitaet

    /**
     * Disparitaet (Pixel des Fensters; x_rechts = x_links + d) auf grobem Raster; Rueckgabe: {d[], w1, h1, ds}.
     * maxD: groesste erwartete Disparitaet in Fensterpixeln (symmetrisch gesucht).
     */
    static Object[] disparity(int[] l, int[] r, int w, int h, int maxD) {
        int ds = Math.max(1, (int) Math.round(Math.max(w, h) / 900.0));
        int w1 = w / ds, h1 = h / ds;
        float[] gl = gray(l, w, h, ds, w1, h1), gr = gray(r, w, h, ds, w1, h1);
        int D = Math.max(2, (int) Math.ceil(maxD / (double) ds));
        int rad = 3;
        float[] best = new float[w1 * h1];
        float[] cm1 = new float[w1 * h1], cp1 = new float[w1 * h1];
        int[] arg = new int[w1 * h1];
        float[] minC = new float[w1 * h1];
        java.util.Arrays.fill(minC, Float.MAX_VALUE);
        float[] sumC = new float[w1 * h1];
        // erste Runde: Minimum und Mittel
        for (int d = -D; d <= D; d++) {
            float[] c = aggregate(gl, gr, w1, h1, d, rad);
            for (int i = 0; i < c.length; i++) {
                sumC[i] += c[i];
                if (c[i] < minC[i]) { minC[i] = c[i]; arg[i] = d; }
            }
        }
        // zweite Runde: Nachbarkosten des Minimums (Subpixel)
        for (int d = -D; d <= D; d++) {
            float[] c = aggregate(gl, gr, w1, h1, d, rad);
            for (int i = 0; i < c.length; i++) {
                if (arg[i] == d - 1) cp1[i] = c[i];
                if (arg[i] == d + 1) cm1[i] = c[i];
            }
        }
        float[] conf = new float[w1 * h1];
        for (int i = 0; i < arg.length; i++) {
            double mean = sumC[i] / (2 * D + 1);
            float a = arg[i] == -D ? minC[i] : cm1[i], c3 = arg[i] == D ? minC[i] : cp1[i], b = minC[i];
            double den = a - 2 * b + c3;
            double sub = den > 1e-6 ? 0.5 * (a - c3) / den : 0;
            if (sub > 0.5) sub = 0.5; else if (sub < -0.5) sub = -0.5;
            best[i] = (float) (arg[i] + sub);
            // Vertrauen: Verhaeltnis Minimum zu Mittel (flache Flaechen: Verhaeltnis nahe 1 -> unsicher)
            double rat = mean > 1e-6 ? b / mean : 1;
            conf[i] = (float) Math.max(0, Math.min(1, (0.85 - rat) / 0.5));
        }
        // glaetten: konfidenzgewichteter Mittelwert, mehrfach (fuellt unsichere Flaechen aus der Umgebung)
        float[] d0 = best;
        for (int it = 0; it < 6; it++) d0 = smooth(d0, conf, w1, h1, 6);
        float[] out = new float[w1 * h1];
        for (int i = 0; i < out.length; i++) out[i] = d0[i] * ds;         // in Fensterpixeln
        return new Object[]{out, w1, h1, ds};
    }

    private static float[] gray(int[] a, int w, int h, int ds, int w1, int h1) {
        float[] g = new float[w1 * h1];
        for (int y = 0; y < h1; y++) for (int x = 0; x < w1; x++) {
            int s = 0, n = 0;
            for (int dy = 0; dy < ds; dy++) for (int dx = 0; dx < ds; dx++) {
                int c = a[(y * ds + dy) * w + x * ds + dx];
                s += (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
                n++;
            }
            g[y * w1 + x] = (float) s / n;
        }
        return g;
    }

    /** Aggregierte absolute Differenz fuer Verschiebung d (Kasten (2*rad+1)^2 ueber Integralbild). */
    private static float[] aggregate(float[] gl, float[] gr, int w, int h, int d, int rad) {
        double[] ii = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                int xr = x + d;
                float e = xr < 0 || xr >= w ? 40f : Math.abs(gl[y * w + x] - gr[y * w + xr]);
                row += e;
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row;
            }
        }
        float[] c = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - rad), y1 = Math.min(h, y + rad + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - rad), x1 = Math.min(w, x + rad + 1);
                double s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0];
                c[y * w + x] = (float) (s / ((x1 - x0) * (y1 - y0)));
            }
        }
        return c;
    }

    private static float[] smooth(float[] d, float[] conf, int w, int h, int rad) {
        float[] o = new float[w * h];
        // horizontal und vertikal getrennt (Kasten), gewichtet mit Vertrauen + kleiner Grundgewichtung
        float[] t = new float[w * h], tw = new float[w * h];
        for (int y = 0; y < h; y++) {
            double s = 0, sw = 0;
            for (int x = -rad; x < w; x++) {
                int xa = x + rad, xr = x - rad - 1;
                if (xa < w) { float wt = conf[y * w + xa] + 0.02f; s += d[y * w + xa] * wt; sw += wt; }
                if (xr >= 0) { float wt = conf[y * w + xr] + 0.02f; s -= d[y * w + xr] * wt; sw -= wt; }
                if (x >= 0) { t[y * w + x] = (float) s; tw[y * w + x] = (float) sw; }
            }
        }
        for (int x = 0; x < w; x++) {
            double s = 0, sw = 0;
            for (int y = -rad; y < h; y++) {
                int ya = y + rad, yr = y - rad - 1;
                if (ya < h) { s += t[ya * w + x]; sw += tw[ya * w + x]; }
                if (yr >= 0) { s -= t[yr * w + x]; sw -= tw[yr * w + x]; }
                if (y >= 0) o[y * w + x] = sw > 1e-9 ? (float) (s / sw) : d[y * w + x];
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ Verschachteln

    /** Phase und Ansichtsindex einer Ausgabespalte (public fuer Tests). */
    static int viewOf(int xo, double period, int views, boolean reverse) {
        double ph = ((xo + 0.5) % period) / period;
        int k = (int) Math.floor(ph * views);
        if (k >= views) k = views - 1;
        return reverse ? views - 1 - k : k;
    }

    /** Rendert das Druckbild und schreibt es als PNG. l, r: Fensterpixel (ARGB), w x h; maxD: groesste Disparitaet in Fensterpixeln. */
    static void render(int[] l, int[] r, int w, int h, int maxD, Params p, OutputStream os, Progress pg) throws Exception {
        Object[] dm = disparity(l, r, w, h, maxD);
        final float[] disp = (float[]) dm[0];
        final int w1 = (Integer) dm[1], h1 = (Integer) dm[2], ds = (Integer) dm[3];
        final double period = p.dpi / p.lpi;
        final int N = p.views;
        final double sx = (double) w / (p.outW - 2 * p.marginX), sy = (double) h / (p.outH - 2 * p.marginY);
        final int[] viewIdx = new int[p.outW];
        for (int x = 0; x < p.outW; x++) viewIdx[x] = viewOf(x, period, N, p.reverse);

        PngOut png = new PngOut(os, p.outW, p.outH, p.dpi);
        int chunk = 32;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            for (int y0 = 0; y0 < p.outH; y0 += chunk) {
                final int ya = y0, yb = Math.min(p.outH, y0 + chunk);
                final byte[][] rows = new byte[yb - ya][];
                List<Future<?>> fs = new ArrayList<>();
                int per = Math.max(1, (yb - ya + threads - 1) / threads);
                for (int s = ya; s < yb; s += per) {
                    final int s0 = s, s1 = Math.min(yb, s + per);
                    fs.add(ex.submit((Callable<Void>) () -> {
                        for (int yo = s0; yo < s1; yo++) rows[yo - ya] = renderRow(l, r, w, h, disp, w1, h1, ds, p, viewIdx, sx, sy, yo);
                        return null;
                    }));
                }
                for (Future<?> f : fs) f.get();
                for (byte[] row : rows) png.row(row);
                if (pg != null && !pg.update((double) yb / p.outH)) throw new java.util.concurrent.CancellationException();
            }
            png.finish();
        } finally {
            ex.shutdownNow();
        }
    }

    private static byte[] renderRow(int[] l, int[] r, int w, int h, float[] disp, int w1, int h1, int ds, Params p,
                                    int[] viewIdx, double sx, double sy, int yo) {
        byte[] row = new byte[p.outW * 3];
        if (p.marginX > 0 && (yo < p.marginY || yo >= p.outH - p.marginY)) {
            int innerGap = p.marginY - p.gap;                              // Streifen aussen, weisse Luecke innen
            boolean inGap = yo >= innerGap && yo < p.marginY || yo >= p.outH - p.marginY && yo < p.outH - innerGap;
            for (int xo = 0; xo < p.outW; xo++) markPixel(row, xo, viewIdx[xo], N0(p), inGap);
            return row;
        }
        int yi = yo - p.marginY;
        double v = (yi + 0.5) * sy - 0.5;
        if (v < 0) v = 0; if (v > h - 1.001) v = h - 1.001;
        int N = p.views;
        double gy = Math.min(h1 - 1.001, Math.max(0, v / ds));
        int gy0 = (int) gy; double fy = gy - gy0;
        for (int xo = 0; xo < p.outW; xo++) {
            if (p.marginX > 0 && (xo < p.marginX || xo >= p.outW - p.marginX)) {
                int innerGap = p.marginX - p.gap;
                boolean inGap = xo >= innerGap && xo < p.marginX || xo >= p.outW - p.marginX && xo < p.outW - innerGap;
                markPixel(row, xo, viewIdx[xo], N, inGap);
                continue;
            }
            double u = (xo - p.marginX + 0.5) * sx - 0.5;
            int k = viewIdx[xo];
            double t = N == 1 ? 0 : (double) k / (N - 1);
            double gx = Math.min(w1 - 1.001, Math.max(0, u / ds));
            int gx0 = (int) gx; double fx = gx - gx0;
            double d = (disp[gy0 * w1 + gx0] * (1 - fx) + disp[gy0 * w1 + gx0 + 1] * fx) * (1 - fy)
                     + (disp[(gy0 + 1) * w1 + gx0] * (1 - fx) + disp[(gy0 + 1) * w1 + gx0 + 1] * fx) * fy;
            d *= p.depth;
            double ul = u - t * d, ur = u + (1 - t) * d;
            int cl = bil(l, w, h, ul, v), cr = bil(r, w, h, ur, v);
            int R = (int) (((cl >> 16) & 255) * (1 - t) + ((cr >> 16) & 255) * t + 0.5);
            int G = (int) (((cl >> 8) & 255) * (1 - t) + ((cr >> 8) & 255) * t + 0.5);
            int B = (int) ((cl & 255) * (1 - t) + (cr & 255) * t + 0.5);
            row[xo * 3] = (byte) R; row[xo * 3 + 1] = (byte) G; row[xo * 3 + 2] = (byte) B;
        }
        return row;
    }

    private static int N0(Params p) { return p.views; }

    /** Ausrichtungsstreifen: jede Ansicht eine eigene Regenbogenfarbe (Lueckenpixel weiss). */
    private static void markPixel(byte[] row, int xo, int view, int views, boolean gap) {
        int c = gap ? 0xFFFFFF : hue(views <= 1 ? 0 : (double) view / views);
        row[xo * 3] = (byte) (c >> 16); row[xo * 3 + 1] = (byte) (c >> 8); row[xo * 3 + 2] = (byte) c;
    }

    /** Volle Saettigung, h in [0,1) -> RGB. */
    static int hue(double h) {
        double x = h * 6, f = x - Math.floor(x);
        int i = (int) Math.floor(x) % 6;
        double q = 1 - f, t = f;
        double r, g, b;
        switch (i) {
            case 0: r = 1; g = t; b = 0; break;
            case 1: r = q; g = 1; b = 0; break;
            case 2: r = 0; g = 1; b = t; break;
            case 3: r = 0; g = q; b = 1; break;
            case 4: r = t; g = 0; b = 1; break;
            default: r = 1; g = 0; b = q; break;
        }
        return ((int) Math.round(r * 255) << 16) | ((int) Math.round(g * 255) << 8) | (int) Math.round(b * 255);
    }

    private static int bil(int[] a, int w, int h, double x, double y) {
        if (x < 0) x = 0; else if (x > w - 1.001) x = w - 1.001;
        int x0 = (int) x, y0 = (int) y;
        double fx = x - x0, fy = y - y0;
        int c00 = a[y0 * w + x0], c10 = a[y0 * w + x0 + 1], c01 = a[(y0 + 1) * w + x0], c11 = a[(y0 + 1) * w + x0 + 1];
        int out = 0;
        for (int s = 0; s <= 16; s += 8) {
            double top = ((c00 >> s) & 255) * (1 - fx) + ((c10 >> s) & 255) * fx;
            double bot = ((c01 >> s) & 255) * (1 - fx) + ((c11 >> s) & 255) * fx;
            out |= ((int) (top * (1 - fy) + bot * fy + 0.5)) << s;
        }
        return out;
    }

    // ------------------------------------------------------------------ Testblatt

    /**
     * Testblatt zur Bestimmung des echten Linsenabstands: neun Baender mit leicht verschiedenen Streifenabstaenden
     * (Faktor -1 % ... +1 %). Ansicht 0 schwarz, die uebrigen weiss. Beim richtigen Abstand erscheint ein Band beim
     * Blick durch das Raster gleichmaessig dunkel/hell (ohne Muster).
     */
    static void testSheet(int outW, int outH, double dpi, double lpi, int views, boolean reverse, OutputStream os) throws IOException {
        PngOut png = new PngOut(os, outW, outH, dpi);
        int bands = 9;
        double[] fac = new double[bands];
        for (int i = 0; i < bands; i++) fac[i] = 1.0 + (i - 4) * 0.0025;
        for (int y = 0; y < outH; y++) {
            int b = Math.min(bands - 1, y * bands / outH);
            boolean gap = (y * bands % outH) < bands * 2;          // duenne weisse Trennlinie
            byte[] row = new byte[outW * 3];
            double period = dpi / (lpi * fac[b]);
            for (int x = 0; x < outW; x++) {
                boolean dark = !gap && viewOf(x, period, views, reverse) == 0;
                byte v = (byte) (dark ? 0 : 255);
                row[x * 3] = v; row[x * 3 + 1] = v; row[x * 3 + 2] = v;
            }
            png.row(row);
        }
        png.finish();
    }

    // ------------------------------------------------------------------ PNG-Schreiber

    static final class PngOut {
        private final OutputStream os;
        private final Deflater def = new Deflater(2);
        private final byte[] buf = new byte[1 << 16];
        private final java.io.ByteArrayOutputStream pend = new java.io.ByteArrayOutputStream(1 << 17);
        private boolean done;

        PngOut(OutputStream os, int w, int h, double dpi) throws IOException {
            this.os = os;
            os.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            byte[] ih = new byte[13];
            put(ih, 0, w); put(ih, 4, h);
            ih[8] = 8; ih[9] = 2; ih[10] = 0; ih[11] = 0; ih[12] = 0;
            chunk("IHDR", ih, ih.length);
            byte[] ph = new byte[9];
            int ppm = (int) Math.round(dpi / 0.0254);
            put(ph, 0, ppm); put(ph, 4, ppm); ph[8] = 1;
            chunk("pHYs", ph, ph.length);
        }

        void row(byte[] rgb) throws IOException {
            def.setInput(new byte[]{0});
            drain(false);
            def.setInput(rgb);
            drain(false);
        }

        private void drain(boolean finish) throws IOException {
            if (finish) def.finish();
            while (finish ? !def.finished() : !def.needsInput()) {
                int n = def.deflate(buf, 0, buf.length);
                if (n > 0) { pend.write(buf, 0, n); if (pend.size() >= 1 << 16) flushIdat(); }
            }
        }

        private void flushIdat() throws IOException {
            if (pend.size() == 0) return;
            byte[] d = pend.toByteArray();
            chunk("IDAT", d, d.length);
            pend.reset();
        }

        void finish() throws IOException {
            if (done) return;
            done = true;
            drain(true);
            flushIdat();
            chunk("IEND", new byte[0], 0);
            def.end();
            os.flush();
        }

        private void chunk(String type, byte[] d, int len) throws IOException {
            byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] lb = new byte[4];
            put(lb, 0, len);
            os.write(lb);
            os.write(t);
            os.write(d, 0, len);
            CRC32 c = new CRC32();
            c.update(t);
            c.update(d, 0, len);
            long v = c.getValue();
            os.write(new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v});
        }

        private static void put(byte[] b, int o, int v) {
            b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v;
        }
    }
}
