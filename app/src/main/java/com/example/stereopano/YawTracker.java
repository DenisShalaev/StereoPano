package com.example.stereopano;

import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Wertet einen Rotationsvektor-Sensor (Gyroskop + Beschleunigung [+ Kompass]) aus.
 * Liefert den Blickwinkel der Kamera (Azimut, im Uhrzeigersinn, fortlaufend ohne Sprung bei 360 Grad)
 * und speichert die letzten Werte mit Zeitstempel, damit ein Kamerabild exakt zugeordnet werden kann.
 *
 * Der Blickvektor der Kamera ist -Z des Geraetes. Er wird (anders als SensorManager.getOrientation)
 * auch bei senkrecht gehaltenem Telefon stabil berechnet.
 */
final class YawTracker implements SensorEventListener {
    private static final int N = 512;
    private final long[] t = new long[N];
    private final double[] y = new double[N];
    private int n = 0;
    private double lastRaw, unwrapped, azimuth, pitch;
    private final float[] r = new float[9];

    @Override
    public void onSensorChanged(SensorEvent e) {
        synchronized (this) {
            SensorManager.getRotationMatrixFromVector(r, e.values);
            // Blickrichtung in Welt-Koordinaten (Ost, Nord, Oben) = -(r[2], r[5], r[8])
            double az = Math.toDegrees(Math.atan2(-r[2], -r[5]));
            double up = Math.max(-1, Math.min(1, -r[8]));
            if (n == 0) {
                unwrapped = az;
            } else {
                double d = az - lastRaw;
                while (d > 180) d -= 360;
                while (d < -180) d += 360;
                unwrapped += d;
            }
            lastRaw = az;
            azimuth = (az + 360) % 360;
            pitch = Math.toDegrees(Math.asin(up));
            t[n % N] = e.timestamp;
            y[n % N] = unwrapped;
            n++;
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }

    synchronized boolean hasData() { return n > 0; }
    synchronized double latest() { return n == 0 ? Double.NaN : y[(n - 1) % N]; }
    synchronized double azimuth() { return n == 0 ? Double.NaN : azimuth; }
    synchronized double pitch() { return n == 0 ? 0 : pitch; }

    /** Interpolierter Winkel zum Zeitpunkt ts (Nanosekunden, Zeitbasis elapsedRealtime). */
    synchronized double yawAt(long ts) {
        if (n == 0) return Double.NaN;
        int newest = n - 1, oldest = Math.max(0, n - N);
        if (ts >= t[newest % N]) return y[newest % N];
        if (ts <= t[oldest % N]) return y[oldest % N];
        for (int i = newest; i > oldest; i--) {
            long t1 = t[i % N], t0 = t[(i - 1) % N];
            if (ts >= t0) {
                double f = t1 == t0 ? 0 : (double) (ts - t0) / (t1 - t0);
                return y[(i - 1) % N] + f * (y[i % N] - y[(i - 1) % N]);
            }
        }
        return y[oldest % N];
    }
}
