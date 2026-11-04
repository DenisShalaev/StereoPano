package com.example.stereopano;

/**
 * Schlitzaufnahme (Slit-Scan) fuer Stereo-Rundumpanoramen, Version 3.
 *
 * Aufbau:
 *  1. Volle Ausrichtung: Jeder Panoramapixel ist ein Strahl in Weltkoordinaten (Azimut/Elevation relativ
 *     zum Horizont). Er wird mit der gemessenen Geraete-Rotation in die Kamera zurueckgerechnet.
 *  2. Gewichtete Mehrfachbelegung: Jede Panoramaspalte wird aus mehreren Bildern gemittelt
 *     (Dreiecksgewicht ueber +-deltaDeg).
 *  3. NEU - Bildbasierte Feinausrichtung: Die Sensorlage ist nie perfekt (Zeitversatz, Rauschen,
 *     Stabilisierung). Vor dem Einsortieren wird das neue Bild mit dem bereits aufgebauten Panorama
 *     verglichen (Ueberlappung in den zuletzt geschriebenen Spalten) und um dx/dy Pixel nachgeschoben.
 *     Das beseitigt Treppen an waagerechten Kanten UND macht die Mittelung scharf, weil sich die
 *     gemittelten Bilder jetzt wirklich decken (vorher: Fehlausrichtung von 2-5 px = Unschaerfe).
 *     Beim Schliessen des Kreises (Ende ueberlappt Anfang) wird das Ende so automatisch an den Anfang
 *     angepasst -> kein harter Stoss bei 0/360 Grad.
 *  4. Strenge Fertig-Bedingung (alle Spalten abgedeckt UND 360 Grad + Ueberlappung erreicht). Kleine Luecken
 *     (bis 2 % der Breite) werden interpoliert, groessere werden nicht erfunden, sondern abgeschnitten.
 *  5. Optionale Nachschaerfung (Unsharp-Mask nur auf der Helligkeit, keine Farbsaeume).
 *
 * Keine Android-Abhaengigkeiten (testbar).
 */
final class StereoStitcher {

    /** Bildquelle in aufrechten Koordinaten (Hochformat, x nach rechts, y nach unten). */
    interface Frame {
        int rgb(int ux, int uy);

        /** Helligkeit 0..255 (Standard: aus rgb; Implementierungen koennen das schneller). */
        default int luma(int ux, int uy) {
            int c = rgb(ux, uy);
            return (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
        }
    }

    static final class Result {
        int[] left, right;
        int panoW, panoH;      // Groesse des vollen 360-Grad-Arrays
        int x0, width;         // verwertbarer Ausschnitt (Spalten)
        boolean complete;
    }

    private static final int W_FULL = 8;    // Gewicht in der Mitte des Dreiecks
    private static final int W_MIN = 4;     // Mindestgewicht, ab dem eine Spalte als abgedeckt gilt
    private static final int W_REF = 12;    // Spalten mit mindestens diesem Gewicht dienen als Referenz
    private static final int REG_COLS = 6;  // Anzahl Referenzspalten fuer die Ausrichtung
    private static final double REG_BACK_DEG = 3.0;   // so weit hinter der aktuellen Spalte wird verglichen

    /** Akkumulator eines Auges. */
    private static final class Eye {
        final double offsetDeg;
        char[] r, g, b;      // gewichtete Summen (max 255 * 255 = 65025 passt in char)
        byte[] w;            // Gewicht je Pixel (0..255)
        final int[] colW;    // Gewicht je Spalte
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
    private final boolean register;
    private boolean closed;
    private Result cached;
    private double maxPsi = -1e9;

    // Arbeitsfelder der Feinausrichtung
    private final int regMax;
    private final double[] sUx, sUy, sRef;
    private final int regRY, regRX;
    /** Zuletzt verwendete Verschiebung (nur zur Diagnose). */
    double lastDx, lastDy;

    StereoStitcher(int frameW, int frameH, double fPx, double alphaDeg, double deltaDeg, double az0Deg) {
        this(frameW, frameH, fPx, alphaDeg, deltaDeg, az0Deg, true);
    }

