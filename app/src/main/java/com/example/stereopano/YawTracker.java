package com.example.stereopano;

import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Wertet einen Rotationsvektor-Sensor aus und puffert die letzten Messwerte mit Zeitstempel.
 * Liefert (zeitlich interpoliert):
 *  - den Blickwinkel der Kamera (Azimut, im Uhrzeigersinn, fortlaufend ohne Sprung bei 360 Grad)
 *  - die volle Geraete->Welt-Rotationsmatrix (Welt = Ost, Nord, Oben)
 * Der Blickvektor der Kamera ist -Z des Geraetes; so bleibt der Winkel auch bei senkrechtem Telefon stabil.
 */
final class YawTracker implements SensorEventListener {
    private static final int N = 512;
    private final long[] t = new long[N];
    private final double[] y = new double[N];
    private final float[] q = new float[N * 4];    // Quaternion w, x, y, z
    private int n = 0;
    private double lastRaw, unwrapped, azimuth, pitch;
    private final float[] r = new float[9];
    private final float[] qq = new float[4];
    private int li0, li1;

    @Override
    public void onSensorChanged(SensorEvent e) {
        synchronized (this) {
            SensorManager.getRotationMatrixFromVector(r, e.values);
            SensorManager.getQuaternionFromVector(qq, e.values);
            double az = Math.toDegrees(Math.atan2(-r[2], -r[5]));
            double up = Math.max(-1, Math.min(1, -r[8]));
            if (n == 0) {
                unwrapped = az;
            } else {
                double d = az - lastRaw;
                while (d > 180) d -= 360;
                while (d < -180) d += 360;
                unwrapped += d;
                // gleiche Hemisphaere wie der Vorgaenger (fuer die Interpolation)
                int p = ((n - 1) % N) * 4;
                float dot = q[p] * qq[0] + q[p + 1] * qq[1] + q[p + 2] * qq[2] + q[p + 3] * qq[3];
                if (dot < 0) for (int i = 0; i < 4; i++) qq[i] = -qq[i];
            }
            lastRaw = az;
            azimuth = (az + 360) % 360;
            pitch = Math.toDegrees(Math.asin(up));
            int k = n % N;
            t[k] = e.timestamp;
            y[k] = unwrapped;
            System.arraycopy(qq, 0, q, k * 4, 4);
            n++;
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }

    synchronized boolean hasData() { return n > 0; }
    synchronized double latest() { return n == 0 ? Double.NaN : y[(n - 1) % N]; }
    synchronized double azimuth() { return n == 0 ? Double.NaN : azimuth; }
    synchronized double pitch() { return n == 0 ? 0 : pitch; }

    /** Sucht das Intervall um ts; Ergebnis in li0/li1, Rueckgabe = Anteil 0..1. */
    private double locate(long ts) {
        int newest = n - 1, oldest = Math.max(0, n - N);
        if (ts >= t[newest % N]) { li0 = li1 = newest; return 0; }
        if (ts <= t[oldest % N]) { li0 = li1 = oldest; return 0; }
        for (int i = newest; i > oldest; i--) {
            long t1 = t[i % N], t0 = t[(i - 1) % N];
            if (ts >= t0) {
                li0 = i - 1; li1 = i;
                return t1 == t0 ? 0 : (double) (ts - t0) / (t1 - t0);
            }
        }
        li0 = li1 = oldest;
        return 0;
    }

    /** Interpolierter Winkel zum Zeitpunkt ts (Nanosekunden, Zeitbasis elapsedRealtime). Long.MAX_VALUE = neuester. */
    synchronized double yawAt(long ts) {
        if (n == 0) return Double.NaN;
        double f = locate(ts);
        double a = y[li0 % N], b = y[li1 % N];
        return a + f * (b - a);
    }

    /** Interpolierte Rotationsmatrix (3x3 zeilenweise, Geraet -> Welt) oder null. */
    synchronized float[] rotationAt(long ts) {
        if (n == 0) return null;
        double f = locate(ts);
        int a = (li0 % N) * 4, b = (li1 % N) * 4;
        double w = q[a] + f * (q[b] - q[a]);
        double x = q[a + 1] + f * (q[b + 1] - q[a + 1]);
        double yy = q[a + 2] + f * (q[b + 2] - q[a + 2]);
        double z = q[a + 3] + f * (q[b + 3] - q[a + 3]);
        double s = Math.sqrt(w * w + x * x + yy * yy + z * z);
        if (s == 0) return null;
        w /= s; x /= s; yy /= s; z /= s;
        return new float[]{
                (float) (1 - 2 * (yy * yy + z * z)), (float) (2 * (x * yy - z * w)), (float) (2 * (x * z + yy * w)),
                (float) (2 * (x * yy + z * w)), (float) (1 - 2 * (x * x + z * z)), (float) (2 * (yy * z - x * w)),
                (float) (2 * (x * z - yy * w)), (float) (2 * (yy * z + x * w)), (float) (1 - 2 * (x * x + yy * yy))};
    }
}
