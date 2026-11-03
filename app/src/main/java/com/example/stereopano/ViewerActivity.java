package com.example.stereopano;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.ScaleGestureDetector;
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
 * Betrachter fuer .vr.jpg (Cardboard-Camera-Format).
 *
 * Modi (Knopf "Modus"):
 *  2D        - flache Ansicht des linken Bildes, Vollbild
 *  Cardboard - Querformat, linkes/rechtes Auge nebeneinander (fuer Pappbrille, Telefon im Headset)
 *  SBS Brille- Querformat, Side-by-Side fuer Videobrillen (z. B. Goovis); optional "Halb-SBS" (gestaucht)
 *
 * Steuerung: Gyro an/aus, Kompass an/aus (Panorama nach Himmelsrichtungen, falls im Bild gespeichert),
 * Finger ziehen = verschieben, Pinch = Zoom, Tippen = Leiste ein/aus, Doppeltippen = 2D <-> letzter Stereo-Modus.
 */
public class ViewerActivity extends Activity implements SensorEventListener {

    private static final int REQ_OEFFNEN = 7;
    private static final String[] MODUS_NAMEN = {"2D", "Cardboard", "SBS Brille"};

    private GLSurfaceView gl;
    private PanoRenderer rend;
    private LinearLayout leiste;
    private Button modusKnopf, kompassKnopf, gyroKnopf, halbKnopf, linsenKnopf;

    private SensorManager sm;
    private SharedPreferences prefs;
    private final float[] r = new float[9], r2 = new float[9];

    private double poseHeading = Double.NaN;   // Kompasskurs der Mitte des vollen Panoramas
    private double azMitte;                    // Azimut (Grad) der Mitte des vorhandenen Ausschnitts
    private boolean kompassWunsch, gyroAn, sbsHalb, linsenAn;
    private boolean neuZentrieren = true;
    private int modus = PanoRenderer.MODE_FLAT;
    private int letzterStereoModus = PanoRenderer.MODE_CARDBOARD;
    private boolean sensorAktiv;

    private MediaPlayer player;
    private File audioDatei;
    private float lx, ly;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        prefs = getSharedPreferences("pano", MODE_PRIVATE);
        kompassWunsch = prefs.getBoolean("kompass", true);
        gyroAn = prefs.getBoolean("gyro", true);
        sbsHalb = prefs.getBoolean("halb", false);
        linsenAn = prefs.getBoolean("linsen", true);

