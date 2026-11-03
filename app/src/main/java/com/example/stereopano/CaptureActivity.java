package com.example.stereopano;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Aufnahme einer Stereo-Rundumpanorama (wie die alte Cardboard Camera):
 * Telefon HOCHKANT halten, Arme ausstrecken und sich langsam im Uhrzeigersinn um die eigene Achse drehen.
 * Camera2 liefert Kamerabilder, der Rotationsvektor-Sensor den Drehwinkel, die Streifen werden in
 * {@link StereoStitcher} einsortiert. Ergebnis: .vr.jpg in Bilder/StereoPano.
 */
public class CaptureActivity extends Activity {

    private static final int MAX_BREITE = 1280;            // Kamerabild 4:3, lange Seite max. 1280 px
    private static final double STEREO_WINKEL_GRAD = 3.5;  // Streifenversatz zur Bildmitte (Stereobasis)
    private static final double DELTA_GRAD = 0.6;          // halbe Breite des Mittelungsfensters
    private static final double ZIEL_TEMPO = 8;             // Grad/s des Tempo-Punktes (360 Grad in ca. 45 s)
    private static final long LATENZ_NS = 0;               // ggf. Sensor-/Kameraversatz korrigieren

    // --- UI
    private FrameLayout root;
    private SurfaceView surface;
    private TextView info, griff;
    private Button knopf, sperreKnopf;
    private ImageView galerie;
    private LinearLayout panel;
    private ProgressBar fortschritt;
    private GestureDetector gesten;
    private boolean panelSichtbar = true;
    private boolean belichtungSperren = true;
    private GuideView guide;
    private Button leitKnopf;
    private boolean leitpunkte = true;
    private long startZeit, letzteT;
    private double letzterYaw, tempo;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            float[] R = leitpunkte ? gameYaw.rotationAt(Long.MAX_VALUE) : null;
            if (R != null) {
                double yaw = gameYaw.latest();
                long jetzt = android.os.SystemClock.elapsedRealtime();
                if (letzteT != 0 && jetzt > letzteT)
                    tempo = 0.85 * tempo + 0.15 * (yaw - letzterYaw) / ((jetzt - letzteT) / 1000.0);
                letzterYaw = yaw;
                letzteT = jetzt;
                double anker = nimmtAuf ? startYaw : yaw;
                double ziel = nimmtAuf ? Math.min(360, (jetzt - startZeit) / 1000.0 * ZIEL_TEMPO) : Double.NaN;
                guide.setzen(R, anker, yaw - anker, nimmtAuf, ziel, tempo);
            } else {
                guide.setzen(null, 0, 0, false, Double.NaN, 0);
            }
            guide.postOnAnimation(this);
        }
    };

    // --- Kamera
    private CameraManager cm;
    private String kameraId;
    private CameraCharacteristics cc;
    private Size groesse;
    private int sensorOrientierung;
    private double fPx;
    private boolean zeitstempelEchtzeit;
    private CameraDevice kamera;
    private CameraCaptureSession sitzung;
    private CaptureRequest.Builder anfrage;
    private ImageReader leser;
    private HandlerThread kameraThread, sensorThread;
    private Handler kameraHandler, sensorHandler;
    private boolean surfaceBereit, oeffnet;

    // --- Sensoren
    private SensorManager sm;
    private final YawTracker gameYaw = new YawTracker();
    private final YawTracker kompass = new YawTracker();
    private boolean hatKompass;

    // --- Aufnahme
    private volatile boolean nimmtAuf;
    private volatile StereoStitcher stitcher;
    private double startYaw, startKurs;
    private long letzteAnzeige;
    private MediaRecorder recorder;
    private File audioDatei;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);

        if (!waehleKamera()) {
            Toast.makeText(this, "Keine passende Rueckkamera gefunden.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        baueOberflaeche();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    // ------------------------------------------------------------------ Aufbau

    private boolean waehleKamera() {
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    kameraId = id;
                    cc = c;
                    break;
                }
            }
            if (cc == null) return false;

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (s.getWidth() * 3 == s.getHeight() * 4 && s.getWidth() <= MAX_BREITE
                        && (best == null || s.getWidth() > best.getWidth())) best = s;
            }
            if (best == null) return false;
            groesse = best;

            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientierung = so == null ? 90 : so;
            Integer ts = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            zeitstempelEchtzeit = ts != null && ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;

            // Brennweite in Pixeln aus Sensorgroesse und Brennweite (lange Bildseite)
            SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] fl = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            double lang = Math.max(phys.getWidth(), phys.getHeight());
            double fovLang = 2 * Math.atan(lang / (2 * fl[0]));
            fPx = (groesse.getWidth() / 2.0) / Math.tan(fovLang / 2);
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void baueOberflaeche() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // Vorschau im Hochformat (3:4)
        root.post(() -> {
            ViewGroup.LayoutParams lp = surface.getLayoutParams();
            lp.width = root.getWidth();
            lp.height = root.getWidth() * 4 / 3;
            surface.setLayoutParams(lp);
        });
        SurfaceHolder holder = surface.getHolder();
        holder.setFixedSize(groesse.getWidth(), groesse.getHeight());
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                surfaceBereit = true;
                oeffneKamera();
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                surfaceBereit = false;
                schliesseKamera();
            }
        });

        // ---- Leitpunkte direkt ueber dem Kamerabild
        guide = new GuideView(this);
        guide.setFocalRatio(fPx / groesse.getHeight());
        root.addView(guide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- Overlay ueber dem Kamerabild (Insets beachten)
        FrameLayout overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.applyInsets(overlay);

        // oben: Fortschritt + Hinweis (bleiben auch bei verstecktem Panel sichtbar)
        LinearLayout oben = new LinearLayout(this);
        oben.setOrientation(LinearLayout.VERTICAL);
        fortschritt = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fortschritt.setMax(1000);
        oben.addView(fortschritt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(15);
        info.setPadding(dp(12), dp(6), dp(12), dp(6));
        info.setBackgroundColor(0x66000000);
        info.setText("Hochkant halten, Arme ausstrecken, Start tippen, dann langsam nach rechts drehen.");
        oben.addView(info);
        overlay.addView(oben, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // unten: Bedienpanel (Galerie | Ausloeser | Belichtungssperre)
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(16), dp(12), dp(16), dp(20));
        panel.setBackgroundColor(0x55000000);

        galerie = new ImageView(this);
        galerie.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable rund = new GradientDrawable();
        rund.setShape(GradientDrawable.OVAL);
        rund.setColor(0x44FFFFFF);
        rund.setStroke(dp(2), Color.WHITE);
        galerie.setBackground(rund);
        galerie.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        galerie.setClipToOutline(true);
        galerie.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        panel.addView(galerie, gewicht(dp(56), dp(56)));

        knopf = new Button(this);
        knopf.setAllCaps(false);
        knopf.setTextSize(11);
        knopf.setTextColor(Color.BLACK);
        knopf.setText("Start");
        setzeAusloeser(false);
        knopf.setOnClickListener(v -> {
            if (nimmtAuf) beendeAufnahme(); else starteAufnahme();
        });
        LinearLayout.LayoutParams lpk = new LinearLayout.LayoutParams(dp(80), dp(80));
        lpk.setMargins(dp(20), 0, dp(20), 0);
        panel.addView(knopf, lpk);

        sperreKnopf = new Button(this);
        sperreKnopf.setAllCaps(false);
        sperreKnopf.setTextSize(11);
        styleToolButton(sperreKnopf, R.drawable.ic_lock);
        aktualisiereSperreKnopf();
        sperreKnopf.setOnClickListener(v -> {
            if (nimmtAuf) return;
            belichtungSperren = !belichtungSperren;
            aktualisiereSperreKnopf();
        });
        panel.addView(sperreKnopf, gewicht(dp(80), dp(56)));

        leitKnopf = new Button(this);
        leitKnopf.setAllCaps(false);
        leitKnopf.setTextSize(11);
        styleToolButton(leitKnopf, R.drawable.ic_guide);
        aktualisiereLeitKnopf();
        leitKnopf.setOnClickListener(v -> {
            leitpunkte = !leitpunkte;
            aktualisiereLeitKnopf();
        });
        panel.addView(leitKnopf, gewicht(dp(80), dp(56)));

        overlay.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Griff, wenn das Panel versteckt ist (antippen oder nach oben wischen)
        griff = new TextView(this);
        griff.setText("\u25B2  Steuerung");
        griff.setTextColor(Color.WHITE);
        griff.setTextSize(13);
        griff.setGravity(Gravity.CENTER);
        griff.setPadding(dp(16), dp(6), dp(16), dp(10));
        griff.setBackgroundColor(0x55000000);
        griff.setVisibility(View.GONE);
        griff.setOnClickListener(v -> zeigePanel(true));
        overlay.addView(griff, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // Wischgesten: nach unten = Panel verstecken, nach oben = zeigen
        gesten = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vy) > Math.abs(vx) && Math.abs(vy) > 800) {
                    zeigePanel(vy < 0);
                    return true;
                }
                return false;
            }
        });
        setContentView(root);
        ladeMiniatur();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gesten != null) gesten.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private LinearLayout.LayoutParams gewicht(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private void styleToolButton(Button btn, int iconRes) {
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(10);
        btn.setAllCaps(false);
        if (iconRes != 0) {
            btn.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
            btn.setCompoundDrawablePadding(dp(4));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(18));
        bg.setColor(0x55000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        btn.setBackground(bg);
        btn.setPadding(dp(6), dp(6), dp(6), dp(6));
    }

    private void zeigePanel(boolean zeigen) {
        if (zeigen == panelSichtbar) return;
        panelSichtbar = zeigen;
        if (zeigen) {
            griff.setVisibility(View.GONE);
            panel.animate().translationY(0).alpha(1).setDuration(200).start();
        } else {
            panel.animate().translationY(panel.getHeight()).alpha(0).setDuration(200)
                    .withEndAction(() -> { if (!panelSichtbar) griff.setVisibility(View.VISIBLE); }).start();
        }
    }

    private void setzeAusloeser(boolean laeuft) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(laeuft ? 0xFFE53935 : 0xFFFFFFFF);
        g.setStroke(dp(4), laeuft ? 0xFFFFCDD2 : 0xFFB0BEC5);
        knopf.setBackground(g);
        knopf.setText(laeuft ? "Stopp" : "Start");
        knopf.setTextColor(laeuft ? Color.WHITE : Color.BLACK);
    }

    private void aktualisiereLeitKnopf() {
        leitKnopf.setText(leitpunkte ? "Leitpunkte\nan" : "Leitpunkte\naus");
        if (guide != null) guide.setVisibility(leitpunkte ? View.VISIBLE : View.INVISIBLE);
    }

    private void aktualisiereSperreKnopf() {
        sperreKnopf.setText(belichtungSperren ? "Belichtung\nfixiert" : "Belichtung\nauto");
    }

    /** Zeigt die letzte Aufnahme als runde Miniatur (Knopf zur Galerie). */
    private void ladeMiniatur() {
        new Thread(() -> {
            try {
                Uri u = GalleryActivity.letzteAufnahme(this);
                if (u == null) return;
                Bitmap bm = getContentResolver().loadThumbnail(u, new Size(256, 256), null);
                runOnUiThread(() -> galerie.setImageBitmap(bm));
            } catch (Exception ignored) { }
        }).start();
    }

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onResume() {
        super.onResume();
        startSensoren();
        letzteT = 0;
        if (guide != null) guide.postOnAnimation(tick);
        if (surfaceBereit) oeffneKamera();
    }

    @Override
    protected void onPause() {
        if (guide != null) guide.removeCallbacks(tick);
        if (nimmtAuf) verwirfAufnahme();
        schliesseKamera();
        stopSensoren();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            oeffneKamera();
        } else {
            Toast.makeText(this, "Ohne Kamera-Berechtigung geht es nicht.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ------------------------------------------------------------------ Sensoren

    private void startSensoren() {
        sensorThread = new HandlerThread("sensor");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        Sensor game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (game == null) game = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor mag = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (game == null) {
            Toast.makeText(this, "Kein Rotationssensor vorhanden.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        sm.registerListener(gameYaw, game, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
        hatKompass = mag != null;
        if (hatKompass) sm.registerListener(kompass, mag, SensorManager.SENSOR_DELAY_GAME, sensorHandler);
    }

    private void stopSensoren() {
        sm.unregisterListener(gameYaw);
        sm.unregisterListener(kompass);
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
        }
    }

    // ------------------------------------------------------------------ Kamera (Camera2)

    private void oeffneKamera() {
        if (kamera != null || oeffnet || !surfaceBereit) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        oeffnet = true;
        kameraThread = new HandlerThread("kamera");
        kameraThread.start();
        kameraHandler = new Handler(kameraThread.getLooper());
        try {
            cm.openCamera(kameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    oeffnet = false;
                    if (!surfaceBereit) { c.close(); return; }
                    kamera = c;
                    starteSitzung();
                }
                @Override public void onDisconnected(CameraDevice c) {
                    oeffnet = false; c.close(); kamera = null;
                }
                @Override public void onError(CameraDevice c, int err) {
                    oeffnet = false; c.close(); kamera = null;
                    runOnUiThread(() -> Toast.makeText(CaptureActivity.this, "Kamerafehler " + err, Toast.LENGTH_LONG).show());
                }
            }, kameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            oeffnet = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void starteSitzung() {
        try {
            leser = ImageReader.newInstance(groesse.getWidth(), groesse.getHeight(), ImageFormat.YUV_420_888, 3);
            leser.setOnImageAvailableListener(this::bildDa, kameraHandler);
            Surface vorschau = surface.getHolder().getSurface();

            anfrage = kamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            anfrage.addTarget(vorschau);
            anfrage.addTarget(leser.getSurface());
            anfrage.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // Stabilisierung aus: sie wuerde das Sichtfeld unvorhersehbar verschieben
            if (hat(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF))
                anfrage.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            if (hat(cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF))
                anfrage.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);

            kamera.createCaptureSession(Arrays.asList(vorschau, leser.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            sitzung = s;
                            try {
                                s.setRepeatingRequest(anfrage.build(), null, kameraHandler);
                            } catch (CameraAccessException | IllegalStateException ignored) { }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> Toast.makeText(CaptureActivity.this, "Kamera-Sitzung fehlgeschlagen", Toast.LENGTH_LONG).show());
                        }
                    }, kameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // Kamera wurde zwischenzeitlich geschlossen
        }
    }

    private static boolean hat(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private void schliesseKamera() {
        if (sitzung != null) { sitzung.close(); sitzung = null; }
        if (kamera != null) { kamera.close(); kamera = null; }
        if (leser != null) { leser.close(); leser = null; }
        if (kameraThread != null) { kameraThread.quitSafely(); kameraThread = null; }
        oeffnet = false;
    }

    // ------------------------------------------------------------------ Aufnahme

    private void starteAufnahme() {
        if (!gameYaw.hasData() || sitzung == null) {
            Toast.makeText(this, "Sensoren/Kamera noch nicht bereit.", Toast.LENGTH_SHORT).show();
            return;
        }
        startYaw = gameYaw.latest();
        startKurs = hatKompass ? kompass.azimuth() : Double.NaN;
        stitcher = new StereoStitcher(groesse.getHeight(), groesse.getWidth(), fPx, STEREO_WINKEL_GRAD, DELTA_GRAD, startYaw);

        if (belichtungSperren) setzeSperren(true);
        starteAudio();
        startZeit = android.os.SystemClock.elapsedRealtime();
        nimmtAuf = true;
        setzeAusloeser(true);
        info.setText("Aufnahme laeuft - langsam nach rechts drehen (360 Grad).");
    }

    private void setzeSperren(boolean an) {
        try {
            anfrage.set(CaptureRequest.CONTROL_AE_LOCK, an);
            anfrage.set(CaptureRequest.CONTROL_AWB_LOCK, an);
            sitzung.setRepeatingRequest(anfrage.build(), null, kameraHandler);
        } catch (CameraAccessException | IllegalStateException | NullPointerException ignored) { }
    }

    /** Wird fuer jedes Kamerabild im Kamera-Thread aufgerufen. */
    private void bildDa(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            StereoStitcher s = stitcher;
            if (!nimmtAuf || s == null) return;
            long ts = zeitstempelEchtzeit ? img.getTimestamp() + LATENZ_NS : Long.MAX_VALUE;
            double yaw = gameYaw.yawAt(ts);
            float[] rot = gameYaw.rotationAt(ts);
            double psi = yaw - startYaw;
            if (Double.isNaN(psi) || rot == null) return;

            s.addFrame(new YuvFrame(img, sensorOrientierung), psi, rot);

            long jetzt = System.currentTimeMillis();
            if (jetzt - letzteAnzeige > 100) {
                letzteAnzeige = jetzt;
                final double fort = s.progress();
                final double pitch = gameYaw.pitch();
                final double p = psi;
                runOnUiThread(() -> aktualisiereAnzeige(fort, pitch, p));
            }
            if (s.isComplete()) runOnUiThread(this::beendeAufnahme);
        } finally {
            img.close();
        }
    }

    private void aktualisiereAnzeige(double fort, double pitch, double psi) {
        fortschritt.setProgress((int) (fort * 1000));
        String hinweis;
        if (psi < -10) hinweis = "Bitte nach RECHTS drehen.";
        else if (Math.abs(pitch) > 15) hinweis = "Telefon senkrecht halten (Neigung " + Math.round(pitch) + " Grad).";
        else hinweis = "Weiter drehen ...";
        info.setText(String.format(Locale.GERMANY, "%.0f / 360 Grad - %s", fort * 360, hinweis));
    }

    private void verwirfAufnahme() {
        nimmtAuf = false;
        if (stitcher != null) stitcher.close();
        stitcher = null;
        stopAudio();
        if (belichtungSperren && sitzung != null) setzeSperren(false);
        setzeAusloeser(false);
        fortschritt.setProgress(0);
    }

    private void beendeAufnahme() {
        if (!nimmtAuf) return;
        nimmtAuf = false;
        final StereoStitcher s = stitcher;
        stitcher = null;
        s.close();
        final byte[] ton = stopAudio();
        if (belichtungSperren && sitzung != null) setzeSperren(false);

        knopf.setEnabled(false);
        info.setText("Speichere ...");
        final double kurs = startKurs;
        new Thread(() -> {
            String meldung;
            try {
                Uri uri = speichere(s, ton, kurs);
                meldung = "Gespeichert: " + uri;
            } catch (Exception | OutOfMemoryError e) {
                meldung = "Fehler beim Speichern: " + e.getMessage();
            }
            final String m = meldung;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                knopf.setEnabled(true);
                setzeAusloeser(false);
                ladeMiniatur();
                fortschritt.setProgress(0);
                info.setText("Fertig. Noch eine Aufnahme?");
            });
        }).start();
    }

    // ------------------------------------------------------------------ Speichern

    private Uri speichere(StereoStitcher s, byte[] ton, double kurs) throws Exception {
        StereoStitcher.Result e = s.result();
        if (e.width < 100) throw new Exception("Zu wenig aufgenommen - mindestens ca. 20 Grad drehen.");

        byte[] links = jpeg(e.left, e);
        byte[] rechts = jpeg(e.right, e);

        VrJpgWriter.Meta m = new VrJpgWriter.Meta();
        m.fullW = e.panoW;
        m.fullH = e.panoW / 2;
        m.cropW = e.width;
        m.cropH = e.panoH;
        m.cropLeft = e.x0;
        m.cropTop = (m.fullH - e.panoH) / 2;
        // Kompasskurs der Mitte des vollen Panoramas (Spalte 0 = Startrichtung)
        if (!Double.isNaN(kurs)) m.poseHeading = (kurs + 180) % 360;
        byte[] datei = VrJpgWriter.build(links, rechts, ton, m);

        String name = "PANO_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".vr.jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StereoPano");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("MediaStore-Eintrag nicht moeglich");
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            os.write(datei);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri;
    }

    private static byte[] jpeg(int[] pixel, StereoStitcher.Result e) {
        Bitmap bmp = Bitmap.createBitmap(pixel, e.x0, e.panoW, e.width, e.panoH, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(4_000_000);
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo);
        bmp.recycle();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ Ton (wie Cardboard Camera)

    @SuppressWarnings("deprecation")
    private void starteAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            audioDatei = File.createTempFile("pano", ".m4a", getCacheDir());
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(audioDatei.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            if (recorder != null) { recorder.release(); recorder = null; }
        }
    }

    private byte[] stopAudio() {
        if (recorder == null) return null;
        byte[] data = null;
        try {
            recorder.stop();
            data = Files.readAllBytes(audioDatei.toPath());
        } catch (Exception ignored) {
        } finally {
            recorder.release();
            recorder = null;
            if (audioDatei != null) audioDatei.delete();
        }
        return data;
    }

    // ------------------------------------------------------------------ YUV -> RGB

    /**
     * Kamerabild (YUV_420_888, im Sensor-Koordinatensystem) als aufrechtes Hochformatbild.
     * Sensororientierung 90: aufrecht(ux, uy) = roh(x = uy, y = H-1-ux)
     * Sensororientierung 270: aufrecht(ux, uy) = roh(x = W-1-uy, y = ux)
     */
    private static final class YuvFrame implements StereoStitcher.Frame {
        private final ByteBuffer yb, ub, vb;
        private final int yRow, uRow, vRow, uPix, vPix, rawW, rawH;
        private final boolean rot90;

        YuvFrame(Image img, int sensorOrientation) {
            Image.Plane[] p = img.getPlanes();
            yb = p[0].getBuffer();
            ub = p[1].getBuffer();
            vb = p[2].getBuffer();
            yRow = p[0].getRowStride();
            uRow = p[1].getRowStride();
            vRow = p[2].getRowStride();
            uPix = p[1].getPixelStride();
            vPix = p[2].getPixelStride();
            rawW = img.getWidth();
            rawH = img.getHeight();
            rot90 = sensorOrientation != 270;
        }

        @Override
        public int rgb(int ux, int uy) {
            int x, y;
            if (rot90) { x = uy; y = rawH - 1 - ux; }
            else       { x = rawW - 1 - uy; y = ux; }
            int yy = yb.get(y * yRow + x) & 0xFF;
            int cu = (ub.get((y >> 1) * uRow + (x >> 1) * uPix) & 0xFF) - 128;
            int cv = (vb.get((y >> 1) * vRow + (x >> 1) * vPix) & 0xFF) - 128;
            int r = clamp(yy + (int) (1.402f * cv));
            int g = clamp(yy - (int) (0.344f * cu + 0.714f * cv));
            int b = clamp(yy + (int) (1.772f * cu));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
    }
}