    /**
     * @param frameW   Breite des aufrechten Bildes in Pixel
     * @param frameH   Hoehe des aufrechten Bildes in Pixel
     * @param fPx      Brennweite in Pixeln
     * @param alphaDeg Winkel der Streifen zur Bildmitte (Stereobasis)
     * @param deltaDeg halbe Breite des Mittelungsfensters (Grad)
     * @param az0Deg   Welt-Azimut (Grad, im Uhrzeigersinn ab Nord) der Startrichtung
     * @param register bildbasierte Feinausrichtung verwenden
     */
    StereoStitcher(int frameW, int frameH, double fPx, double alphaDeg, double deltaDeg, double az0Deg, boolean register) {
        this.frameW = frameW;
        this.frameH = frameH;
        this.fPx = fPx;
        this.deltaDeg = deltaDeg;
        this.az0Deg = az0Deg;
        this.register = register;
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
        this.regRY = Math.max(6, frameH / 160);
        this.regRX = Math.max(3, regRY / 2);
        this.regMax = REG_COLS * (panoH / 3 + 2);
        this.sUx = new double[regMax];
        this.sUy = new double[regMax];
        this.sRef = new double[regMax];
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
        double dx = 0, dy = 0;
        if (register) {
            double[] s = estimateShift(f, psi, rot);
            dx = s[0];
            dy = s[1];
        }
        lastDx = dx;
        lastDy = dy;
        accumulate(eyeL, f, psi, rot, dx, dy);
        accumulate(eyeR, f, psi, rot, dx, dy);
        if (psi > maxPsi) maxPsi = psi;
    }

    // ------------------------------------------------------------------ Einsortieren

