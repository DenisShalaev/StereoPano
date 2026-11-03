package com.example.stereopano;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Leitpunkte (Reperpunkte) wie in der Cardboard Camera, ueber dem Kamerabild:
 *  - Mittelring: dorthin zeigt die Kamera.
 *  - Horizontlinie: neigt sich mit dem Kippen des Telefons, verschiebt sich beim Nicken
 *    (gruen = waagerecht und aufrecht).
 *  - Punktreihe am Horizont alle 15 Grad, fest im Raum verankert (Start = 0 Grad). Sie wandern beim Drehen durchs Bild.
 *  - Tempo-Punkt (nur waehrend der Aufnahme): bewegt sich mit Zielgeschwindigkeit um den Horizont.
 *    Ring auf den Punkt halten! Ist er ausserhalb des Bildes, zeigt ein Pfeil am Rand die Richtung.
 * Das Telefon steht im Hochformat, daher entspricht die Hochformat-Projektion exakt der Kamera.
 */
final class GuideView extends View {

    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pfeil = new Path();
    private final float d;

    private float[] rot;
    private double anchor, psi, ziel = Double.NaN, tempo;
    private boolean aufnahme;
    private double focalRatio = 1;     // Brennweite in Pixeln / Breite des aufrechten Kamerabildes

    GuideView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        dot.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        text.setTextSize(11 * d);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(3 * d, 0, 0, Color.BLACK);
        fill.setStyle(Paint.Style.FILL);
    }

    void setFocalRatio(double r) { focalRatio = r; }

    /**
     * @param rot      Geraet->Welt (3x3 zeilenweise) oder null
     * @param anchor   Welt-Azimut (Grad), der als 0 Grad gilt (Startrichtung)
     * @param psi      aktuelle Blickrichtung relativ zu anchor (Grad)
     * @param aufnahme laeuft die Aufnahme?
     * @param ziel     Soll-Winkel des Tempo-Punktes relativ zu anchor (NaN = keiner)
     * @param tempo    aktuelle Drehgeschwindigkeit in Grad/s (mit Vorzeichen)
     */
    void setzen(float[] rot, double anchor, double psi, boolean aufnahme, double ziel, double tempo) {
        this.rot = rot;
        this.anchor = anchor;
        this.psi = psi;
        this.aufnahme = aufnahme;
        this.ziel = ziel;
        this.tempo = tempo;
        invalidate();
    }

    /** Projiziert (Welt-Azimut, Elevation) auf den Bildschirm; null wenn hinter der Kamera. */
    private float[] project(double azDeg, double elDeg) {
        float[] R = rot;
        double A = Math.toRadians(azDeg), E = Math.toRadians(elDeg);
        double dE = Math.sin(A) * Math.cos(E), dN = Math.cos(A) * Math.cos(E), dU = Math.sin(E);
        double cx = R[0] * dE + R[3] * dN + R[6] * dU;
        double cy = R[1] * dE + R[4] * dN + R[7] * dU;
        double cz = R[2] * dE + R[5] * dN + R[8] * dU;
        if (cz > -1e-3) return null;
        double f = focalRatio * getWidth();
        return new float[]{(float) (getWidth() / 2.0 + f * cx / -cz), (float) (getHeight() / 2.0 - f * cy / -cz)};
    }

    @Override
    protected void onDraw(Canvas c) {
        if (rot == null) return;
        float w = getWidth(), h = getHeight();
        double cam = anchor + psi;                        // Blickrichtung der Kamera (Welt-Azimut)

        // Ausrichtung: Neigung und Kippen aus der Matrix (Blick -Z, rechts +X des Geraetes)
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -rot[8]))));
        double roll = Math.toDegrees(Math.atan2(rot[6], rot[7]));          // Kippen um die Blickachse
        boolean gerade = Math.abs(pitch) < 3 && Math.abs(roll) < 3;
        int ok = gerade ? 0xFF66FF66 : 0xFFFFC107;

        // Horizontlinie
        float[] l1 = project(cam - 40, 0), l2 = project(cam + 40, 0);
        if (l1 != null && l2 != null) {
            line.setColor(ok);
            c.drawLine(l1[0], l1[1], l2[0], l2[1], line);
        }

        // Punktreihe alle 15 Grad (fest im Raum)
        for (int k = 0; k < 24; k++) {
            double deg = k * 15;
            float[] p = project(anchor + deg, 0);
            if (p == null || p[0] < -20 || p[0] > w + 20 || p[1] < -20 || p[1] > h + 20) continue;
            boolean gross = k % 6 == 0;
            dot.setColor(k == 0 ? 0xFF4FC3F7 : 0xCCFFFFFF);
            c.drawCircle(p[0], p[1], (gross ? 6 : 3.5f) * d, dot);
            if (gross) c.drawText((int) deg + "\u00B0", p[0], p[1] - 12 * d, text);
        }

        // Tempo-Punkt
        int farbe = Color.WHITE;
        if (aufnahme && !Double.isNaN(ziel)) {
            double delta = ziel - psi;
            farbe = Math.abs(delta) < 6 ? 0xFF66FF66 : Math.abs(delta) < 15 ? 0xFFFFC107 : 0xFFFF5252;
            float[] p = project(anchor + ziel, 0);
            if (p != null && p[0] > 16 * d && p[0] < w - 16 * d && p[1] > 0 && p[1] < h) {
                fill.setColor(0xFFFF5252);
                c.drawCircle(p[0], p[1], 10 * d, fill);
                fill.setColor(Color.WHITE);
                c.drawCircle(p[0], p[1], 4 * d, fill);
            } else {
                // Pfeil am Rand: Ziel liegt vor (rechts) oder hinter (links) dir
                float ex = delta > 0 ? w - 28 * d : 28 * d, ey = h / 2f, s = delta > 0 ? 1 : -1;
                pfeil.reset();
                pfeil.moveTo(ex + s * 14 * d, ey);
                pfeil.lineTo(ex - s * 10 * d, ey - 14 * d);
                pfeil.lineTo(ex - s * 10 * d, ey + 14 * d);
                pfeil.close();
                fill.setColor(farbe);
                c.drawPath(pfeil, fill);
                c.drawText(Math.round(Math.abs(delta)) + "\u00B0", ex, ey + 30 * d, text);
            }
        }

        // Mittelring + Fadenkreuz
        ring.setColor(aufnahme ? farbe : ok);
        c.drawCircle(w / 2, h / 2, 26 * d, ring);
        line.setColor(0xAAFFFFFF);
        c.drawLine(w / 2 - 8 * d, h / 2, w / 2 + 8 * d, h / 2, line);
        c.drawLine(w / 2, h / 2 - 8 * d, w / 2, h / 2 + 8 * d, line);
        if (aufnahme) c.drawText(String.format("%.0f\u00B0/s", Math.abs(tempo)), w / 2, h / 2 + 46 * d, text);
    }
}
