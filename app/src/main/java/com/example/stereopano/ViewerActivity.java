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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ViewerActivity extends Activity implements SensorEventListener {

    private static final int REQ_OEFFNEN = 7;

    private GLSurfaceView gl;
    private PanoRenderer rend;
    private LinearLayout leiste;
    private ImageButton galerieKnopf, modusKnopf, kompassKnopf, gyroKnopf, halbKnopf, linsenKnopf, mitteKnopf, einstellungenKnopf;

    private SensorManager sm;
    private SharedPreferences prefs;
    private final float[] r = new float[9], r2 = new float[9];

    private double poseHeading = Double.NaN;
    private double azMitte;
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
                rend.setFovScale(rend.getFovScale() / d.getScaleFactor());
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
        leiste.setGravity(Gravity.CENTER_VERTICAL);
        leiste.setPadding(dp(8), dp(8), dp(8), dp(8));
        leiste.setBackgroundColor(0x77000000);

        galerieKnopf = Ui.createRoundButton(this, R.drawable.ic_gallery, R.string.btn_gallery, v -> zurueckZurGalerie());
        leiste.addView(galerieKnopf, knopfLp());

        modusKnopf = Ui.createRoundButton(this, R.drawable.ic_mode_2d, R.string.btn_mode_2d, v -> {
            setzeModus((modus + 1) % 3);
            int msgRes = modus == PanoRenderer.MODE_FLAT ? R.string.msg_mode_2d :
                    (modus == PanoRenderer.MODE_CARDBOARD ? R.string.msg_mode_cardboard : R.string.msg_mode_sbs);
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
        });
        leiste.addView(modusKnopf, knopfLp());

        kompassKnopf = Ui.createRoundButton(this, R.drawable.ic_compass, R.string.btn_compass, v -> schalteKompass());
        leiste.addView(kompassKnopf, knopfLp());

        gyroKnopf = Ui.createRoundButton(this, R.drawable.ic_gyro, R.string.btn_gyro, v -> schalteGyro());
        leiste.addView(gyroKnopf, knopfLp());

        linsenKnopf = Ui.createRoundButton(this, R.drawable.ic_lens, R.string.btn_lens, v -> {
            linsenAn = !linsenAn;
            prefs.edit().putBoolean("linsen", linsenAn).apply();
            rend.setLens(linsenAn);
            beschrifte();
            Toast.makeText(this, linsenAn ? R.string.msg_lens_on : R.string.msg_lens_off, Toast.LENGTH_SHORT).show();
        });
        leiste.addView(linsenKnopf, knopfLp());

        halbKnopf = Ui.createRoundButton(this, R.drawable.ic_half_sbs, R.string.btn_half_sbs, v -> {
            sbsHalb = !sbsHalb;
            prefs.edit().putBoolean("halb", sbsHalb).apply();
            rend.setSbsHalf(sbsHalb);
            beschrifte();
            Toast.makeText(this, sbsHalb ? R.string.msg_half_sbs_on : R.string.msg_half_sbs_off, Toast.LENGTH_SHORT).show();
        });
        leiste.addView(halbKnopf, knopfLp());

        mitteKnopf = Ui.createRoundButton(this, R.drawable.ic_center, R.string.btn_center, v -> {
            rend.resetDrag();
            rend.setFovScale(1f);
            neuZentrieren = true;
            wendeKursAn();
            Toast.makeText(this, R.string.msg_view_centered, Toast.LENGTH_SHORT).show();
        });
        leiste.addView(mitteKnopf, knopfLp());

        einstellungenKnopf = Ui.createRoundButton(this, R.drawable.ic_settings, R.string.btn_settings,
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        leiste.addView(einstellungenKnopf, knopfLp());

        root.addView(leiste, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(root);
        Ui.applyInsets(leiste);
        beschrifte();

        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) lade(in.getData());
        else oeffnen();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout.LayoutParams knopfLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44), 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        return lp;
    }

    private void beschrifte() {
        if (modus == PanoRenderer.MODE_FLAT) {
            modusKnopf.setImageResource(R.drawable.ic_mode_2d);
            modusKnopf.setContentDescription(getString(R.string.btn_mode_2d));
            Ui.setRoundButtonState(modusKnopf, Ui.ButtonState.INACTIVE);
        } else if (modus == PanoRenderer.MODE_CARDBOARD) {
            modusKnopf.setImageResource(R.drawable.ic_mode_cardboard);
            modusKnopf.setContentDescription(getString(R.string.btn_mode_cardboard));
            Ui.setRoundButtonState(modusKnopf, Ui.ButtonState.ACTIVE);
        } else {
            modusKnopf.setImageResource(R.drawable.ic_mode_sbs);
            modusKnopf.setContentDescription(getString(R.string.btn_mode_sbs));
            Ui.setRoundButtonState(modusKnopf, Ui.ButtonState.ACTIVE);
        }

        boolean kompassOk = !Double.isNaN(poseHeading);
        if (!kompassOk) {
            Ui.setRoundButtonState(kompassKnopf, Ui.ButtonState.DISABLED);
        } else {
            Ui.setRoundButtonState(kompassKnopf, kompassWunsch ? Ui.ButtonState.ACTIVE : Ui.ButtonState.INACTIVE);
        }

        Ui.setRoundButtonState(gyroKnopf, gyroAn ? Ui.ButtonState.ACTIVE : Ui.ButtonState.INACTIVE);

        halbKnopf.setVisibility(modus == PanoRenderer.MODE_SBS ? View.VISIBLE : View.GONE);
        Ui.setRoundButtonState(halbKnopf, sbsHalb ? Ui.ButtonState.ACTIVE : Ui.ButtonState.INACTIVE);

        linsenKnopf.setVisibility(modus == PanoRenderer.MODE_CARDBOARD ? View.VISIBLE : View.GONE);
        Ui.setRoundButtonState(linsenKnopf, linsenAn ? Ui.ButtonState.ACTIVE : Ui.ButtonState.INACTIVE);

        Ui.setRoundButtonState(mitteKnopf, Ui.ButtonState.INACTIVE);
        Ui.setRoundButtonState(galerieKnopf, Ui.ButtonState.INACTIVE);
        Ui.setRoundButtonState(einstellungenKnopf, Ui.ButtonState.INACTIVE);
    }

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

    private void starteSensor() {
        if (!gyroAn) { rend.setRotation(null); return; }
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s != null) {
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            sensorAktiv = true;
            neuZentrieren = true;
        } else {
            Toast.makeText(this, R.string.no_gyro, Toast.LENGTH_SHORT).show();
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

    private void wendeKursAn() {
        if (kompassWunsch && !Double.isNaN(poseHeading)) {
            rend.setHeadingOffset((float) (poseHeading - 180));
        } else {
            double dev = (gyroAn && sensorAktiv) ? Math.toDegrees(Math.atan2(-r2[2], -r2[5])) : 0;
            rend.setHeadingOffset((float) (dev - azMitte));
        }
    }

    private void schalteKompass() {
        if (Double.isNaN(poseHeading)) {
            Toast.makeText(this, R.string.msg_compass_na, Toast.LENGTH_SHORT).show();
            return;
        }
        kompassWunsch = !kompassWunsch;
        prefs.edit().putBoolean("kompass", kompassWunsch).apply();
        neuZentrieren = true;
        wendeKursAn();
        beschrifte();
        Toast.makeText(this, kompassWunsch ? R.string.msg_compass_on : R.string.msg_compass_off, Toast.LENGTH_SHORT).show();
    }

    private void schalteGyro() {
        gyroAn = !gyroAn;
        prefs.edit().putBoolean("gyro", gyroAn).apply();
        stoppeSensor();
        starteSensor();
        rend.resetDrag();
        wendeKursAn();
        beschrifte();
        Toast.makeText(this, gyroAn ? R.string.msg_gyro_on : R.string.msg_gyro_off, Toast.LENGTH_SHORT).show();
    }

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
    }

    private void zurueckZurGalerie() {
        if (isTaskRoot()) {
            startActivity(new Intent(this, GalleryActivity.class));
        }
        finish();
    }

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
                });
            } catch (Exception | OutOfMemoryError ex) {
                runOnUiThread(() -> Toast.makeText(this, "Fehler beim Laden: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

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
