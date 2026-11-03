package com.example.stereopano;

/**
 * Schlitzaufnahme (Slit-Scan) fuer Stereo-Rundumpanoramen, Version 2.
 *
 * Gegenueber Version 1 (nur Gierwinkel, jede Spalte genau einmal geschrieben):
 *  1. Volle Ausrichtung: Jeder Panoramapixel ist ein Strahl in Weltkoordinaten (Azimut/Elevation relativ
 *     zum Horizont). Er wird mit der gemessenen Geraete-Rotation (3x3-Matrix) in die Kamera zurueckgerechnet.
 *     Neigung und Seitenkippen der Hand werden dadurch ausgeglichen -> der Horizont bleibt gerade und
 *     die Spalten wackeln nicht mehr.
 *  2. Gewichtete Mehrfachbelegung: Jede Panoramaspalte wird aus mehreren aufeinanderfolgenden Bildern
 *     gemittelt (Dreiecksgewicht ueber +-deltaDeg). Das glaettet Zeitstempel-/Sensorrauschen,
 *     Helligkeitsspruenge, Bildrauschen und harte Kanten durch Parallaxe (weicher Uebergang statt Stufe).
 *  3. Keine Richtungsannahme: Beliebiges Hin- und Herdrehen ist erlaubt; fertig ist das Bild, wenn alle
 *     Spalten beider Augen genug Gewicht haben. Unvollstaendige Aufnahmen werden auf den laengsten
 *     gemeinsam abgedeckten Bereich zugeschnitten.
 *
 * Geometrie (360-Grad-Drehung mit ausgestrecktem Arm): Strahl bei +alpha rechts der optischen Achse
 * -> linkes Auge, bei -alpha -> rechtes Auge; Stereobasis ca. 2 * Armlaenge * sin(alpha).
 *
 * Keine Android-Abhaengigkeiten (testbar).
 */
final class StereoStitcher {

    /** Bildquelle in aufrechten Koordinaten (Hochformat, x nach rechts, y nach unten). */
    interface Frame {
        int rgb(int ux, int uy);
    }

    static final class Result {
        int[] left, right;
        int panoW, panoH;      // Groesse des vollen 360-Grad-Arrays
        int x0, width;         // verwertbarer Ausschnitt (Spalten)
        boolean complete;
    }

    private static final int W_FULL = 8;    // Gewicht in der Mitte des Dreiecks
    private static final int W_MIN = 4;     // Mindestgewicht, ab dem eine Spalte als abgedeckt gilt

    /** Akkumulator eines Auges. */
    private static final class Eye {
        final double offsetDeg;
        char[] r, g, b;      // gewichtete Summen (max 255 * 255 = 65025 passt in char)
        byte[] w;            // Gewicht je Pixel (0..255)
        final int[] colW;    // Gewicht je Spalte (Absicherung/Fortschritt)
        int covered;         // Anzahl Spalten mit colW >= W_MIN

        Eye(double offsetDeg, int n, int panoW) {
            this.offsetDeg = offsetDeg;
            r = new char[n]; g = new char[n]; b = new char[n];
            w = new byte[n];
            colW = new int[panoW];
        }
    }

    final int frameW, frameH, panoW, panoH;
    final double fPx, deltaDeg, az0Deg;
    private final double[] sinE, cosE;
    private final Eye eyeL, eyeR;
    private boolean closed;
    private Result cached;

    /**
     * @param frameW   Breite des aufrechten Bildes in Pixel
     * @param frameH   Hoehe des aufrechten Bildes in Pixel
     * @param fPx      Brennweite in Pixeln
     * @param alphaDeg Winkel der Streifen zur Bildmitte (Stereobasis)
     * @param deltaDeg halbe Breite des Mittelungsfensters (Grad)
     * @param az0Deg   Welt-Azimut (Grad, im Uhrzeigersinn ab Nord) der Startrichtung
     */
    StereoStitcher(int frameW, int frameH, double fPx, double alphaDeg, double deltaDeg, double az0Deg) {
        this.frameW = frameW;
        this.frameH = frameH;
        this.fPx = fPx;
        this.deltaDeg = deltaDeg;
        this.az0Deg = az0Deg;
        this.panoW = (int) Math.round(2 * Math.PI * fPx) & ~1;
        double ppd = panoW / 360.0; // Pixel pro Grad
        double vfov = 2 * Math.toDegrees(Math.atan((frameH / 2.0) / fPx));
        this.panoH = (int) Math.round(vfov * ppd) & ~1;
        this.sinE = new double[panoH];
        this.cosE = new double[panoH];
        for (int v = 0; v < panoH; v++) {
            double el = Math.toRadians((panoH / 2.0 - (v + 0.5)) / ppd);   // Elevation ueber dem Horizont
            sinE[v] = Math.sin(el);
            cosE[v] = Math.cos(el);
        }
        int n = panoW * panoH;
        this.eyeL = new Eye(+alphaDeg, n, panoW);
        this.eyeR = new Eye(-alphaDeg, n, panoW);
    }