        FrameLayout root = new FrameLayout(this);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setPreserveEGLContextOnPause(true);
        rend = new PanoRenderer();
        rend.setSbsHalf(sbsHalb);
        rend.setLens(linsenAn);
        gl.setRenderer(rend);
        root.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                leiste.setVisibility(leiste.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                setzeModus(modus == PanoRenderer.MODE_FLAT ? letzterStereoModus : PanoRenderer.MODE_FLAT);
                return true;
            }
        });
        final ScaleGestureDetector sd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                rend.setFovScale(rend.getFovScale() / d.getScaleFactor());   // spreizen = naeher heran
                return true;
            }
        });
        gl.setOnTouchListener((v, ev) -> {
            sd.onTouchEvent(ev);
            gd.onTouchEvent(ev);
            if (sd.isInProgress() || ev.getPointerCount() > 1) { lx = ev.getX(); ly = ev.getY(); return true; }
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
        leiste.setPadding(12, 12, 12, 12);
        leiste.setBackgroundColor(0x77000000);
        leiste.addView(knopf("Galerie", R.drawable.ic_gallery, v -> zurueckZurGalerie()));
        modusKnopf = knopf("", R.drawable.ic_mode, v -> setzeModus((modus + 1) % 3));
        leiste.addView(modusKnopf);
        kompassKnopf = knopf("", R.drawable.ic_compass, v -> schalteKompass());
        leiste.addView(kompassKnopf);
        gyroKnopf = knopf("", R.drawable.ic_gyro, v -> schalteGyro());
        leiste.addView(gyroKnopf);
        halbKnopf = knopf("", 0, v -> {
            sbsHalb = !sbsHalb;
            prefs.edit().putBoolean("halb", sbsHalb).apply();
            rend.setSbsHalf(sbsHalb);
            beschrifte();
        });
        leiste.addView(halbKnopf);
        linsenKnopf = knopf("", 0, v -> {
            linsenAn = !linsenAn;
            prefs.edit().putBoolean("linsen", linsenAn).apply();
            rend.setLens(linsenAn);
            beschrifte();
        });
        leiste.addView(linsenKnopf);
        leiste.addView(knopf("Mitte", R.drawable.ic_center, v -> { rend.resetDrag(); rend.setFovScale(1f); neuZentrieren = true; wendeKursAn(); }));
        root.addView(leiste, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(root);
        Ui.applyInsets(leiste);
        beschrifte();

        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) lade(in.getData());
        else oeffnen();
    }

    private Button knopf(String text, int iconRes, View.OnClickListener l) {
        Button k = new Button(this);
        k.setText(text);
        k.setTextSize(10);
        k.setTextColor(android.graphics.Color.WHITE);
        k.setAllCaps(false);
        if (iconRes != 0) {
            k.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
            k.setCompoundDrawablePadding(4);
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(18);
        bg.setColor(0x55000000);
        bg.setStroke(2, 0x44FFFFFF);
        k.setBackground(bg);
        k.setPadding(8, 12, 8, 12);
        k.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(4, 4, 4, 4);
        k.setLayoutParams(lp);
        return k;
    }

    private void beschrifte() {
        modusKnopf.setText("Modus:\n" + MODUS_NAMEN[modus]);
        boolean kompassOk = !Double.isNaN(poseHeading);
        kompassKnopf.setText(!kompassOk ? "Kompass:\nn/a" : kompassWunsch ? "Kompass:\nan" : "Kompass:\naus");
        gyroKnopf.setText(gyroAn ? "Gyro:\nan" : "Gyro:\naus");
        halbKnopf.setText(sbsHalb ? "Halb-SBS:\nan" : "Halb-SBS:\naus");
        halbKnopf.setVisibility(modus == PanoRenderer.MODE_SBS ? View.VISIBLE : View.GONE);
        linsenKnopf.setText(linsenAn ? "Linsen:\nan" : "Linsen:\naus");
        linsenKnopf.setVisibility(modus == PanoRenderer.MODE_CARDBOARD ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onResume() {
        super.onResume();
        gl.onResume();
        starteSensor();
        if (player != null) player.start();
    }

    @Override
    protected void onPause() {
        stoppeSensor();
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

    private void starteSensor() {
        if (!gyroAn) { rend.setRotation(null); return; }
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);   // Gyro + Beschleunigung + Magnetometer
        if (s != null) {
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            sensorAktiv = true;
            neuZentrieren = true;
        } else {
            Toast.makeText(this, "Kein Rotationssensor - nur Touch-Steuerung.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stoppeSensor() {
        sm.unregisterListener(this);
        sensorAktiv = false;
    }

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
        if (kompassWunsch && !Double.isNaN(poseHeading)) {
            rend.setHeadingOffset((float) (poseHeading - 180));
        } else {
            // Ohne Kompass: Mitte des Bildes liegt dort, wohin das Telefon beim Zentrieren zeigt (-Z des Geraets)
            double dev = (gyroAn && sensorAktiv) ? Math.toDegrees(Math.atan2(-r2[2], -r2[5])) : 0;
            rend.setHeadingOffset((float) (dev - azMitte));
        }
    }

    private void schalteKompass() {
        if (Double.isNaN(poseHeading)) {
            Toast.makeText(this, "Dieses Bild enthaelt keine Kompassdaten.", Toast.LENGTH_SHORT).show();
            return;
        }
        kompassWunsch = !kompassWunsch;
        prefs.edit().putBoolean("kompass", kompassWunsch).apply();
        neuZentrieren = true;
        wendeKursAn();
        beschrifte();
    }

    private void schalteGyro() {
        gyroAn = !gyroAn;
        prefs.edit().putBoolean("gyro", gyroAn).apply();
        stoppeSensor();
        starteSensor();
        rend.resetDrag();
        wendeKursAn();
        beschrifte();
    }

    // ------------------------------------------------------------------ Modi

    private void setzeModus(int neu) {
        modus = neu;
        boolean stereo = neu != PanoRenderer.MODE_FLAT;
        if (stereo) letzterStereoModus = neu;
        rend.setMode(neu);
        setRequestedOrientation(stereo ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                       : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        leiste.setVisibility(stereo ? View.GONE : View.VISIBLE);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (stereo) c.hide(WindowInsets.Type.systemBars());
            else c.show(WindowInsets.Type.systemBars());
        }
        beschrifte();
        if (stereo) Toast.makeText(this, "Tippen: Leiste, Doppeltippen: zurueck zu 2D", Toast.LENGTH_SHORT).show();
    }

    private void zurueckZurGalerie() {
        startActivity(new Intent(this, GalleryActivity.class));
        finish();
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
                    neuZentrieren = true;
                    wendeKursAn();
                    beschrifte();
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