    private void accumulate(Eye e, Frame f, double psi, float[] R, double sx, double sy) {
        double centre = psi + e.offsetDeg;
        long x0 = (long) Math.ceil((centre - deltaDeg) / 360.0 * panoW - 0.5);
        long x1 = (long) Math.floor((centre + deltaDeg) / 360.0 * panoW - 0.5);
        double qx = R[6], qy = R[7], qz = R[8];   // Welt-"Oben" im Geraetesystem

        for (long x = x0; x <= x1; x++) {
            double phi = (x + 0.5) / panoW * 360.0;              // Panorama-Azimut relativ zum Start
            double a = phi - centre;                              // -delta .. +delta
            int wt = (int) Math.round(W_FULL * (1 - Math.abs(a) / deltaDeg));
            if (wt <= 0) continue;
            int col = (int) Math.floorMod(x, (long) panoW);

            double A = Math.toRadians(az0Deg + phi);              // Welt-Azimut des Strahls
            double sA = Math.sin(A), cA = Math.cos(A);
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];

            for (int v = 0; v < panoH; v++) {
                double cx = cosE[v] * px + sinE[v] * qx;
                double cy = cosE[v] * py + sinE[v] * qy;
                double cz = cosE[v] * pz + sinE[v] * qz;
                if (cz > -1e-3) continue;                          // Strahl liegt hinter der Kamera
                double ux = frameW / 2.0 + fPx * cx / -cz + sx;
                double uy = frameH / 2.0 - fPx * cy / -cz + sy;
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

    private double lumaBilinear(Frame f, double x, double y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > frameW - 1.001) x = frameW - 1.001;
        if (y > frameH - 1.001) y = frameH - 1.001;
        int x0 = (int) x, y0 = (int) y;
        double dx = x - x0, dy = y - y0;
        double top = f.luma(x0, y0) * (1 - dx) + f.luma(x0 + 1, y0) * dx;
        double bot = f.luma(x0, y0 + 1) * (1 - dx) + f.luma(x0 + 1, y0 + 1) * dx;
        return top * (1 - dy) + bot * dy;
    }

    // ------------------------------------------------------------------ Feinausrichtung

    /**
     * Schaetzt, um wie viele Bildpixel (dx, dy) das neue Bild gegenueber dem Sensorwert verschoben werden muss,
     * damit es zum bereits aufgebauten Panorama (linkes Auge) passt. Rueckgabe {0,0}, wenn nichts Sicheres gefunden wird.
     */
    private double[] estimateShift(Frame f, double psi, float[] R) {
        final double[] zero = {0, 0};
        Eye e = eyeL;
        double centre = psi + e.offsetDeg;
        long xa = (long) Math.floor((centre - REG_BACK_DEG) / 360.0 * panoW - 0.5);
        long xb = (long) Math.floor((centre + deltaDeg) / 360.0 * panoW - 0.5);

        // Spalten mit ausreichend Gewicht (also bereits "fertig") auswaehlen
        int[] cols = new int[REG_COLS];
        long[] xs = new long[REG_COLS];
        int nc = 0;
        long span = xb - xa + 1;
        if (span <= 0) return zero;
        long step = Math.max(1, span / REG_COLS);
        for (long x = xa; x <= xb && nc < REG_COLS; x += step) {
            int col = (int) Math.floorMod(x, (long) panoW);
            if (e.colW[col] >= W_REF) { cols[nc] = col; xs[nc] = x; nc++; }
        }
        if (nc < 2) return zero;

        double qx = R[6], qy = R[7], qz = R[8];
        int n = 0;
        double margin = regRY + 3;
        for (int k = 0; k < nc; k++) {
            double phi = (xs[k] + 0.5) / panoW * 360.0;
            double A = Math.toRadians(az0Deg + phi);
            double sA = Math.sin(A), cA = Math.cos(A);
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];
            for (int v = 3; v < panoH - 3 && n < regMax; v += 3) {
                int idx = v * panoW + cols[k];
                int wp = e.w[idx] & 0xFF;
                if (wp < 6) continue;
                double cx = cosE[v] * px + sinE[v] * qx;
                double cy = cosE[v] * py + sinE[v] * qy;
                double cz = cosE[v] * pz + sinE[v] * qz;
                if (cz > -1e-3) continue;
                double ux = frameW / 2.0 + fPx * cx / -cz;
                double uy = frameH / 2.0 - fPx * cy / -cz;
                if (ux < margin || uy < margin || ux > frameW - 1 - margin || uy > frameH - 1 - margin) continue;
                sUx[n] = ux;
                sUy[n] = uy;
                sRef[n] = (77.0 * e.r[idx] + 150.0 * e.g[idx] + 29.0 * e.b[idx]) / (256.0 * wp);
                n++;
            }
        }
        if (n < 300) return zero;

        // genug Struktur zum Vergleichen? (glatte Waende liefern keine verlaessliche Verschiebung)
        double m = 0, m2 = 0;
        for (int i = 0; i < n; i++) { m += sRef[i]; m2 += sRef[i] * sRef[i]; }
        m /= n;
        if (m2 / n - m * m < 36) return zero;

        double cost0 = cost(f, n, 0, 0);
        if (cost0 < 4) return zero;                        // passt schon (Rauschniveau)

        // 1) vertikal (Nicken), 2) horizontal (Gieren) - getrennt gesucht
        double bestY = 0, bestCy = cost0;
        double[] cy = new double[2 * regRY + 1];
        for (int s = -regRY; s <= regRY; s++) {
            cy[s + regRY] = cost(f, n, 0, s);
            if (cy[s + regRY] < bestCy) { bestCy = cy[s + regRY]; bestY = s; }
        }
        double dy = subpixel(cy, (int) bestY + regRY, bestY);
        if (Math.abs(bestY) >= regRY) dy = 0;

        double bestX = 0, bestCx = cost(f, n, 0, dy);
        double[] cxs = new double[2 * regRX + 1];
        for (int s = -regRX; s <= regRX; s++) {
            cxs[s + regRX] = cost(f, n, s, dy);
            if (cxs[s + regRX] < bestCx) { bestCx = cxs[s + regRX]; bestX = s; }
        }
        double dx = subpixel(cxs, (int) bestX + regRX, bestX);
        if (Math.abs(bestX) >= regRX) dx = 0;

        double costBest = cost(f, n, dx, dy);
        if (costBest > 0.85 * cost0) return zero;          // keine klare Verbesserung -> Sensorwert behalten
        return new double[]{dx, dy};
    }

    /** Parabel durch drei Kostenwerte -> Minimum mit Teilpixelgenauigkeit. */
    private static double subpixel(double[] c, int i, double best) {
        if (i <= 0 || i >= c.length - 1) return best;
        double a = c[i - 1], b = c[i], d = c[i + 1];
        double den = a - 2 * b + d;
        if (den <= 1e-9) return best;
        double off = 0.5 * (a - d) / den;
        if (off > 0.5) off = 0.5;
        if (off < -0.5) off = -0.5;
        return best + off;
    }