    synchronized void close() {
        closed = true;
    }

    /**
     * @param psi Drehwinkel der Kamera-Blickrichtung seit Start (Grad, im Uhrzeigersinn, unbegrenzt)
     * @param rot Geraete->Welt-Rotation (3x3 zeilenweise, Welt = Ost/Nord/Oben) zum Aufnahmezeitpunkt
     */
    synchronized void addFrame(Frame f, double psi, float[] rot) {
        if (closed || cached != null || rot == null || Double.isNaN(psi)) return;
        accumulate(eyeL, f, psi, rot);
        accumulate(eyeR, f, psi, rot);
    }

    private void accumulate(Eye e, Frame f, double psi, float[] R) {
        double centre = psi + e.offsetDeg;
        long x0 = (long) Math.ceil((centre - deltaDeg) / 360.0 * panoW - 0.5);
        long x1 = (long) Math.floor((centre + deltaDeg) / 360.0 * panoW - 0.5);
        double qx = R[6], qy = R[7], qz = R[8];   // Welt-"Oben" im Geraetesystem (Spalte 2 von R^T)

        for (long x = x0; x <= x1; x++) {
            double phi = (x + 0.5) / panoW * 360.0;              // Panorama-Azimut relativ zum Start
            double a = phi - centre;                              // -delta .. +delta
            int wt = (int) Math.round(W_FULL * (1 - Math.abs(a) / deltaDeg));
            if (wt <= 0) continue;
            int col = (int) Math.floorMod(x, (long) panoW);

            double A = Math.toRadians(az0Deg + phi);              // Welt-Azimut des Strahls
            double sA = Math.sin(A), cA = Math.cos(A);
            // c = R^T * d,  d = (sinA cosE, cosA cosE, sinE)  ->  c = cosE * P + sinE * Q
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];

            for (int v = 0; v < panoH; v++) {
                double cx = cosE[v] * px + sinE[v] * qx;
                double cy = cosE[v] * py + sinE[v] * qy;
                double cz = cosE[v] * pz + sinE[v] * qz;
                if (cz > -1e-3) continue;                          // Strahl liegt hinter der Kamera
                double ux = frameW / 2.0 + fPx * cx / -cz;
                double uy = frameH / 2.0 - fPx * cy / -cz;
                if (ux < 0 || uy < 0 || ux > frameW - 1.001 || uy > frameH - 1.001) continue;
                int idx = v * panoW + col;
                int wp = e.w[idx] & 0xFF;
                if (wp + wt > 255) continue;                       // Ueberlaufschutz bei sehr langsamer Drehung
                int c = bilinear(f, ux, uy);
                e.r[idx] += (char) (wt * ((c >> 16) & 255));
                e.g[idx] += (char) (wt * ((c >> 8) & 255));
                e.b[idx] += (char) (wt * (c & 255));
                e.w[idx] = (byte) (wp + wt);
            }
            int before = e.colW[col];
            e.colW[col] = before + wt;
            if (before < W_MIN && before + wt >= W_MIN) e.covered++;
        }
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
        return eyeL.covered >= panoW && eyeR.covered >= panoW;
    }

    /** Fortschritt 0..1 (Anteil abgedeckter Spalten des schwaecheren Auges). */
    synchronized double progress() {
        return Math.min(eyeL.covered, eyeR.covered) / (double) panoW;
    }

    /** Fertigt das Ergebnis (einmalig); danach sind keine weiteren Bilder mehr moeglich. */
    synchronized Result result() {
        if (cached != null) return cached;
        closed = true;
        Result r = new Result();
        r.panoW = panoW;
        r.panoH = panoH;

        // laengster zusammenhaengender Bereich, den beide Augen abdecken
        int bestStart = 0, bestLen = 0, runStart = 0, runLen = 0;
        for (int x = 0; x < panoW; x++) {
            if (eyeL.colW[x] >= W_MIN && eyeR.colW[x] >= W_MIN) {
                if (runLen == 0) runStart = x;
                runLen++;
                if (runLen > bestLen) { bestLen = runLen; bestStart = runStart; }
            } else {
                runLen = 0;
            }
        }
        r.complete = bestLen >= panoW;
        r.x0 = r.complete ? 0 : bestStart;
        r.width = bestLen;

        r.left = finish(eyeL);
        eyeL.r = eyeL.g = eyeL.b = null;
        eyeL.w = null;
        r.right = finish(eyeR);
        eyeR.r = eyeR.g = eyeR.b = null;
        eyeR.w = null;
        cached = r;
        return r;
    }

    private int[] finish(Eye e) {
        int n = panoW * panoH;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int wp = e.w[i] & 0xFF;
            if (wp == 0) { out[i] = 0xFF000000; continue; }
            int h = wp >> 1;
            out[i] = 0xFF000000
                    | (((e.r[i] + h) / wp) << 16)
                    | (((e.g[i] + h) / wp) << 8)
                    | ((e.b[i] + h) / wp);
        }
        return out;
    }
}
