package com.example.stereopano;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Betrachter fuer .vr.jpg (Cardboard-Camera-Format) mit Gyroskop/Kompass.
 *  - Ziehen mit dem Finger: Ansicht verschieben (zusaetzlich zum Sensor)
 *  - Doppeltippen oder Knopf "VR": Cardboard-Modus (Querformat, linkes/rechtes Auge nebeneinander)
 *  - Kompass an: Panorama wird nach den echten Himmelsrichtungen ausgerichtet (falls beim Aufnehmen gespeichert)
 *  - Kompass aus: Ansicht beginnt dort, wo das Telefon beim Oeffnen / "Zentrieren" hinzeigt
 */
public class ViewerActivity extends Activity implements SensorEventListener {

    private static final int REQ_OEFFNEN = 7;

    private GLSurfaceView gl;
    private PanoRenderer rend;
    private LinearLayout leiste;
    private Button kompassKnopf;

    private SensorManager sm;
    private final float[] r = new float[9], r2 = new float[9];

    private double poseHeading = Double.NaN;   // Kompasskurs der Mitte des vollen Panoramas
    private double azMitte;                    // Azimut (Grad) der Mitte des vorhandenen Ausschnitts
    private boolean kompassModus = true;
    private boolean neuZentrieren = true;
    private boolean vr;

