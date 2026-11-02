package com.example.stereopano;

/**
 * Schlitzaufnahme (Slit-Scan): Aus jedem Kamerabild werden schmale Streifen links und rechts
 * der Bildmitte in zwei Panoramen (linkes / rechtes Auge) einsortiert.
 *
 * Geometrie: Dreht man sich mit ausgestrecktem Arm im Uhrzeigersinn, wandert die Kamera auf einem
 * Kreis (Radius = Armlaenge). Ein Strahl, der um +alpha rechts von der optischen Achse liegt, stammt
 * von einer Kameraposition, die weiter links liegt -> linkes Auge. Ein Strahl bei -alpha -> rechtes Auge.
 * Die Stereobasis ist daher ca. 2 * Armlaenge * sin(alpha).
 *
 * Diese Klasse hat keine Android-Abhaengigkeiten (gut testbar).
 */
final class StereoStitcher {

    /** Bildquelle in "aufrechten" Koordinaten (Hochformat, x nach rechts, y nach unten). */
    interface Frame {
        int rgb(int ux, int uy);
    }

    static final class Result {
        int[] left, right;
        int panoW, panoH;      // Groesse des vollen 360-Grad-Arrays
        int x0, width;         // aufgenommener Ausschnitt (Spalten)
        boolean complete;
    }

    private static final class Eye {
        final double offsetDeg;
        final long start, end;
        long last;

        Eye(double offsetDeg, int panoW) {
            this.offsetDeg = offsetDeg;
            this.start = (long) Math.floor(offsetDeg / 360.0 * panoW);
            this.end = start + panoW - 1;
            this.last = start - 1;
        }
    }

    final int frameW, frameH, panoW, panoH;
    final double fPx;
    private final int[] left, right;
    private final double[] srcY;
    private final Eye eyeL, eyeR;
    private boolean closed;

    /**
     * @param frameW   Breite des aufrechten Bildes in Pixel
     * @param frameH   Hoehe des aufrechten Bildes in Pixel
     * @param fPx      Brennweite in Pixeln
     * @param alphaDeg Winkel der Streifen zur Bildmitte (Stereobasis)
     */
    StereoStitcher(int frameW, int frameH, double fPx, double alphaDeg) {
        this.frameW = frameW;
        this.frameH = frameH;
        this.fPx = fPx;
        this.panoW = (int) Math.round(2 * Math.PI * fPx) & ~1;
        double ppd = panoW / 360.0; // Pixel pro Grad
        double vfov = 2 * Math.toDegrees(Math.atan((frameH / 2.0) / fPx));
        this.panoH = (int) Math.round(vfov * ppd) & ~1;
        this.left = new int[panoW * panoH];
        this.right = new int[panoW * panoH];
        this.srcY = new double[panoH];
        for (int v = 0; v < panoH; v++) {
            // Elevation der Zeile (aequirektangular) -> Zeile im Kamerabild (Pinhole)
            double el = Math.toRadians((panoH / 2.0 - (v + 0.5)) / ppd);
            double sy = frameH / 2.0 - fPx * Math.tan(el);
            srcY[v] = Math.max(0, Math.min(frameH - 1.001, sy));
        }
        this.eyeL = new Eye(+alphaDeg, panoW);
        this.eyeR = new Eye(-alphaDeg, panoW);
    }

    synchronized void close() {
        closed = true;
    }

    /** psi = Drehwinkel seit Start in Grad (im Uhrzeigersinn, nicht auf 360 begrenzt). */
    synchronized void addFrame(Frame f, double psi) {
        if (closed || Double.isNaN(psi)) return;
        fill(left, eyeL, f, psi);
        fill(right, eyeR, f, psi);
    }

    private void fill(int[] dst, Eye e, Frame f, double psi) {
        long cur = Math.min(e.end, (long) Math.floor((psi + e.offsetDeg) / 360.0 * panoW));
        for (long x = e.last + 1; x <= cur; x++) {
            // Winkel des Strahls relativ zur optischen Achse des aktuellen Bildes
            double a = Math.toRadians((x + 0.5) / panoW * 360.0 - psi);
            if (Math.abs(a) > Math.toRadians(80)) continue;
            double ux = frameW / 2.0 + fPx * Math.tan(a);
            ux = Math.max(0, Math.min(frameW - 1.001, ux));
            int col = (int) Math.floorMod(x, (long) panoW);
            for (int v = 0; v < panoH; v++) {
                dst[v * panoW + col] = bilinear(f, ux, srcY[v]);
            }
        }
        if (cur > e.last) e.last = cur;
    }

    private int bilinear(Frame f, double x, double y) {
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, frameW - 1), y1 = Math.min(y0 + 1, frameH - 1);
        double dx = x - x0, dy = y - y0;
        int c00 = f.rgb(x0, y0), c10 = f.rgb(x1, y0), c01 = f.rgb(x0, y1), c11 = f.rgb(x1, y1);
        int out = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) {
            double top = ((c00 >> s) & 255) * (1 - dx) + ((c10 >> s) & 255) * dx;
            double bot = ((c01 >> s) & 255) * (1 - dx) + ((c11 >> s) & 255) * dx;
            out |= ((int) Math.round(top * (1 - dy) + bot * dy)) << s;
        }
        return out;
    }

    synchronized boolean isComplete() {
        return eyeL.last >= eyeL.end && eyeR.last >= eyeR.end;
    }

    /** Fortschritt 0..1 (das rechte Auge hinkt hinterher). */
    synchronized double progress() {
        return Math.max(0, Math.min(1, (eyeR.last - eyeR.start + 1) / (double) panoW));
    }

    synchronized Result result() {
        Result r = new Result();
        r.left = left;
        r.right = right;
        r.panoW = panoW;
        r.panoH = panoH;
        r.complete = isComplete();
        if (r.complete) {
            r.x0 = 0;
            r.width = panoW;
        } else {
            long s = eyeL.start;
            long e = Math.min(eyeL.last, eyeR.last);
            r.x0 = (int) Math.max(0, s);
            r.width = (int) Math.max(0, Math.min(e, panoW - 1) - r.x0 + 1);
        }
        return r;
    }
}
