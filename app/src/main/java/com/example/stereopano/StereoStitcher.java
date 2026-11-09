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
 *  7. NEU - Kreisschluss: Nur bei VOLLEM Kreis (360 Grad) wird der Sensordrift gemessen (Vergleich der letzten Bilder
 *     mit dem Anfang des Panoramas) und gleichmaessig ueber das ganze Bild verteilt. Dadurch verschwindet die Stufe am
 *     Nahtpunkt. Teilpanoramen werden nicht veraendert.
 *  6. NEU - Bewegte Objekte: Jedes Bild wird (drehungskompensiert) mit einem Bild von vor ca. 0.2 s verglichen.
 *     Zellen, die sich staerker veraendert haben als die Kamerabewegung erklaert, gelten als "bewegt" und
 *     werden nicht ins Panorama geschrieben. Die entstehenden Luecken werden aus einer Hilfsebene gefuellt, die
 *     den Hintergrund aus leicht anderem Blickwinkel/Zeitpunkt (+-FB_DEG) mit halber Aufloesung sammelt.
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

    /**
     * Dreht die Lage um die Hochachse (Welt) um delta Grad im Uhrzeigersinn (Kurs waechst). Damit wird ein bekannter
     * Massstabsfehler des Drehsensors (Sensor zeigt z. B. 4,5 % zu wenig) vor der Verarbeitung herausgerechnet.
     */
    static float[] scaleYaw(float[] R, double deltaDeg) {
        double r = Math.toRadians(deltaDeg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float[] o = R.clone();
        for (int k = 0; k < 3; k++) {
            o[k] = c * R[k] + s * R[3 + k];
            o[3 + k] = -s * R[k] + c * R[3 + k];
        }
        return o;
    }

    static final class Result {
        int[] left, right;
        int panoW, panoH;      // Groesse des vollen 360-Grad-Arrays
        int x0, width;         // verwertbarer Ausschnitt (Spalten)
        int y0 = 0, height;    // verwertbare Zeilen (ohne schwarze Raender oben/unten); height 0 = noch nicht bestimmt
        boolean complete;
        double closureShiftPx;   // angewandter Kreisschluss (0 = keiner)
        String diag = "";        // Kurzprotokoll der Aufnahme (fuer die Fehlersuche, steht in den Tags)
        double elCenterDeg;      // Elevation der Panoramamitte (fuer die Metadaten)
    }

    private static final int W_FULL = 8;    // Gewicht in der Mitte des Dreiecks
    private static final int W_MIN = 1;     // Mindestgewicht, ab dem eine Spalte als abgedeckt gilt
    private static final int W_REF = 5;     // Spalten mit mindestens diesem Gewicht dienen als Referenz (bei 30 fps/0.3 Grad sind es ca. 9)
    private static final int REG_COLS = 6;  // Anzahl Referenzspalten fuer die Ausrichtung
    private static final double REG_BACK_DEG = 3.0;   // so weit hinter der aktuellen Spalte wird verglichen
    private static final int MV_STRIDE = 8;           // Rasterweite der Bewegungserkennung (Bildpixel)
    private static final int MV_LAG = 3;              // Vergleich mit dem Bild von vor n Bildern (ca. 0.1 s)
    private static final double FB_DEG = 4.0;         // Versatz der Hilfsebenen (Grad)

    /** Akkumulator eines Auges. */
    private static final class Eye {
        final double offsetDeg;
        final long start, end;   // Spaltenbereich des vollen Kreises dieses Auges (ungewickelt)
        char[] r, g, b;      // gewichtete Summen (max 255 * 255 = 65025 passt in char)
        byte[] w;            // Gewicht je Pixel (0..255)
        final int[] colW;    // Gewicht je Spalte
        int covered;         // Anzahl Spalten mit colW >= W_MIN
        char[] fr, fg, fb;   // Hilfsebene (halbe Aufloesung) fuer Hintergrund hinter bewegten Objekten
        byte[] fw;
        char[] er, eg, eb;   // Erweiterung: Spalten >= panoW (ueber 360 Grad hinaus), getrennt vom Anfang
        byte[] ew;

        Eye(double offsetDeg, int n, int panoW, boolean fallback, int extN) {
            this.offsetDeg = offsetDeg;
            this.start = (long) Math.floor(offsetDeg / 360.0 * panoW);
            this.end = start + panoW - 1;
            r = new char[n]; g = new char[n]; b = new char[n];
            w = new byte[n];
            colW = new int[panoW];
            er = new char[extN]; eg = new char[extN]; eb = new char[extN];
            ew = new byte[extN];
            if (fallback) {
                int m = n / 4;
                fr = new char[m]; fg = new char[m]; fb = new char[m];
                fw = new byte[m];
            }
        }
    }

    final double elCenterDeg;
    final int frameW, frameH, panoW, panoH;
    private int extW;
    final double fPx, deltaDeg, az0Deg;
    private final double[] sinE, cosE;
    private final Eye eyeL, eyeR;
    private final boolean register;
    private final double moveThresh;       // Helligkeitsschwelle der Bewegungserkennung (0 = aus)
    private boolean closed;
    private Result cached;
    private double maxPsi = -1e9;
    private double lastPsiSeen = Double.NaN;
    private boolean firstStep;
    private final java.util.ArrayList<Double> closureSamples = new java.util.ArrayList<>();
    /** Zum Testen abschaltbar. */
    static boolean closureEnabled = true;
    static boolean loopEnabled = true;
    /** Suchbereich (Grad) fuer den Restfehler der Drehung. Ist der Massstab des Sensors schon korrigiert, bleibt der Fehler klein;
     *  grosse Treffer waeren dann Fehlzuordnungen (aehnliche Tuerkanten) und wuerden die Aufnahme zu frueh beenden. */
    static double loopRange = 45.0;
    /** Groesster Sensorfehler (Grad), dem eine Bild-Messung noch geglaubt wird; groessere Werte gelten als falscher Treffer, es zaehlt der Gyro. */
    static double loopTrust = 45.0;
    static double hintCap = 394.0;      // bis zu diesem Rohwinkel darf die grobe Schaetzung das Ende hinauszoegern               // Kreisschluss-Erkennung durch Bildvergleich mit dem Anfang
    private static final double LOOP_FROM_DEG = 280.0;   // ab hier wird nach dem Anfang des Panoramas gesucht
    private static final double LOOP_MARGIN_DEG = 3.0;   // so weit hinter den Anfang weiterdrehen (Ueberlappung fuer die Naht)
    private static final double CLOSE_FROM_DEG = 357.0;   // ab hier werden die Bilder mit dem Anfang verglichen
    private static final double EXT_DEG = 24.0;             // so weit ueber 360 Grad hinaus wird getrennt gespeichert (Erweiterung)
    private double stepEma = 0.3;      // typischer Winkelabstand zwischen zwei Bildern (Grad)
    private double curWs = 1.0;     // Gewichtsfaktor des aktuellen Bildes
    private double win;                // aktuelle halbe Fensterbreite (mindestens deltaDeg, waechst bei niedriger Bildrate)

    // Arbeitsfelder der Feinausrichtung
    private final int regMax;
    private final int regVStep;
    private final double[] sUx, sUy, sRef;
    private final int regRY, regRX;
    // Bewegungserkennung
    private final int gw, gh;
    private final java.util.ArrayDeque<GridFrame> hist = new java.util.ArrayDeque<>();
    private byte[] mask;   // Maske des aktuellen Bildes (1 = bewegt) oder null

    private static final class GridFrame {
        final short[] h;       // Hochpass der Helligkeit (Rasterzellen), unempfindlich gegen Flackern/Belichtung
        final float[] rot;
        GridFrame(short[] h, float[] rot) { this.h = h; this.rot = rot; }
    }

    /** Vorzeichen der Parallaxen-Richtung (+1: Vorgaengerbild zeigt nahe Objekte weiter rechts). Per Test ermittelt. */
    private static final int PAR_SIGN = +1;
    private static final double PAR_K = 0.8;       // Parallaxen-Faktor: Armlaenge 0.6 m / kleinster Objektabstand 0.5 m
    private static final int MV_MIN_CELLS = 10;    // kleinere zusammenhaengende Flecken sind Rauschen
    private byte[] prevMask;                        // Maske des Vorgaengerbildes (zeitliche Bestaetigung)
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
        this(frameW, frameH, fPx, alphaDeg, deltaDeg, az0Deg, register, 0);
    }

    /** @param moveSens Empfindlichkeit der Erkennung bewegter Objekte 0 (aus) .. 100 */
    StereoStitcher(int frameW, int frameH, double fPx, double alphaDeg, double deltaDeg, double az0Deg,
                   boolean register, int moveSens) {
        this(frameW, frameH, fPx, alphaDeg, deltaDeg, az0Deg, register, moveSens, 0.0);
    }

    /**
     * @param elCenterDeg Elevation (ueber dem Horizont) der Panoramamitte. 0 = Standard (Horizont in der Mitte);
     *                    z. B. 30 fuer hohe Gebaeude: das Fenster ist um 30 Grad nach oben verschoben, bleibt aber
     *                    schwerkraftausgerichtet (Zeilen = echte Elevation). Die Metadaten verschiebt der Aufrufer.
     */
    StereoStitcher(int frameW, int frameH, double fPx, double alphaDeg, double deltaDeg, double az0Deg,
                   boolean register, int moveSens, double elCenterDeg) {
        this.elCenterDeg = elCenterDeg;
        this.frameW = frameW;
        this.frameH = frameH;
        this.fPx = fPx;
        this.deltaDeg = deltaDeg;
        this.win = deltaDeg;
        this.az0Deg = az0Deg;
        this.register = register;
        this.moveThresh = moveSens <= 0 ? 0 : Math.max(6, Math.min(40, 6 + (100 - moveSens) * 0.30));
        this.panoW = (int) Math.round(2 * Math.PI * fPx) & ~1;
        double ppd = panoW / 360.0; // Pixel pro Grad
        double vfov = 2 * Math.toDegrees(Math.atan((frameH / 2.0) / fPx));
        this.panoH = (int) Math.round(vfov * ppd) & ~1;
        this.sinE = new double[panoH];
        this.cosE = new double[panoH];
        for (int v = 0; v < panoH; v++) {
            double el = Math.toRadians(elCenterDeg + (panoH / 2.0 - (v + 0.5)) / ppd);   // Elevation ueber dem Horizont
            sinE[v] = Math.sin(el);
            cosE[v] = Math.cos(el);
        }
        int n = panoW * panoH;
        this.extW = (int) Math.ceil(EXT_DEG * panoW / 360.0) + 2;
        this.eyeL = new Eye(+alphaDeg, n, panoW, moveThresh > 0, extW * panoH);
        this.eyeR = new Eye(-alphaDeg, n, panoW, moveThresh > 0, extW * panoH);
        this.gw = frameW / MV_STRIDE;
        this.gh = frameH / MV_STRIDE;
        this.regRY = Math.max(6, frameH / 160);
        this.regRX = Math.max(3, regRY / 2);
        this.regVStep = Math.max(3, (REG_COLS * panoH) / 1500);   // hoechstens ca. 1500 Vergleichspunkte (Rechenzeit)
        this.regMax = REG_COLS * (panoH / regVStep + 2);
        this.sUx = new double[regMax];
        this.sUy = new double[regMax];
        this.sRef = new double[regMax];
    }

    /** Typischer Winkelabstand zwischen zwei verarbeiteten Bildern in Grad (Mass fuer die tatsaechliche Bildrate). */
    synchronized double stepDeg() { return stepEma; }

    synchronized void close() {
        closed = true;
    }

    /**
     * @param psi Drehwinkel der Kamera-Blickrichtung seit Start (Grad, im Uhrzeigersinn, unbegrenzt)
     * @param rot Geraete->Welt-Rotation (3x3 zeilenweise, Welt = Ost/Nord/Oben) zum Aufnahmezeitpunkt
     */
    synchronized void addFrame(Frame f, double psi, float[] rot) {
        addFrame(f, psi, rot, 1.0);
    }

    /**
     * @param weightScale 1 = voller Beitrag; kleiner = unruhiges Bild (schnelles Nicken/Kippen) zaehlt weniger,
     *                    wird aber NICHT verworfen, damit keine Luecken entstehen
     */
    synchronized void addFrame(Frame f, double psiRaw, float[] rot, double weightScale) {
        curWs = Math.max(0.05, Math.min(1.0, weightScale));
        if (closed || cached != null || rot == null || Double.isNaN(psiRaw)) return;
        // Bei niedriger Bildrate (Dunkelheit, lange Belichtung) wuerden sich die schmalen Streifen nicht mehr
        // beruehren -> Luecken. Daher wird das Fenster so breit gewaehlt, dass aufeinanderfolgende Bilder ueberlappen.
        double step = 0;
        if (!Double.isNaN(lastPsiSeen)) {
            step = Math.abs(psiRaw - lastPsiSeen);
            if (step < 4.0) stepEma = firstStep ? 0.8 * stepEma + 0.2 * step : step;   // erster Messwert direkt uebernehmen
            if (step < 4.0) firstStep = true;
        }
        lastPsiSeen = psiRaw;
        if (psiRaw > maxRaw) maxRaw = psiRaw;
        // Wirklicher Drehwinkel = Sensorwinkel + gelernte Korrektur eps (siehe unten). Ab hier rechnen alle Spalten damit.
        curEps = eps;
        double psi0 = psiRaw + eps;
        win = Math.max(deltaDeg, 0.8 * Math.max(stepEma, step < 4.0 ? step : 0));
        // Kreisschluss erkennen: Das Bild wird mit dem Anfang des Panoramas verglichen. Kommt die Kamera in die Startlage
        // zurueck, ist dort KEINE Parallaxe (gleicher Ort, gleiche Richtung): der Winkelfehler des Sensors laesst sich direkt messen.
        if (loopEnabled && psiRaw >= LOOP_FROM_DEG && psiRaw <= 400.0 && loopSamples.size() < 400) {
            double ed = measureLoop(f, rot);
            if (!Double.isNaN(ed)) loopSamples.add(new double[]{psiRaw, ed});
        }
        // (alte, eng begrenzte Messung - nur noch Reserve, wenn die neue nichts findet)
        if (closureEnabled && psi0 >= CLOSE_FROM_DEG && psi0 <= 372.0 && closureSamples.size() < 40) {
            double m = measureClosure(f, rot);
            if (!Double.isNaN(m)) closureSamples.add(m);
        }
        // Kreis geschlossen: weitere Bilder nicht mehr einmischen (sonst Doppelbilder durch Sensordrift)
        if (psi0 > 360.0 + EXT_DEG + 0.5) return;
        double dx = 0, dy = 0;
        if (register) {
            double[] s = estimateShift(f, psi0, rot);
            regCalls++;
            if (s != null) regOk++;
            if (s == null) {                      // keine Messung moeglich (kaum Struktur): Werte halten bzw. abklingen lassen
                smDy *= 0.92;
                smDx *= 0.92;
            } else {
                if (s[2] > 0.5) {                                           // sicher: weich nachfuehren, Sprung pro Bild begrenzt
                    smDy += clampD(0.5 * (s[1] - smDy), 1.5);
                } else {
                    smDy *= 0.92;
                    smDx *= 0.92;
                }
                // Horizontaler Rest = Fehler des Drehsensors (meist zu wenig Drehung gemeldet). Er wird NICHT als Bildverschiebung
                // angewendet, sondern fortlaufend zum Drehwinkel addiert: Das Panorama bleibt lokal nahtlos und die
                // Winkelskala stimmt, sodass sich der Kreis nach 360 Grad wirklich schliesst. Bild gegen Bild vor wenigen Grad
                // misst nur den kleinen Zuwachs; ueber den Kreis summiert er sich (auch bei 15 Grad Gesamtfehler).
                double dEps = -Math.toDegrees(Math.atan(s[0] / fPx));      // Inhalt weiter rechts als erwartet = Kamera weiter links
                if (epsEnabled) eps += clampD(EPS_GAIN * dEps, EPS_MAX_STEP);
            }
            dy = smDy;
        }
        // Plausibilitaet: Sensorfehler liegen im Bereich weniger Prozent; die Korrektur bleibt in diesem Rahmen
        double lim = 0.15 * Math.max(psiRaw, 30.0) + 2.0;
        eps = clampD(eps, lim);
        curEps = eps;
        if (eps > epsMax) epsMax = eps;
        if (eps < epsMin) epsMin = eps;
        final double psi = psiRaw + eps;
        lastDx = dx;
        lastDy = dy;
        mask = moveThresh > 0 ? motionMask(f, rot) : null;
        // Beide Augen schreiben in getrennte Puffer -> parallel (halbiert die Rechenzeit pro Bild auf Mehrkernprozessoren)
        final double fdx = dx, fdy = dy;
        final boolean fb = mask != null;
        java.util.concurrent.CompletableFuture<Void> r = java.util.concurrent.CompletableFuture.runAsync(
                () -> accumulateEye(eyeR, f, psi, rot, fdx, fdy, fb));
        accumulateEye(eyeL, f, psi, rot, fdx, fdy, fb);
        r.join();
        if (psi > maxPsi) maxPsi = psi;
    }

    // ------------------------------------------------------------------ Einsortieren

    private void accumulateEye(Eye e, Frame f, double psi, float[] rot, double dx, double dy, boolean fb) {
        accumulate(e, f, psi, rot, dx, dy);
        if (fb) {
            accumulateFallback(e, f, psi, rot, dx, dy, +FB_DEG);
            accumulateFallback(e, f, psi, rot, dx, dy, -FB_DEG);
        }
    }

    private void accumulate(Eye e, Frame f, double psi, float[] R, double sx, double sy) {
        double centre = psi + e.offsetDeg;
        long x0 = (long) Math.ceil((centre - win) / 360.0 * panoW - 0.5);
        long x1 = (long) Math.floor((centre + win) / 360.0 * panoW - 0.5);
        double qx = R[6], qy = R[7], qz = R[8];   // Welt-"Oben" im Geraetesystem

        for (long x = x0; x <= x1; x++) {
            double phi = (x + 0.5) / panoW * 360.0;              // Panorama-Azimut relativ zum Start
            double a = phi - centre;                              // -delta .. +delta
            double tri = 1 - Math.abs(a) / win;
            if (tri <= 0) continue;
            int wt = Math.max(1, (int) Math.round(W_FULL * curWs * tri));
            boolean inExt = x > e.end;                            // hinter dem Kreis dieses Auges: getrennt speichern
            if (inExt && x - e.end - 1 >= extW) continue;
            int col = (int) Math.floorMod(x, (long) panoW);

            double A = Math.toRadians(az0Deg + phi - curEps);              // Welt-Azimut des Strahls
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
                if (isMoving(ux - sx, uy - sy)) continue;          // bewegtes Objekt: nicht ins Panorama schreiben
                char[] rr = e.r, gg = e.g, bb = e.b;
                byte[] ww = e.w;
                int idx = v * panoW + col;
                if (inExt) { rr = e.er; gg = e.eg; bb = e.eb; ww = e.ew; idx = v * extW + (int) (x - e.end - 1); }
                int wp = ww[idx] & 0xFF;
                if (wp + wt > 255) continue;                       // Ueberlaufschutz bei sehr langsamer Drehung
                int c = bilinear(f, ux, uy);
                rr[idx] += (char) (wt * ((c >> 16) & 255));
                gg[idx] += (char) (wt * ((c >> 8) & 255));
                bb[idx] += (char) (wt * (c & 255));
                ww[idx] = (byte) (wp + wt);
            }
            if (!inExt) {
                int before = e.colW[col];
                e.colW[col] = before + wt;
                if (before < W_MIN && before + wt >= W_MIN) e.covered++;
            }
        }
    }

    private boolean isMoving(double ux, double uy) {
        if (mask == null) return false;
        int gx = (int) (ux / MV_STRIDE), gy = (int) (uy / MV_STRIDE);
        return gx >= 0 && gy >= 0 && gx < gw && gy < gh && mask[gy * gw + gx] != 0;
    }

    /** Wie accumulate, aber nur Hintergrund (nicht bewegt), halbe Aufloesung, Mittelpunkt um offsetPlus verschoben. */
    private void accumulateFallback(Eye e, Frame f, double psi, float[] R, double sx, double sy, double offsetPlus) {
        double centre = psi + e.offsetDeg + offsetPlus;
        long x0 = (long) Math.ceil((centre - win) / 360.0 * panoW - 0.5);
        long x1 = (long) Math.floor((centre + win) / 360.0 * panoW - 0.5);
        double qx = R[6], qy = R[7], qz = R[8];
        int fW = panoW >> 1;
        for (long x = x0; x <= x1; x++) {
            if ((x & 1L) != 0 || x > e.end) continue;
            double phi = (x + 0.5) / panoW * 360.0;
            double a = phi - centre;
            double tri = 1 - Math.abs(a) / win;
            if (tri <= 0) continue;
            int wt = Math.max(1, (int) Math.round(W_FULL * curWs * tri));
            int col = (int) Math.floorMod(x, (long) panoW);
            double A = Math.toRadians(az0Deg + phi - curEps);
            double sA = Math.sin(A), cA = Math.cos(A);
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];
            for (int v = 0; v < panoH; v += 2) {
                double cx = cosE[v] * px + sinE[v] * qx;
                double cy = cosE[v] * py + sinE[v] * qy;
                double cz = cosE[v] * pz + sinE[v] * qz;
                if (cz > -1e-3) continue;
                double ux = frameW / 2.0 + fPx * cx / -cz + sx;
                double uy = frameH / 2.0 - fPx * cy / -cz + sy;
                if (ux < 0 || uy < 0 || ux > frameW - 1.001 || uy > frameH - 1.001) continue;
                if (isMoving(ux - sx, uy - sy)) continue;
                int idx = (v >> 1) * fW + (col >> 1);
                int wp = e.fw[idx] & 0xFF;
                if (wp + wt > 255) continue;
                int c = bilinear(f, ux, uy);
                e.fr[idx] += (char) (wt * ((c >> 16) & 255));
                e.fg[idx] += (char) (wt * ((c >> 8) & 255));
                e.fb[idx] += (char) (wt * (c & 255));
                e.fw[idx] = (byte) (wp + wt);
            }
        }
    }

    // ------------------------------------------------------------------ Kreisschluss

    /**
     * Vergleicht das aktuelle Bild (Lage kurz vor/nach 360 Grad) mit den zuerst geschriebenen Spalten des linken Auges
     * (Strahlen 0.3..3 Grad hinter der Startstreifenmitte). Dort sieht das Bild dieselben Strahlen aus fast derselben
     * Kameraposition (kaum Parallaxe). Rueckgabe: horizontaler Versatz in Bildpixeln, den die Sensorlage gegenueber dem
     * Anfang hat (positiv = Sensor-Blickwinkel ist zu weit im Uhrzeigersinn). NaN, wenn nichts Sicheres gefunden wird.
     */
    private double measureClosure(Frame f, float[] R) {
        Eye e = eyeL;
        long xa = (long) Math.ceil((e.offsetDeg + 0.3) / 360.0 * panoW - 0.5);
        long xb = (long) Math.floor((e.offsetDeg + 3.0) / 360.0 * panoW - 0.5);
        int rng = (int) Math.round(5.0 * panoW / 360.0);              // Suchbereich +-5 Grad
        if (xb < xa) return Double.NaN;
        int[] cols = new int[REG_COLS];
        long[] xs = new long[REG_COLS];
        int nc = 0;
        long step = Math.max(1, (xb - xa + 1) / REG_COLS);
        for (long x = xa; x <= xb && nc < REG_COLS; x += step) {
            int col = (int) Math.floorMod(x, (long) panoW);
            if (e.colW[col] >= 3) { cols[nc] = col; xs[nc] = x; nc++; }
        }
        if (nc < 3) return Double.NaN;

        double qx = R[6], qy = R[7], qz = R[8];
        double margin = rng + 4;
        int n = 0;
        for (int k = 0; k < nc; k++) {
            double phi = (xs[k] + 0.5) / panoW * 360.0;
            double A = Math.toRadians(az0Deg + phi - curEps);
            double sA = Math.sin(A), cA = Math.cos(A);
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];
            for (int v = 3; v < panoH - 3 && n < regMax; v += 3) {
                int idx = v * panoW + cols[k];
                int wp = e.w[idx] & 0xFF;
                if (wp < 2) continue;
                double cx = cosE[v] * px + sinE[v] * qx;
                double cy = cosE[v] * py + sinE[v] * qy;
                double cz = cosE[v] * pz + sinE[v] * qz;
                if (cz > -1e-3) continue;
                double ux = frameW / 2.0 + fPx * cx / -cz;
                double uy = frameH / 2.0 - fPx * cy / -cz;
                if (ux < margin || uy < 8 || ux > frameW - 1 - margin || uy > frameH - 9) continue;
                sUx[n] = ux;
                sUy[n] = uy;
                sRef[n] = (77.0 * e.r[idx] + 150.0 * e.g[idx] + 29.0 * e.b[idx]) / (256.0 * wp);
                n++;
            }
        }
        if (n < 300) { ldbg[1]++; return Double.NaN; }
        double m = 0, m2 = 0;
        for (int i = 0; i < n; i++) { m += sRef[i]; m2 += sRef[i] * sRef[i]; }
        m /= n;
        if (m2 / n - m * m < 12) { ldbg[2]++; return Double.NaN; }    // zu wenig Struktur

        double cost0 = cost(f, n, 0, 0);
        double bestS = 0, bestC = cost0;
        for (int sx = -rng; sx <= rng; sx += 2) {                    // grob
            double c = cost(f, n, sx, 0);
            if (c < bestC) { bestC = c; bestS = sx; }
        }
        double[] c3 = new double[3];                                  // fein (+-1 px) + Teilpixel
        for (int k = -1; k <= 1; k++) c3[k + 1] = cost(f, n, bestS + k, 0);
        double sub = subpixel(c3, 1, bestS);
        if (Math.abs(bestS) >= rng - 2) return Double.NaN;            // am Rand des Suchbereichs -> unsicher
        double costFine = cost(f, n, sub, 0);
        if (costFine > 0.85 * cost0 && Math.abs(sub) > 2) return Double.NaN;   // keine klare Verbesserung
        return sub;
    }

    // ------------------------------------------------------------------ Kreisschluss-Erkennung

    /**
     * Sucht die ersten Spalten des Panoramas (Anfang) im aktuellen Bild, in einem weiten Bereich (+-45 Grad). Rueckgabe:
     * Fehler des Sensorwinkels in Grad (wirklicher Winkel = Sensorwinkel + Rueckgabe; positiv = Sensor hinkt nach) oder NaN.
     */
    /** Zaehler der Ablehnungsgruende von measureLoop (Diagnose): 0 zuwenig Spalten, 1 zuwenig Punkte, 2 flach, 3 NCC klein, 4 mehrdeutig, 5 Bereich, 6 ok, 7 Aufrufe */
    private final int[] ldbg = new int[8];

    private double measureLoop(Frame f, float[] R) {
        ldbg[7]++;
        double r = measureLoop2(f, R);
        if (!Double.isNaN(r)) ldbg[6]++;
        return r;
    }

    private double measureLoop2(Frame f, float[] R) {
        Eye e = eyeL;
        final int NC = 10;
        long xa = (long) Math.ceil((e.offsetDeg + 0.3) / 360.0 * panoW - 0.5);
        long xb = (long) Math.floor((e.offsetDeg + 12.0) / 360.0 * panoW - 0.5);
        if (xb < xa) return Double.NaN;
        int[] cols = new int[NC];
        long[] xs = new long[NC];
        int nc = 0;
        long step = Math.max(1, (xb - xa + 1) / NC);
        for (long x = xa; x <= xb && nc < NC; x += step) {
            int col = (int) Math.floorMod(x, (long) panoW);
            if (e.colW[col] >= W_REF) { cols[nc] = col; xs[nc] = x; nc++; }
        }
        if (nc < 4) { ldbg[0]++; return Double.NaN; }
        int vs = Math.max(3, (NC * panoH) / 1200);
        int cap = NC * (panoH / vs + 2);
        double[] lx = new double[cap], ly = new double[cap], lr = new double[cap];
        double qx = R[6], qy = R[7], qz = R[8];
        int n = 0;
        double ppd = panoW / 360.0;
        int rng = (int) Math.round(loopRange * ppd);
        for (int k = 0; k < nc; k++) {
            double phi = (xs[k] + 0.5) / panoW * 360.0;
            double A = Math.toRadians(az0Deg + phi - curEps);
            double sA = Math.sin(A), cA = Math.cos(A);
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];
            for (int v = 3; v < panoH - 3 && n < cap; v += vs) {
                int idx = v * panoW + cols[k];
                int wp = e.w[idx] & 0xFF;
                if (wp < 3) continue;
                double cx = cosE[v] * px + sinE[v] * qx;
                double cy = cosE[v] * py + sinE[v] * qy;
                double cz = cosE[v] * pz + sinE[v] * qz;
                if (cz > -1e-3) continue;
                double ux = frameW / 2.0 + fPx * cx / -cz;
                double uy = frameH / 2.0 - fPx * cy / -cz;
                if (uy < 8 || uy > frameH - 9 || ux < -rng - 8 || ux > frameW + rng + 8) continue;
                lx[n] = ux; ly[n] = uy;
                lr[n] = (77.0 * e.r[idx] + 150.0 * e.g[idx] + 29.0 * e.b[idx]) / (256.0 * wp);
                n++;
            }
        }
        if (n < 300) return Double.NaN;
        double m = 0, m2 = 0;
        for (int i = 0; i < n; i++) { m += lr[i]; m2 += lr[i] * lr[i]; }
        m /= n;
        if (m2 / n - m * m < 36) return Double.NaN;                   // zu wenig Struktur

        int nS = (2 * rng) / 4 + 1;
        double[] ncc = new double[nS];
        double best = -2; int bi = -1;
        for (int i = 0; i < nS; i++) {
            ncc[i] = nccAt(f, lx, ly, lr, n, -rng + 4 * i);
            if (ncc[i] > best) { best = ncc[i]; bi = i; }
        }
        if (bi < 0 || best < 0.4) { ldbg[3]++; return Double.NaN; }
        int bestS = -rng + 4 * bi;
        double bv = best;
        for (int s = bestS - 3; s <= bestS + 3; s++) {
            double c = nccAt(f, lx, ly, lr, n, s);
            if (c > bv) { bv = c; bestS = s; }
        }
        double second = -2;                                           // zweitbester, deutlich entfernter Treffer
        for (int i = 1; i < nS - 1; i++) {                           // nur echte lokale Maxima zaehlen (glatter Abfall neben dem Treffer ist kein Zweiter)
            if (Math.abs((-rng + 4 * i) - bestS) < 24) continue;
            if (ncc[i] >= ncc[i - 1] && ncc[i] >= ncc[i + 1] && ncc[i] > second) second = ncc[i];
        }
        if (bv - second < 0.08) { ldbg[4]++; return Double.NaN; }     // nicht eindeutig (sich wiederholende Struktur)
        double cm = nccAt(f, lx, ly, lr, n, bestS - 1), cp = nccAt(f, lx, ly, lr, n, bestS + 1);
        double den = cm - 2 * bv + cp, sub = bestS;
        if (den < -1e-9) sub += Math.max(-0.5, Math.min(0.5, 0.5 * (cm - cp) / den));
        // Pixelverschiebung -> Winkel (an der mittleren Bildposition der Stichproben)
        double ux0 = 0; int cnt = 0;
        for (int i = 0; i < n; i += 5) { ux0 += lx[i]; cnt++; }
        ux0 /= Math.max(1, cnt);
        double a0 = Math.atan((ux0 - frameW / 2.0) / fPx), a1 = Math.atan((ux0 + sub - frameW / 2.0) / fPx);
        double err = -Math.toDegrees(a1 - a0);
        if (Math.abs(err) > loopRange + 1) { ldbg[5]++; return Double.NaN; }
        return err;
    }

    /** Normierte Kreuzkorrelation der Referenz mit dem um s Pixel verschobenen Bild (-2 = zu wenig Ueberlappung). */
    private double nccAt(Frame f, double[] lx, double[] ly, double[] lr, int n, int s) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            double ux = lx[i] + s;
            if (ux < 4 || ux > frameW - 5) continue;
            double a = lumaBilinear(f, ux, ly[i]), b = lr[i];
            sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
            cnt++;
        }
        if (cnt < Math.max(150, 0.3 * n)) return -2;
        double va = saa - sa * sa / cnt, vb = sbb - sb * sb / cnt;
        if (va < 1e-6 || vb < 1e-6) return -2;
        return (sab - sa * sb / cnt) / Math.sqrt(va * vb);
    }

    /** Sensorfehler aus den Messungen nahe der Startlage (dort stoert keine Parallaxe); NaN = noch nicht sicher. */
    private double loopError() {
        java.util.ArrayList<Double> near = new java.util.ArrayList<>();
        for (int i = loopSamples.size() - 1; i >= 0 && near.size() < 6; i--) {
            double[] q = loopSamples.get(i);
            if (Math.abs(q[0] + q[1] - 360.0) <= 6.0) near.add(q[1]);
        }
        if (near.size() < 3) return Double.NaN;
        double mn = 1e9, mx = -1e9;
        for (double v : near) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        if (mx - mn > 4.0) return Double.NaN;            // Messungen widersprechen sich (Parallaxe/falscher Treffer): nicht verwenden
        double med = median(near);
        if (Math.abs(med) > loopTrust) { ldbg[5]++; return Double.NaN; }   // weicht zu stark vom Gyro ab: wahrscheinlich falscher Treffer
        return med;
    }

    /** Grobe Schaetzung aus den allerletzten Messungen (auch weit vom Anfang, evtl. mit etwas Parallaxe); NaN = keine. */
    private double recentHint() {
        java.util.ArrayList<Double> r = new java.util.ArrayList<>();
        for (int i = loopSamples.size() - 1; i >= 0 && r.size() < 4; i--) {
            double[] q = loopSamples.get(i);
            if (q[0] >= maxRaw - 6.0) r.add(q[1]);
        }
        if (r.size() < 2) return Double.NaN;
        double m = median(r);
        return Math.abs(m) > loopTrust + 2.0 ? Double.NaN : m;
    }

    private boolean loopReady() { return loopEnabled && !Double.isNaN(loopError()); }

    /** Spalten (ab Augen-Start), die fuer den geschlossenen Kreis gebraucht werden. */
    private int needCols(double err) {
        double L = 360.0 - err;
        return (int) Math.max(1, Math.min(panoW, Math.ceil(L * panoW / 360.0)));
    }

    /** Zulaessig fehlende Spalten (wie die Luecken, die result() ueberbrueckt): meist die ersten Spalten, bevor die Drehung begann. */
    private int gapTol() { return Math.max(4, (int) (panoW * 0.004)); }

    private boolean coveredUpTo(Eye e, int need) {
        int miss = 0, tol = gapTol();
        for (int i = 0; i < need; i++)
            if (e.colW[(int) Math.floorMod(e.start + i, (long) panoW)] < W_MIN && ++miss > tol) return false;
        return true;
    }

    /** Erweiterung (Spalten ueber 360 Grad hinaus) in RGB umrechnen. */
    private int[] finishExt(Eye e) {
        int[] out = new int[extW * panoH];
        for (int i = 0; i < out.length; i++) {
            int wp = e.ew[i] & 0xFF;
            if (wp == 0) continue;
            int h = wp >> 1;
            out[i] = 0xFF000000 | (((e.er[i] + h) / wp) << 16) | (((e.eg[i] + h) / wp) << 8) | ((e.eb[i] + h) / wp);
        }
        return out;
    }

    /**
     * Kreisschluss: Der Sensor-Drift waechst ungefaehr linear mit dem Winkel. Ein Panoramapunkt in Spalte t liegt im
     * aufgenommenen Bild bei x = t * W / (W - d) (d = Drift am Ende, in Spalten; positiv = Sensor eilt voraus).
     * Dadurch schliesst sich der Kreis genau bei der Spaltenzahl W:
     *  - d < 0 (Sensor hinkt nach): ueberzaehliges Stueck am Ende wird nicht verwendet (x < W).
     *  - d > 0 (Sensor eilt voraus): fehlendes Stueck stammt aus der getrennten Erweiterung (x >= W).
     */
    static void closeLoop(int[] px, int[] ext, int extW, int w, int h, double d, int startCol) {
        closeLoop(px, ext, extW, w, h, d, startCol, null);
    }

    /** Pixel an der (gebrochenen) Position label des Kreises (label >= w: Erweiterung); 0 = keine Daten. */
    private static int sampleLabel(int[] px, int[] ext, int extW, int w, int startCol, int v, double label) {
        if (label < 0) return 0;
        int i0 = (int) label;
        double fr = label - i0;
        int a = rawAt(px, ext, extW, w, startCol, v, i0);
        int b = rawAt(px, ext, extW, w, startCol, v, i0 + 1);
        if (a == 0 || b == 0) return a != 0 ? a : b;
        return 0xFF000000
                | ((int) (((a >> 16) & 255) * (1 - fr) + ((b >> 16) & 255) * fr + 0.5) << 16)
                | ((int) (((a >> 8) & 255) * (1 - fr) + ((b >> 8) & 255) * fr + 0.5) << 8)
                | (int) ((a & 255) * (1 - fr) + (b & 255) * fr + 0.5);
    }

    private static int rawAt(int[] px, int[] ext, int extW, int w, int startCol, int v, int i) {
        if (i < w) return px[v * w + Math.floorMod(startCol + i, w)];
        return i - w < extW ? ext[v * extW + i - w] : 0;
    }

    /**
     * Wie viele Spalten (ab Kreisanfang) liegen auch am Ende des Kreises vor (Erweiterung)? Dort sieht man dieselben
     * Strahlen zweimal: am Anfang und am Ende der Aufnahme.
     */
    static int seamLength(int[] ext, int extW, int w, int h, double d, int lastMainLabel) {
        double scale = w / (w - d);
        int maxJ = -1;
        for (int j = extW - 1; j >= 0 && maxJ < 0; j--) {
            int cnt = 0;
            for (int v = h / 4; v < 3 * h / 4; v += 4) if (ext[v * extW + j] != 0) cnt++;
            if (cnt > (h / 2 / 4) * 0.6) maxJ = j;
        }
        int maxLabel = maxJ >= 0 ? w + maxJ : lastMainLabel;     // ohne Erweiterung: Daten reichen bis zur letzten Spalte des Kreises
        double ppd = w / 360.0;
        double o = Math.min((maxLabel - 1) / scale - w, Math.min(EXT_DEG * ppd - 2, w / 4.0));
        return o < ppd ? 0 : (int) o;
    }

    /**
     * Nahtglaettung ohne Ueberlappungsdaten: Am Kreisende passen Anfang und Ende meist nicht ganz zusammen (Helligkeit,
     * kleiner vertikaler Versatz). Die Abweichung an der Nahtstelle wird gemessen und ueber die letzten ~30 Grad des Kreises
     * allmaehlich herausgerechnet (kaum sichtbar, statt einer harten senkrechten Linie). Vertikaler Versatz wird fuer beide Augen
     * gemeinsam bestimmt (sonst gaebe es vertikale Disparitaet), die Helligkeit je Auge.
     */
    static void wrapFix(int[] pl, int[] pr, int startL, int startR, int w, int h) {
        double dy = measureWrapDy(pl, startL, w, h);
        int sh = (int) Math.round(dy);
        double gl = measureWrapGain(pl, startL, w, h, sh), gr = measureWrapGain(pr, startR, w, h, sh);
        applyWrap(pl, startL, w, h, dy, gl);
        applyWrap(pr, startR, w, h, dy, gr);
    }

    private static final int WK = 10;     // Spalten beiderseits der Naht fuer die Messung

    private static double[] wrapProfile(int[] px, int startCol, int w, int h, boolean ende) {
        double[] p = new double[h];
        int[] n = new int[h];
        for (int v = 0; v < h; v++) {
            double s = 0; int c = 0;
            for (int k = 0; k < WK; k++) {
                int t = ende ? w - 1 - k : k;
                int px0 = px[v * w + Math.floorMod(startCol + t, w)];
                if (px0 == 0) continue;
                s += lum(px0); c++;
            }
            p[v] = c >= WK / 2 ? s / c : Double.NaN;
        }
        return p;
    }

    static double measureWrapGain(int[] px, int startCol, int w, int h, int sh) {
        double[] e = wrapProfile(px, startCol, w, h, true), s = wrapProfile(px, startCol, w, h, false);
        double se = 0, ss = 0; int n = 0;
        for (int v = h / 8; v < h - h / 8; v++) {
            int ve = v + sh;                                       // dieselbe Stelle der Szene im Ende
            if (ve < 0 || ve >= h) continue;
            if (!Double.isNaN(e[ve]) && !Double.isNaN(s[v])) { se += e[ve]; ss += s[v]; n++; }
        }
        if (n < h / 4 || se < 1) return 1;
        double g = ss / se;
        return g < 0.8 ? 0.8 : (g > 1.25 ? 1.25 : g);
    }

    static double measureWrapDy(int[] px, int startCol, int w, int h) {
        double[] e = wrapProfile(px, startCol, w, h, true), s = wrapProfile(px, startCol, w, h, false);
        final int R = Math.max(8, (int) (4.0 * w / 360.0));
        double var = 0; int nv = 0;
        for (int v = 1; v < h; v++) if (!Double.isNaN(s[v]) && !Double.isNaN(s[v - 1])) { double d = s[v] - s[v - 1]; var += d * d; nv++; }
        if (nv < h / 3 || var / nv < 0.5) return 0;
        double[] cost = new double[2 * R + 1];
        double c0 = Double.NaN, best = 1e18; int bi = R;
        for (int dy = -R; dy <= R; dy++) {
            double sum = 0; int n = 0;
            for (int v = R + 1; v < h - R - 1; v++) {
                double a0 = s[v], a1 = s[v - 1], b0 = e[v + dy], b1 = e[v + dy - 1];
                if (Double.isNaN(a0) || Double.isNaN(a1) || Double.isNaN(b0) || Double.isNaN(b1)) continue;
                sum += Math.abs((a0 - a1) - (b0 - b1));
                n++;
            }
            double c = n < h / 3 ? 1e18 : sum / n;
            cost[dy + R] = c;
            if (dy == 0) c0 = c;
            if (c < best) { best = c; bi = dy + R; }
        }
        if (bi == 0 || bi == 2 * R || best > 0.8 * c0) return 0;           // Rand der Suche / keine klare Verbesserung
        double a = cost[bi - 1], b = cost[bi], c = cost[bi + 1], den = a - 2 * b + c, sub = bi - R;
        if (den > 1e-9) sub += Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / den));
        return sub;
    }

    private static void applyWrap(int[] px, int startCol, int w, int h, double dy, double g) {
        if (Math.abs(dy) < 0.3 && Math.abs(g - 1) < 0.01) return;
        int len = Math.min(w / 4, (int) (30.0 * w / 360.0));
        int[] col = new int[h];
        for (int j = 0; j < len; j++) {
            int t = w - len + j;
            double f = smooth01((j + 1.0) / len);
            int ci = Math.floorMod(startCol + t, w);
            double gain = Math.pow(g, f), sh = dy * f;
            for (int v = 0; v < h; v++) {
                double y = v + sh;
                int y0 = (int) Math.floor(y);
                double fy = y - y0;
                int p0 = y0 >= 0 && y0 < h ? px[y0 * w + ci] : 0, p1 = y0 + 1 >= 0 && y0 + 1 < h ? px[(y0 + 1) * w + ci] : 0;
                int o;
                if (p0 == 0 || p1 == 0) o = px[v * w + ci] == 0 ? 0 : px[v * w + ci];
                else {
                    int r = (int) (((p0 >> 16) & 255) * (1 - fy) + ((p1 >> 16) & 255) * fy);
                    int gg = (int) (((p0 >> 8) & 255) * (1 - fy) + ((p1 >> 8) & 255) * fy);
                    int b = (int) ((p0 & 255) * (1 - fy) + (p1 & 255) * fy);
                    o = 0xFF000000 | (Math.min(255, (int) (r * gain)) << 16) | (Math.min(255, (int) (gg * gain)) << 8) | Math.min(255, (int) (b * gain));
                }
                col[v] = o;
            }
            for (int v = 0; v < h; v++) px[v * w + ci] = col[v];
        }
    }

    private static int lum(int c) { return (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8; }

    private static double smooth01(double x) { x = x < 0 ? 0 : (x > 1 ? 1 : x); return x * x * (3 - 2 * x); }

    /**
     * Kreisschluss (siehe oben) und - wenn seam != null - Nahtglaettung: Am Anfang des Kreises sind dieselben Strahlen
     * zweimal aufgenommen (Anfang und Ende). Weicht die Kameraposition am Ende etwas ab (Parallaxe naher Objekte,
     * Hand wandert), passen beide nicht zusammen: Doppelbilder und Versatz an der Naht. Hier wird die Fortsetzung des
     * Endes (Erweiterung) ueber die Strecke O allmaehlich so verschoben, dass sie bei t = O genau auf den Anfang passt,
     * und dort uebergeblendet. Es gibt keinen Sprung mehr, nur eine leichte Streckung ueber O Spalten.
     * seam = {Zustand (0 = noch zu messen), O, rx, ry, gain}; beide Augen verwenden dieselbe Verschiebung.
     */
    static void closeLoop(int[] px, int[] ext, int extW, int w, int h, double d, int startCol, double[] seam) {
        double scale = w / (w - d);
        final int O = seam == null ? 0 : (int) seam[1];
        final int Rm = O > 0 ? (int) Math.max(8, Math.min(0.6 * O, 5.0 * w / 360.0)) : 0;
        final int Ry = O > 0 ? (int) Math.max(6, 3.0 * w / 360.0) : 0;
        int[] aL = null, bL = null;          // Luma von A (Anfang) und B (Fortsetzung des Endes), -1 = keine Daten
        int bw = O + 2 * Rm;
        int[] aRGB = null, bRGB = null;
        if (O > 0) {
            aRGB = new int[O * h];
            bRGB = new int[bw * h];
            for (int v = 0; v < h; v++) {
                for (int t = 0; t < O; t++) aRGB[v * O + t] = sampleLabel(px, ext, extW, w, startCol, v, t * scale);
                for (int j = 0; j < bw; j++) bRGB[v * bw + j] = sampleLabel(px, ext, extW, w, startCol, v, (w + j - Rm) * scale);
            }
            if (seam[0] == 0) {
                aL = new int[O * h];
                bL = new int[bw * h];
                for (int i = 0; i < aL.length; i++) aL[i] = aRGB[i] == 0 ? -1 : lum(aRGB[i]);
                for (int i = 0; i < bL.length; i++) bL[i] = bRGB[i] == 0 ? -1 : lum(bRGB[i]);
                seam[2] = 0; seam[3] = 0; seam[4] = 1;
                double[] r = alignSeam(aL, bL, O, bw, h, Rm, Ry);
                if (r != null) { seam[2] = r[0]; seam[3] = r[1]; seam[4] = r[2]; }
                seam[0] = 1;
            }
        }

        int[] lin = new int[w];                       // Kreis des Auges, linear ab Startspalte
        for (int v = 0; v < h; v++) {
            int base = v * w, ebase = v * extW;
            for (int u = 0; u < w; u++) lin[u] = px[base + Math.floorMod(startCol + u, w)];
            for (int t = 0; t < w; t++) {
                double src = t * scale;
                int i0 = (int) src;
                double fr = src - i0;
                int a = i0 < w ? lin[i0] : (i0 - w < extW ? ext[ebase + i0 - w] : 0);
                int i1 = i0 + 1;
                int b = i1 < w ? lin[i1] : (i1 - w < extW ? ext[ebase + i1 - w] : 0);
                int out;
                if (a == 0 || b == 0) out = a != 0 ? a : b;
                else out = 0xFF000000
                        | ((int) (((a >> 16) & 255) * (1 - fr) + ((b >> 16) & 255) * fr + 0.5) << 16)
                        | ((int) (((a >> 8) & 255) * (1 - fr) + ((b >> 8) & 255) * fr + 0.5) << 8)
                        | (int) ((a & 255) * (1 - fr) + (b & 255) * fr + 0.5);
                px[base + Math.floorMod(startCol + t, w)] = out;
            }
        }

        if (O > 0 && seam[0] == 1 && (seam[2] != 0 || seam[3] != 0 || Math.abs(seam[4] - 1) > 0.02)) {
            double rx = seam[2], ry = seam[3], g = seam[4];
            for (int v = 0; v < h; v++) {
                for (int t = 0; t < O; t++) {
                    double wgt = smooth01((double) t / O);
                    double bx = t + wgt * rx + Rm, by = v + wgt * ry;
                    int c = bilinearRGB(bRGB, bw, h, bx, by);
                    if (c == 0) continue;                                  // keine Daten: Anfang (A) bleibt stehen
                    double gain = Math.pow(g, wgt);
                    double al = smooth01(((double) t / O - 0.7) / 0.3);    // letztes Drittel: in A uebergehen
                    int a = aRGB[v * O + t];
                    int rr = (int) (((c >> 16) & 255) * gain), gg = (int) (((c >> 8) & 255) * gain), bb = (int) ((c & 255) * gain);
                    if (a != 0 && al > 0) {
                        rr = (int) (rr * (1 - al) + ((a >> 16) & 255) * al);
                        gg = (int) (gg * (1 - al) + ((a >> 8) & 255) * al);
                        bb = (int) (bb * (1 - al) + (a & 255) * al);
                    }
                    px[v * w + Math.floorMod(startCol + t, w)] = 0xFF000000 | (Math.min(255, rr) << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb);
                }
            }
        }
    }

    private static int bilinearRGB(int[] a, int aw, int h, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= aw || y0 + 1 >= h) return 0;
        double fx = x - x0, fy = y - y0;
        int c00 = a[y0 * aw + x0], c10 = a[y0 * aw + x0 + 1], c01 = a[(y0 + 1) * aw + x0], c11 = a[(y0 + 1) * aw + x0 + 1];
        if (c00 == 0 || c10 == 0 || c01 == 0 || c11 == 0) return 0;
        int out = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) {
            double top = ((c00 >> s) & 255) * (1 - fx) + ((c10 >> s) & 255) * fx;
            double bot = ((c01 >> s) & 255) * (1 - fx) + ((c11 >> s) & 255) * fx;
            out |= ((int) (top * (1 - fy) + bot * fy + 0.5)) << s;
        }
        return out;
    }

    /**
     * Sucht die Verschiebung (rx, ry), mit der die Fortsetzung des Endes (B) auf den Anfang (A) passt, sowie einen
     * Helligkeitsfaktor. null, wenn nichts Sicheres gefunden wird (dann bleibt es bei der reinen Ueberblendung ohne Verschiebung).
     */
    private static double[] alignSeam(int[] a, int[] b, int O, int bw, int h, int Rm, int Ry) {
        final int ts = 3, vs = 4;
        double ma = 0, mb = 0;
        int na = 0, nb = 0;
        for (int v = 0; v < h; v += vs) for (int t = 0; t < O; t += ts) {
            int x = a[v * O + t]; if (x >= 0) { ma += x; na++; }
            int y = b[v * bw + t + Rm]; if (y >= 0) { mb += y; nb++; }
        }
        if (na < 200 || nb < 200) return null;
        ma /= na; mb /= nb;
        // Struktur im Anfang? (ohne Struktur ist die Suche wertlos)
        double va = 0;
        for (int v = 0; v < h; v += vs) for (int t = 0; t < O; t += ts) { int x = a[v * O + t]; if (x >= 0) va += (x - ma) * (x - ma); }
        if (va / na < 25) return new double[]{0, 0, clampG(ma / Math.max(1, mb))};

        double c0 = seamCost(a, b, O, bw, h, 0, 0, ma, mb, ts, vs, Rm);
        if (Double.isNaN(c0)) return null;
        double best = c0; int bx = 0, by = 0;
        for (int dy = -Ry; dy <= Ry; dy += 4) for (int dx = -Rm; dx <= Rm; dx += 4) {
            double c = seamCost(a, b, O, bw, h, dx, dy, ma, mb, ts, vs, Rm);
            if (!Double.isNaN(c) && c < best) { best = c; bx = dx; by = dy; }
        }
        int cx = bx, cy = by;
        for (int dy = cy - 3; dy <= cy + 3; dy++) for (int dx = cx - 3; dx <= cx + 3; dx++) {
            if (Math.abs(dx) > Rm || Math.abs(dy) > Ry) continue;
            double c = seamCost(a, b, O, bw, h, dx, dy, ma, mb, ts, vs, Rm);
            if (!Double.isNaN(c) && c < best) { best = c; bx = dx; by = dy; }
        }
        if (best > 0.85 * c0 || Math.abs(bx) >= Rm || Math.abs(by) >= Ry) { bx = 0; by = 0; }   // keine klare Verbesserung / Rand
        // Helligkeitsfaktor bei der gefundenen Verschiebung
        double sb = 0; int nn = 0;
        for (int v = 0; v < h; v += vs) for (int t = 0; t < O; t += ts) {
            int vy = v + by; if (vy < 0 || vy >= h) continue;
            int y = b[vy * bw + t + bx + Rm]; int x = a[v * O + t];
            if (x >= 0 && y >= 0) { sb += y; nn++; }
        }
        double g = nn > 100 ? ma / Math.max(1, sb / nn) : 1;
        return new double[]{bx, by, clampG(g)};
    }

    private static double clampG(double g) { return g < 0.7 ? 0.7 : (g > 1.4 ? 1.4 : g); }

    /** Mittlere absolute Differenz (nach Abzug der Mittelwerte) von A und B(t+dx, v+dy); NaN bei zu wenig Ueberlappung. */
    private static double seamCost(int[] a, int[] b, int O, int bw, int h, int dx, int dy, double ma, double mb, int ts, int vs, int Rm) {
        double s = 0; int n = 0, tot = 0;
        for (int v = 0; v < h; v += vs) {
            int vy = v + dy;
            for (int t = 0; t < O; t += ts) {
                tot++;
                if (vy < 0 || vy >= h) continue;
                int x = a[v * O + t], y = b[vy * bw + t + dx + Rm];
                if (x < 0 || y < 0) continue;
                s += Math.abs((x - ma) - (y - mb));
                n++;
            }
        }
        return n < 0.5 * tot || n < 200 ? Double.NaN : s / n;
    }

    // ------------------------------------------------------------------ Bewegte Objekte

    /**
     * Vergleicht das aktuelle Bild (auf grobem Raster) mit dem Bild von vor MV_LAG Bildern, nachdem dieses mit den
     * Sensorlagen in die aktuelle Ansicht gedreht wurde. Kleine Ausrichtungsfehler werden durch das Minimum ueber
     * horizontal +-8 Pixel, vertikal +-4 Pixel toleriert. Zellen mit grossem Restunterschied gelten als bewegt. Die Maske wird erweitert und
     * geschlossene Konturen werden gefuellt (Innenflaeche einfarbiger Objekte).
     */
    private byte[] motionMask(Frame f, float[] R) {
        // 1) grobe Helligkeit je Zelle (3x3 Stichproben -> weniger Rauschen), 2) Hochpass (lokales 5x5-Mittel abziehen)
        int[] g = new int[gw * gh];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int ux = gx * MV_STRIDE + MV_STRIDE / 2, uy = gy * MV_STRIDE + MV_STRIDE / 2;
                int sum = 0;
                for (int dy = -3; dy <= 3; dy += 3)
                    for (int dx = -3; dx <= 3; dx += 3) sum += f.luma(ux + dx, uy + dy);
                g[gy * gw + gx] = sum / 9;
            }
        }
        short[] hp = highPass(g);

        byte[] m = null;
        GridFrame prev = hist.size() >= MV_LAG ? hist.peekFirst() : null;
        if (prev != null) {
            float[] P = prev.rot;
            // Winkel zwischen den Blickrichtungen -> erlaubte Parallaxen-Verschiebung nahe gelegener Objekte (in Pixeln)
            double dot = R[2] * P[2] + R[5] * P[5] + R[8] * P[8];
            double ang = Math.acos(Math.max(-1, Math.min(1, dot)));
            double parPx = PAR_K * fPx * ang;
            int parSteps = (int) Math.ceil(parPx / (MV_STRIDE * 0.5));     // in halben Zellen
            m = new byte[gw * gh];
            double cx0 = frameW / 2.0, cy0 = frameH / 2.0;
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    double x = gx * MV_STRIDE + MV_STRIDE / 2 - cx0, y = cy0 - (gy * MV_STRIDE + MV_STRIDE / 2);
                    double z = -fPx;
                    double dE = R[0] * x + R[1] * y + R[2] * z;
                    double dN = R[3] * x + R[4] * y + R[5] * z;
                    double dU = R[6] * x + R[7] * y + R[8] * z;
                    double qx = P[0] * dE + P[3] * dN + P[6] * dU;
                    double qy = P[1] * dE + P[4] * dN + P[7] * dU;
                    double qz = P[2] * dE + P[5] * dN + P[8] * dU;
                    if (qz > -1e-3) continue;
                    double pu = (cx0 + fPx * qx / -qz) / MV_STRIDE - 0.5;
                    double pv = (cy0 - fPx * qy / -qz) / MV_STRIDE - 0.5;
                    int c = hp[gy * gw + gx];
                    double best = 1e9;
                    // Toleranz: +-0.5 Zelle (Sensorfehler) und zusaetzlich einseitig bis zur Parallaxen-Grenze
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int ox = -1; ox <= parSteps; ox++) {
                            double u = pu + 0.5 * ox * PAR_SIGN, v = pv + 0.5 * oy;
                            if (u < 0 || v < 0 || u > gw - 1.001 || v > gh - 1.001) continue;
                            double d = Math.abs(c - gridBilinear(prev.h, u, v));
                            if (d < best) best = d;
                        }
                    }
                    if (best < 1e8 && best > moveThresh) m[gy * gw + gx] = 1;
                }
            }
            removeSmall(m, MV_MIN_CELLS);
            // zeitliche Bestaetigung: nur Zellen, die schon im Vorgaengerbild (leicht erweitert) markiert waren
            if (prevMask != null) {
                byte[] pd = dilate(prevMask, 3);
                for (int i = 0; i < m.length; i++) if (m[i] != 0 && pd[i] == 0) m[i] = 0;
            }
            byte[] raw = m.clone();
            m = dilate(m, 3);
            fillEnclosed(m);
            prevMask = raw;
        } else {
            prevMask = null;
        }
        hist.addLast(new GridFrame(hp, R.clone()));
        while (hist.size() > MV_LAG) hist.removeFirst();
        return m;
    }

    /** Helligkeit minus lokales 5x5-Mittel (trennt echte Strukturaenderung von Flackern, Banding und Belichtung). */
    private short[] highPass(int[] g) {
        int r = 2;
        int[] t = new int[g.length];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int s = 0, n = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(gw - 1, x + r); k++) { s += g[y * gw + k]; n++; }
                t[y * gw + x] = s / n;
            }
        }
        short[] o = new short[g.length];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int s = 0, n = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(gh - 1, y + r); k++) { s += t[k * gw + x]; n++; }
                o[y * gw + x] = (short) (g[y * gw + x] - s / n);
            }
        }
        return o;
    }

    /** Entfernt zusammenhaengende Flecken (4er-Nachbarschaft) mit weniger als minCells Zellen. */
    private void removeSmall(byte[] m, int minCells) {
        boolean[] seen = new boolean[m.length];
        int[] q = new int[m.length];
        for (int i = 0; i < m.length; i++) {
            if (m[i] == 0 || seen[i]) continue;
            int h = 0, t = 0;
            q[t++] = i;
            seen[i] = true;
            while (h < t) {
                int c = q[h++], x = c % gw, y = c / gw;
                if (x > 0 && m[c - 1] != 0 && !seen[c - 1]) { seen[c - 1] = true; q[t++] = c - 1; }
                if (x < gw - 1 && m[c + 1] != 0 && !seen[c + 1]) { seen[c + 1] = true; q[t++] = c + 1; }
                if (y > 0 && m[c - gw] != 0 && !seen[c - gw]) { seen[c - gw] = true; q[t++] = c - gw; }
                if (y < gh - 1 && m[c + gw] != 0 && !seen[c + gw]) { seen[c + gw] = true; q[t++] = c + gw; }
            }
            if (t < minCells) for (int k = 0; k < t; k++) m[q[k]] = 0;
        }
    }

    private double gridBilinear(short[] g, double x, double y) {
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, gw - 1), y1 = Math.min(y0 + 1, gh - 1);
        double dx = x - x0, dy = y - y0;
        double a = g[y0 * gw + x0] * (1 - dx) + g[y0 * gw + x1] * dx;
        double b = g[y1 * gw + x0] * (1 - dx) + g[y1 * gw + x1] * dx;
        return a * (1 - dy) + b * dy;
    }

    /** Quadratische Dilatation (getrennt in x und y). */
    private byte[] dilate(byte[] m, int r) {
        byte[] t = new byte[m.length], o = new byte[m.length];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                if (m[y * gw + x] != 0)
                    for (int k = Math.max(0, x - r); k <= Math.min(gw - 1, x + r); k++) t[y * gw + k] = 1;
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                if (t[y * gw + x] != 0)
                    for (int k = Math.max(0, y - r); k <= Math.min(gh - 1, y + r); k++) o[k * gw + x] = 1;
        return o;
    }

    /** Nicht markierte Zellen, die vom Bildrand aus nicht erreichbar sind (von einer Kontur umschlossen), werden markiert. */
    private void fillEnclosed(byte[] m) {
        boolean[] outside = new boolean[m.length];
        int[] q = new int[m.length];
        int h = 0, t = 0;
        for (int x = 0; x < gw; x++) { int[] ys = {0, gh - 1}; for (int y : ys) { int i = y * gw + x; if (m[i] == 0 && !outside[i]) { outside[i] = true; q[t++] = i; } } }
        for (int y = 0; y < gh; y++) { int[] xs = {0, gw - 1}; for (int x : xs) { int i = y * gw + x; if (m[i] == 0 && !outside[i]) { outside[i] = true; q[t++] = i; } } }
        while (h < t) {
            int i = q[h++], x = i % gw, y = i / gw;
            if (x > 0 && m[i - 1] == 0 && !outside[i - 1]) { outside[i - 1] = true; q[t++] = i - 1; }
            if (x < gw - 1 && m[i + 1] == 0 && !outside[i + 1]) { outside[i + 1] = true; q[t++] = i + 1; }
            if (y > 0 && m[i - gw] == 0 && !outside[i - gw]) { outside[i - gw] = true; q[t++] = i - gw; }
            if (y < gh - 1 && m[i + gw] == 0 && !outside[i + gw]) { outside[i + gw] = true; q[t++] = i + gw; }
        }
        for (int i = 0; i < m.length; i++) if (m[i] == 0 && !outside[i]) m[i] = 1;
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
    private final java.util.ArrayList<double[]> loopSamples = new java.util.ArrayList<>();   // {Sensorwinkel, Fehler in Grad}
    private int regCalls = 0, regOk = 0;
    private double maxRaw = 0, epsMin = 0, epsMax = 0;
    private double smDx = 0, smDy = 0;
    private double eps = 0, curEps = 0;                 // gelernte Korrektur des Sensorwinkels (Grad) bzw. der aktuell benutzte Wert
    static boolean epsEnabled = false;                // Winkelkorrektur nach Bild (zum Testen abschaltbar)
    static double EPS_GAIN = 1.0;          // gemessener Rest ist differenziell (gegen schon korrigierten Inhalt) -> Einheitsverstaerkung, sonst geht Drift verloren
    private static final double EPS_MAX_STEP = 0.4;      // max. Korrektur pro Bild (Grad)

    /** Korrigierter Drehwinkel zu einem Sensorwinkel (fuer Abbruchbedingungen des Aufrufers). */
    synchronized double effPsi(double psiRaw) { return psiRaw + eps; }

    private static double clampD(double v, double m) { return v > m ? m : (v < -m ? -m : v); }

    private double[] estimateShift(Frame f, double psi, float[] R) {
        final double[] zero = null;                         // null = keine sichere Messung
        Eye e = eyeL;
        double centre = psi + e.offsetDeg;
        long xa = (long) Math.floor((centre - REG_BACK_DEG) / 360.0 * panoW - 0.5);
        long xb = (long) Math.floor((centre + win) / 360.0 * panoW - 0.5);

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
            double A = Math.toRadians(az0Deg + phi - curEps);
            double sA = Math.sin(A), cA = Math.cos(A);
            double px = sA * R[0] + cA * R[3];
            double py = sA * R[1] + cA * R[4];
            double pz = sA * R[2] + cA * R[5];
            for (int v = 3; v < panoH - 3 && n < regMax; v += regVStep) {
                int idx = v * panoW + cols[k];
                int wp = e.w[idx] & 0xFF;
                if (wp < 3) continue;
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
        // Vertikale Feinausrichtung nur, wenn wirklich ein Unterschied besteht und die Suche klar besser wird.
        // Der horizontale Rest (Winkelfehler des Sensors) wird dagegen IMMER gemessen: er ist pro Bild winzig
        // (Bruchteil eines Pixels), summiert sich aber ueber den Kreis auf viele Grad.
        boolean unterschied = cost0 >= 4;
        double dy = unterschied ? search1D(f, n, true, regRY, 0, cost0) : 0;
        double dx = search1D(f, n, false, regRX, dy, cost0);
        double costBest = cost(f, n, dx, dy);
        boolean klar = unterschied && costBest <= 0.75 * cost0;
        return new double[]{dx, dy, klar ? 1 : 0};
    }

    /** Sucht das Kostenminimum entlang einer Achse; 0 bei Randtreffer. */
    private double search1D(Frame f, int n, boolean vertical, int r, double other, double c0) {
        int best = 0;
        double bc = vertical ? c0 : cost(f, n, 0, other);
        for (int s = -r; s <= r; s += 2) {
            double c = vertical ? cost(f, n, 0, s) : cost(f, n, s, other);
            if (c < bc) { bc = c; best = s; }
        }
        for (int s = best - 1; s <= best + 1; s += 2) {
            if (s < -r || s > r) continue;
            double c = vertical ? cost(f, n, 0, s) : cost(f, n, s, other);
            if (c < bc) { bc = c; best = s; }
        }
        if (Math.abs(best) >= r) return 0;
        double[] c3 = new double[3];
        for (int i = -1; i <= 1; i++) {
            int s = best + i;
            c3[i + 1] = vertical ? cost(f, n, 0, s) : cost(f, n, s, other);
        }
        return subpixel(c3, 1, best);
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
    /** Wie viele Grad muss noch gedreht werden, bis isComplete() erreicht ist (nur wenn alle Spalten schon abgedeckt sind). */
    synchronized double remainingDeg() {
        double ed = loopEnabled ? loopError() : Double.NaN;
        if (!Double.isNaN(ed)) return Math.max(0, 360.0 + win + LOOP_MARGIN_DEG - (maxRaw + ed));
        if (loopEnabled) {
            double hint = recentHint();
            if (!Double.isNaN(hint)) return Math.max(0, 360.0 + win + LOOP_MARGIN_DEG - (maxRaw + hint));
        }
        return Math.max(0, 360.0 + win + closureNeedDeg() - maxPsi);
    }

    synchronized boolean isComplete() {
        double ed = loopEnabled ? loopError() : Double.NaN;
        if (!Double.isNaN(ed)) {
            // Der Kreis ist geschlossen, sobald der WIRKLICHE Winkel (Sensor + Fehler) den Anfang um die Ueberlappung passiert hat
            int need = needCols(ed);
            if (!coveredUpTo(eyeL, need) || !coveredUpTo(eyeR, need)) return false;
            return maxRaw + ed >= 360.0 + win + LOOP_MARGIN_DEG;
        }
        if (loopEnabled) {                                   // noch nicht gesichert: deutet die grobe Schaetzung auf "Anfang noch nicht erreicht"?
            double hint = recentHint();
            if (!Double.isNaN(hint) && maxRaw + hint < 360.0 + win + LOOP_MARGIN_DEG && maxRaw < hintCap) return false;
        }
        if (!(eyeL.covered >= panoW - gapTol() && eyeR.covered >= panoW - gapTol())) return false;
        return maxPsi >= 360.0 + win + closureNeedDeg();
    }

    /**
     * Eilt der Sensor voraus (positiver Drift), zeigt er 360 Grad, bevor der echte Kreis geschlossen ist. Dann fehlt am
     * Ende noch ein Stueck; es wird weiter aufgenommen (in die getrennte Erweiterung), bis die Drift-Strecke gedeckt ist.
     */
    private double closureNeedDeg() {
        if (!closureEnabled || closureSamples.size() < 3) return 0;
        double med = median(closureSamples);
        if (med <= 1.5) return 0;
        return Math.min(3.0, med / (panoW / 360.0) + 0.4);       // hoechstens 3 Grad zusaetzlich drehen (Rest glaettet die Naht)
    }

    private static double median(java.util.List<Double> l) {
        java.util.List<Double> c = new java.util.ArrayList<>(l);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }

    /** Fortschritt 0..1 (Anteil abgedeckter Spalten des schwaecheren Auges). */
    synchronized double progress() {
        double ed = loopEnabled ? loopError() : Double.NaN;
        if (!Double.isNaN(ed) && ed > 0) {                 // Sensor hinkt nach: der Kreis ist schon bei weniger als 360 Sensorgrad zu
            int need = needCols(ed);
            return Math.min(1.0, (Math.min(eyeL.covered, eyeR.covered) + gapTol()) / (double) need);
        }
        return Math.min(1.0, (Math.min(eyeL.covered, eyeR.covered) + gapTol()) / (double) panoW);
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
        r.elCenterDeg = elCenterDeg;
        r.panoW = panoW;
        r.panoH = panoH;

        // abgedeckte Spalten (beide Augen); kleine Luecken (bis 2 %) werden ueberbrueckt, grosse nicht erfunden
        int gapMax = Math.max(4, (int) (panoW * 0.004));      // nur winzige Luecken (<0.4 %) werden interpoliert
        boolean[] ok = new boolean[panoW];
        int nOk = 0;
        // Kreisschluss erkannt und Sensor hinkt nach: der Kreis ist schon vor Spalte panoW zu; der Rest wird nicht gebraucht
        final double loopEd = loopEnabled ? loopError() : Double.NaN;
        final int needL = Double.isNaN(loopEd) ? panoW : needCols(loopEd);
        for (int x = 0; x < panoW; x++) {
            boolean unusedL = needL < panoW && Math.floorMod(x - eyeL.start, (long) panoW) >= needL;
            boolean unusedR = needL < panoW && Math.floorMod(x - eyeR.start, (long) panoW) >= needL;
            ok[x] = (unusedL || eyeL.colW[x] >= W_MIN) && (unusedR || eyeR.colW[x] >= W_MIN);
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

        int[] extL = finishExt(eyeL), extR = finishExt(eyeR);
        r.left = finish(eyeL, gapMax, 0f);
        eyeL.r = eyeL.g = eyeL.b = null;
        eyeL.w = null;
        eyeL.er = eyeL.eg = eyeL.eb = null;
        eyeL.ew = null;
        eyeL.fr = eyeL.fg = eyeL.fb = null;
        eyeL.fw = null;
        r.right = finish(eyeR, gapMax, 0f);
        eyeR.r = eyeR.g = eyeR.b = null;
        eyeR.w = null;
        eyeR.er = eyeR.eg = eyeR.eb = null;
        eyeR.ew = null;
        eyeR.fr = eyeR.fg = eyeR.fb = null;
        eyeR.fw = null;
        // Kreisschluss nur bei vollem Kreis (Teilpanoramen werden nicht veraendert)
        r.closureShiftPx = 0;
        if (r.complete && closureEnabled) {
            double d = 0;
            if (!Double.isNaN(loopEd)) {
                // gemessener Sensorfehler -> wirkliche Kreislaenge L (in Sensorgrad) -> lineare Streckung auf genau panoW Spalten
                double scaleL = (360.0 - loopEd) / 360.0;
                d = panoW * (1.0 - 1.0 / scaleL);
            } else if (closureSamples.size() >= 4) {
                java.util.List<Double> sv = new java.util.ArrayList<>(closureSamples);
                java.util.Collections.sort(sv);
                double med = sv.get(sv.size() / 2);
                java.util.List<Double> dev = new java.util.ArrayList<>();
                for (double q : sv) dev.add(Math.abs(q - med));
                java.util.Collections.sort(dev);
                double mad = dev.get(dev.size() / 2);
                double maxShift = Math.min(EXT_DEG - 0.5, loopTrust) * panoW / 360.0;       // mehr als ~8 Grad Drift ist unplausibel
                if (Math.abs(med) >= 1.5 && Math.abs(med) <= maxShift && mad <= Math.max(3.0, 0.35 * Math.abs(med))) d = med;
            }
            // Nahtglaettung: gemeinsame Strecke O beider Augen
            int hint = (int) Math.min(panoW - 1, Math.floor(maxRaw * panoW / 360.0));
            int oL = seamLength(extL, extW, panoW, panoH, d, hint), oR = seamLength(extR, extW, panoW, panoH, d, hint);
            double[] seam = {0, Math.min(oL, oR), 0, 0, 1};
            closeLoop(r.left, extL, extW, panoW, panoH, d, (int) eyeL.start, seam);
            closeLoop(r.right, extR, extW, panoW, panoH, d, (int) eyeR.start, seam);
            r.closureShiftPx = d;
            if (seam[1] == 0) wrapFix(r.left, r.right, (int) eyeL.start, (int) eyeR.start, panoW, panoH);
            else if (Math.abs(seam[4] - 1) <= 0.02) {          // Naht ausgerichtet, aber kein Helligkeitsabgleich gefunden: Stufe zwischen Ende und Anfang sanft ausgleichen
                double gl = measureWrapGain(r.left, (int) eyeL.start, panoW, panoH, 0), gr = measureWrapGain(r.right, (int) eyeR.start, panoW, panoH, 0);
                if (Math.abs(gl - 1) > 0.02) applyWrap(r.left, (int) eyeL.start, panoW, panoH, 0, gl);
                if (Math.abs(gr - 1) > 0.02) applyWrap(r.right, (int) eyeR.start, panoW, panoH, 0, gr);
            }
            r.diag = String.format(java.util.Locale.US, "raw=%.1f err=%.1f loopN=%d d=%.1fpx seam=%d,%.0f,%.0f,%.2f reg=%d/%d ppd=%.1f ld=%s",
                    maxRaw, Double.isNaN(loopEd) ? 0.0 : loopEd, loopSamples.size(), d, (int) seam[1], seam[2], seam[3], seam[4], regOk, regCalls, panoW / 360.0, java.util.Arrays.toString(ldbg));
        }
        if (r.diag.isEmpty())
            r.diag = String.format(java.util.Locale.US, "partial raw=%.1f loopN=%d reg=%d/%d ld=%s", maxRaw, loopSamples.size(), regOk, regCalls, java.util.Arrays.toString(ldbg));
        if (sharpen > 0.01f) {
            sharpenLuma(r.left, panoW, panoH, sharpen);
            sharpenLuma(r.right, panoW, panoH, sharpen);
        }
        r.diag += String.format(java.util.Locale.US, " | stop: cov=%d/%d/%d win=%.2f mp=%.1f ed=%s hint=%s cn=%.1f",
                eyeL.covered, eyeR.covered, panoW, win, maxPsi, Double.isNaN(loopEd) ? "nan" : String.format(java.util.Locale.US, "%.2f", loopEd),
                Double.isNaN(recentHint()) ? "nan" : String.format(java.util.Locale.US, "%.1f", recentHint()), closureNeedDeg());
        trimRows(r);
        cached = r;
        return r;
    }

    /**
     * Beschnitt oben und unten: laengster Zeilenbereich, in dem beide Augen (fast) keine unbedeckten Pixel haben.
     * Schwarze, ausgefranste Raender (Neigung, Sichtfeld-Ende) fallen so weg; Miniatur und Betrachter zeigen ein gerades Bild.
     */
    private void trimRows(Result r) {
        r.y0 = 0;
        r.height = panoH;
        if (r.width <= 0 || r.left == null || r.right == null) return;
        int tol = Math.max(1, (int) (r.width * 0.003));
        int bestStart = 0, bestLen = 0, runStart = 0, runLen = 0;
        for (int y = 0; y < panoH; y++) {
            int holes = 0;
            int base = y * panoW + r.x0;
            for (int x = 0; x < r.width && holes <= tol; x++) {
                if ((r.left[base + x] >>> 24) == 0) holes++;
                if ((r.right[base + x] >>> 24) == 0) holes++;
            }
            if (holes <= tol) {
                if (runLen == 0) runStart = y;
                runLen++;
                if (runLen > bestLen) { bestLen = runLen; bestStart = runStart; }
            } else {
                runLen = 0;
            }
        }
        final int margin = 3;                                  // Randzeilen sind oft unsauber (Interpolation, Schaerfen)
        if (bestLen - 2 * margin < panoH / 2) return;          // Bild kaum nutzbar: lieber nichts abschneiden
        r.y0 = bestStart + margin;
        r.height = bestLen - 2 * margin;
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

        // Luecken durch entfernte bewegte Objekte: Hintergrund aus der Hilfsebene (halbe Aufloesung) einsetzen
        if (e.fw != null) {
            int fW = panoW >> 1;
            for (int v = 0; v < panoH; v++) {
                int row = v * panoW, frow = (v >> 1) * fW;
                for (int c = 0; c < panoW; c++) {
                    if (out[row + c] != 0) continue;
                    int fi = frow + (c >> 1);
                    int wp = e.fw[fi] & 0xFF;
                    if (wp == 0) continue;
                    int h = wp >> 1;
                    out[row + c] = 0xFF000000 | (((e.fr[fi] + h) / wp) << 16) | (((e.fg[fi] + h) / wp) << 8) | ((e.fb[fi] + h) / wp);
                }
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