    /** Varianz der Differenz (Helligkeitsunterschiede zwischen den Bildern werden ignoriert). */
    private double cost(Frame f, int n, double sx, double sy) {
        double s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double d = lumaBilinear(f, sUx[i] + sx, sUy[i] + sy) - sRef[i];
            s1 += d;
            s2 += d * d;
        }
        return (s2 - s1 * s1 / n) / n;
    }

    // ------------------------------------------------------------------ Status

    /** Fertig: alle Spalten beider Augen abgedeckt UND mindestens 360 Grad + Ueberlappung gedreht. */
    synchronized boolean isComplete() {
        return eyeL.covered >= panoW && eyeR.covered >= panoW && maxPsi >= 360.0 + deltaDeg;
    }

    /** Fortschritt 0..1 (Anteil abgedeckter Spalten des schwaecheren Auges). */
    synchronized double progress() {
        return Math.min(eyeL.covered, eyeR.covered) / (double) panoW;
    }

    // ------------------------------------------------------------------ Ergebnis

    synchronized Result result() { return result(0f); }

    /**
     * Fertigt das Ergebnis (einmalig); danach sind keine weiteren Bilder mehr moeglich.
     * @param sharpen Staerke der Nachschaerfung (0 = aus, ca. 0.3..1.0 sinnvoll)
     */
    synchronized Result result(float sharpen) {
        if (cached != null) return cached;
        closed = true;
        Result r = new Result();
        r.panoW = panoW;
        r.panoH = panoH;

        // abgedeckte Spalten (beide Augen); kleine Luecken (bis 2 %) werden ueberbrueckt, grosse nicht erfunden
        int gapMax = Math.max(8, (int) (panoW * 0.02));
        boolean[] ok = new boolean[panoW];
        int nOk = 0;
        for (int x = 0; x < panoW; x++) {
            ok[x] = eyeL.colW[x] >= W_MIN && eyeR.colW[x] >= W_MIN;
            if (ok[x]) nOk++;
        }
        boolean[] okb = ok.clone();
        int firstOk = -1;
        for (int x = 0; x < panoW; x++) if (ok[x]) { firstOk = x; break; }
        boolean allBridged = firstOk >= 0;
        if (firstOk >= 0 && nOk < panoW) {
            int i = 0;
            while (i < panoW) {                              // zirkulaer ab dem ersten gueltigen Index
                int x = (firstOk + i) % panoW;
                if (ok[x]) { i++; continue; }
                int len = 0;
                while (i + len < panoW && !ok[(firstOk + i + len) % panoW]) len++;
                if (len <= gapMax) {
                    for (int k = 0; k < len; k++) okb[(firstOk + i + k) % panoW] = true;
                } else {
                    allBridged = false;
                }
                i += len;
            }
        }

        if (allBridged) {
            r.complete = true;
            r.x0 = 0;
            r.width = panoW;
        } else {
            int bestStart = 0, bestLen = 0, runStart = 0, runLen = 0;
            for (int x = 0; x < panoW; x++) {
                if (okb[x]) {
                    if (runLen == 0) runStart = x;
                    runLen++;
                    if (runLen > bestLen) { bestLen = runLen; bestStart = runStart; }
                } else {
                    runLen = 0;
                }
            }
            r.complete = false;
            r.x0 = bestStart;
            r.width = bestLen;
        }

        r.left = finish(eyeL, gapMax, sharpen);
        eyeL.r = eyeL.g = eyeL.b = null;
        eyeL.w = null;
        r.right = finish(eyeR, gapMax, sharpen);
        eyeR.r = eyeR.g = eyeR.b = null;
        eyeR.w = null;
        cached = r;
        return r;
    }

    private int[] finish(Eye e, int gapMax, float sharpen) {
        int n = panoW * panoH;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int wp = e.w[i] & 0xFF;
            if (wp > 0) {
                int h = wp >> 1;
                out[i] = 0xFF000000
                        | (((e.r[i] + h) / wp) << 16)
                        | (((e.g[i] + h) / wp) << 8)
                        | ((e.b[i] + h) / wp);
            }
        }