    private MediaPlayer player;
    private File audioDatei;
    private float lx, ly;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        FrameLayout root = new FrameLayout(this);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setPreserveEGLContextOnPause(true);
        rend = new PanoRenderer();
        gl.setRenderer(rend);
        root.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { schalteVr(); return true; }
        });
        gl.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lx = ev.getX(); ly = ev.getY(); break;
                case MotionEvent.ACTION_MOVE:
                    rend.drag(ev.getX() - lx, ev.getY() - ly);
                    lx = ev.getX(); ly = ev.getY();
                    break;
                default: break;
            }
            return true;
        });

        leiste = new LinearLayout(this);
        leiste.setOrientation(LinearLayout.HORIZONTAL);
        leiste.setBackgroundColor(0x66000000);
        leiste.addView(knopf("Oeffnen", v -> oeffnen()));
        leiste.addView(knopf("VR", v -> schalteVr()));
        kompassKnopf = knopf("Kompass: an", v -> schalteKompass());
        leiste.addView(kompassKnopf);
        leiste.addView(knopf("Zentrieren", v -> { rend.resetDrag(); neuZentrieren = true; wendeKursAn(); }));
        root.addView(leiste, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(root);
        Ui.applyInsets(leiste);

        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) lade(in.getData());
        else oeffnen();
    }

    private Button knopf(String text, View.OnClickListener l) {
        Button k = new Button(this);
        k.setText(text);
        k.setTextSize(12);
        k.setOnClickListener(l);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return k;
    }

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onResume() {
        super.onResume();
        gl.onResume();
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);   // Gyro + Beschleunigung + Magnetometer
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        else Toast.makeText(this, "Kein Rotationssensor - nur Touch-Steuerung.", Toast.LENGTH_SHORT).show();
        if (player != null) player.start();
    }

    @Override
    protected void onPause() {
        sm.unregisterListener(this);
        if (player != null && player.isPlaying()) player.pause();
        gl.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        if (audioDatei != null) audioDatei.delete();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Sensor

    @SuppressWarnings("deprecation")
    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(r, e.values);
        int ax, ay;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:  ax = SensorManager.AXIS_Y;       ay = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: ax = SensorManager.AXIS_MINUS_X; ay = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: ax = SensorManager.AXIS_MINUS_Y; ay = SensorManager.AXIS_X;       break;
            default:                   ax = SensorManager.AXIS_X;       ay = SensorManager.AXIS_Y;       break;
        }
        SensorManager.remapCoordinateSystem(r, ax, ay, r2);
        rend.setRotation(r2.clone());

        if (neuZentrieren) {
            neuZentrieren = false;
            wendeKursAn();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** Legt fest, welche Himmelsrichtung dem Beginn des Panoramas (Spalte 0) entspricht. */
    private void wendeKursAn() {
        if (kompassModus && !Double.isNaN(poseHeading)) {
            rend.setHeadingOffset((float) (poseHeading - 180));
        } else {
            // Blickrichtung des Telefons = Kamera schaut aus dem Bildschirm heraus: -Z des Geraets
            double dev = Math.toDegrees(Math.atan2(-r2[2], -r2[5]));
            rend.setHeadingOffset((float) (dev - azMitte));
        }
    }

    private void schalteKompass() {
        if (Double.isNaN(poseHeading)) {
            Toast.makeText(this, "Dieses Bild enthaelt keine Kompassdaten.", Toast.LENGTH_SHORT).show();
            kompassModus = false;
        } else {
            kompassModus = !kompassModus;
        }
        kompassKnopf.setText(kompassModus ? "Kompass: an" : "Kompass: aus");
        neuZentrieren = true;
        wendeKursAn();
    }

    // ------------------------------------------------------------------ VR-Modus

    private void schalteVr() {
        vr = !vr;
        rend.setVr(vr);
        setRequestedOrientation(vr ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                   : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        leiste.setVisibility(vr ? View.GONE : View.VISIBLE);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (vr) c.hide(WindowInsets.Type.systemBars());
            else c.show(WindowInsets.Type.systemBars());
        }
        if (vr) Toast.makeText(this, "Doppeltippen beendet den VR-Modus.", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ Laden

    @SuppressWarnings("deprecation")
    private void oeffnen() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/jpeg");
        startActivityForResult(i, REQ_OEFFNEN);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OEFFNEN && res == RESULT_OK && data != null && data.getData() != null) lade(data.getData());
    }

    private void lade(Uri uri) {
        new Thread(() -> {
            try {
                byte[] daten;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    daten = bo.toByteArray();
                }
                VrJpgReader.Result meta = VrJpgReader.parse(daten);
                Bitmap links = BitmapFactory.decodeByteArray(daten, 0, daten.length);
                if (links == null) throw new IOException("Kein gueltiges JPEG");
                Bitmap rechts = meta.rightJpeg != null
                        ? BitmapFactory.decodeByteArray(meta.rightJpeg, 0, meta.rightJpeg.length) : null;
                final boolean stereo = rechts != null;
                if (!stereo) rechts = links;

                // Geometrie (ohne GPano-Daten: 360 Grad, vertikal zentriert annehmen)
                int fullW = meta.fullW, fullH = meta.fullH, cropW = meta.cropW, cropH = meta.cropH;
                int cropLeft = meta.cropLeft, cropTop = meta.cropTop;
                if (fullW <= 0 || cropW <= 0) {
                    fullW = links.getWidth();
                    fullH = Math.max(fullW / 2, links.getHeight());
                    cropW = links.getWidth();
                    cropH = links.getHeight();
                    cropLeft = 0;
                    cropTop = (fullH - cropH) / 2;
                }
                final double u0 = (double) cropLeft / fullW;
                final double u1 = (double) (cropLeft + cropW) / fullW;
                final double top = 90.0 - (double) cropTop / fullH * 180.0;
                final double bot = 90.0 - (double) (cropTop + cropH) / fullH * 180.0;
                final Bitmap fl = links, fr = rechts;
                final byte[] ton = meta.audio;

                runOnUiThread(() -> {
                    poseHeading = meta.poseHeading;
                    azMitte = (u0 + u1) / 2 * 360.0;
                    rend.setPano(fl, fr, u0, u1, top, bot);
                    rend.resetDrag();
                    kompassModus = !Double.isNaN(poseHeading);
                    kompassKnopf.setText(kompassModus ? "Kompass: an" : "Kompass: aus");
                    neuZentrieren = true;
                    wendeKursAn();
                    starteTon(ton);
                    if (!stereo) Toast.makeText(this, "Kein Stereo-Bild gefunden - zeige 2D-Panorama.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception | OutOfMemoryError ex) {
                runOnUiThread(() -> Toast.makeText(this, "Fehler beim Laden: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ------------------------------------------------------------------ Ton

    private void starteTon(byte[] ton) {
        if (player != null) { player.release(); player = null; }
        if (audioDatei != null) { audioDatei.delete(); audioDatei = null; }
        if (ton == null) return;
        try {
            audioDatei = File.createTempFile("ton", ".m4a", getCacheDir());
            try (FileOutputStream fo = new FileOutputStream(audioDatei)) { fo.write(ton); }
            player = new MediaPlayer();
            player.setDataSource(audioDatei.getAbsolutePath());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
        }
    }
}