        // Kleine horizontale Luecken (bis gapMax Pixel) zwischen zwei gueltigen Pixeln linear fuellen (zirkulaer).
        // Groessere Luecken bleiben schwarz - sie werden beim Zuschneiden entfernt statt zu verschmieren.
        for (int v = 0; v < panoH; v++) {
            int row = v * panoW;
            int first = -1;
            for (int c = 0; c < panoW; c++) if (out[row + c] != 0) { first = c; break; }
            if (first < 0) continue;
            int i = 0;
            while (i < panoW) {
                int c = (first + i) % panoW;
                if (out[row + c] != 0) { i++; continue; }
                int len = 0;
                while (i + len < panoW && out[row + (first + i + len) % panoW] == 0) len++;
                if (len <= gapMax) {
                    int left = out[row + (first + i - 1 + panoW) % panoW];
                    int right = out[row + (first + i + len) % panoW];
                    if (left != 0 && right != 0) {
                        for (int k = 0; k < len; k++) {
                            out[row + (first + i + k) % panoW] = blend(left, right, (k + 1f) / (len + 1f));
                        }
                    }
                }
                i += len;
            }
        }

        if (sharpen > 0.01f) sharpenLuma(out, panoW, panoH, sharpen);
        return out;
    }

    private static int blend(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return 0xFF000000 | ((int) (r1 + (r2 - r1) * t) << 16) | ((int) (g1 + (g2 - g1) * t) << 8) | (int) (b1 + (b2 - b1) * t);
    }

    /**
     * Unsharp-Mask nur auf der Helligkeit: Y' = Y + amount * (Y - blur3x3(Y)); die Differenz wird auf alle
     * Kanaele addiert (Farbton bleibt erhalten). Pixel am Rand zu ungefuellten (schwarzen) Bereichen bleiben
     * unveraendert. Arbeitet zeilenweise mit drei Zeilenpuffern (kein zweites Bild im Speicher).
     */
    static void sharpenLuma(int[] px, int w, int h, float amount) {
        if (h < 3 || w < 3) return;
        int[] yPrev = new int[w], yCur = new int[w], yNext = new int[w];
        int[] rowPrev = new int[w], rowCur = new int[w];      // Originalpixel der Zeilen v-1 und v
        lumaRow(px, 0, w, yCur);
        lumaRow(px, w, w, yNext);
        for (int v = 0; v < h; v++) {
            System.arraycopy(px, v * w, rowCur, 0, w);
            if (v > 0 && v < h - 1) {
                int base = v * w, next = base + w;
                for (int x = 1; x < w - 1; x++) {
                    int c = rowCur[x];
                    if (c == 0) continue;
                    if (rowPrev[x - 1] == 0 || rowPrev[x] == 0 || rowPrev[x + 1] == 0
                            || rowCur[x - 1] == 0 || rowCur[x + 1] == 0
                            || px[next + x - 1] == 0 || px[next + x] == 0 || px[next + x + 1] == 0) continue;
                    int blur = (yPrev[x - 1] + 2 * yPrev[x] + yPrev[x + 1]
                            + 2 * yCur[x - 1] + 4 * yCur[x] + 2 * yCur[x + 1]
                            + yNext[x - 1] + 2 * yNext[x] + yNext[x + 1] + 8) >> 4;
                    int d = Math.round(amount * (yCur[x] - blur));
                    if (d == 0) continue;
                    px[base + x] = 0xFF000000 | (clamp(((c >> 16) & 255) + d) << 16)
                            | (clamp(((c >> 8) & 255) + d) << 8) | clamp((c & 255) + d);
                }
            }
            // Fenster um eine Zeile weiterschieben
            int[] t = yPrev; yPrev = yCur; yCur = yNext; yNext = t;
            if (v + 2 < h) lumaRow(px, (v + 2) * w, w, yNext);
            int[] tr = rowPrev; rowPrev = rowCur; rowCur = tr;
        }
    }

    private static void lumaRow(int[] px, int off, int w, int[] out) {
        for (int x = 0; x < w; x++) {
            int c = px[off + x];
            out[x] = (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
}
