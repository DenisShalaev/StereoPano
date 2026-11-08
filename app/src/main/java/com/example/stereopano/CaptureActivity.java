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

    // Werte kommen aus den Einstellungen (Zahnrad) - siehe ladeEinstellungen()
    private int maxBreite = 1280;          // Kamerabild 4:3, lange Seite max. N px
    private double stereoWinkel = 3.5;     // Streifenversatz zur Bildmitte (Stereobasis)
    private double deltaGrad = 0.6;        // halbe Breite des Mittelungsfensters
    private double zielTempo = 8;          // Grad/s des Tempo-Punktes
    private long latenzNs = 0;             // Sensor-/Kameraversatz
    private double fpxFaktor = 1.0;        // Korrektur der berechneten Brennweite
    private int jpegQualitaet = 90;
    private boolean audioAn = true;
    private double fPxBasis;
    private int bewegung = 0;             // Empfindlichkeit der Erkennung bewegter Objekte (0 = aus)
    private float schaerfe = 0.5f;        // Nachschaerfung des fertigen Panoramas (0..1)
    private double maxNick = 6;          // Grad/s: schnellere Nick-/Kippbewegung -> Bild wird verworfen
    private volatile long expNs, skewNs;  // letzte Belichtungszeit / Rolling-Shutter-Versatz aus den Kamera-Ergebnissen
    private long vorherTs;
    private double vorherNick = Double.NaN, vorherKipp = Double.NaN;

    // --- UI
    private FrameLayout root;
    private SurfaceView surface;
    private TextView info;
    private ImageView griff;
    private ImageView knopf, sperreKnopf, leitKnopf;
    private ImageView galerie;
    private LinearLayout panel;
    private ProgressBar fortschritt;
    private GestureDetector gesten;
    private boolean panelSichtbar = true;
    private boolean belichtungSperren = true;
    private volatile double fPxGeraet = 0;     // Brennweite in Pixeln laut Kamera (nur zur Diagnose)
    private GuideView guide;
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
                double ziel = nimmtAuf ? Math.min(360, (jetzt - startZeit) / 1000.0 * zielTempo) : Double.NaN;
                double elVirt = nimmtAuf ? startEl : snapElevation(Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -R[8])))));
                guide.setzen(R, anker, yaw - anker, nimmtAuf, ziel, tempo, elVirt);
            } else {
                guide.setzen(null, 0, 0, false, Double.NaN, 0, 0);
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
    private HandlerThread kameraThread, sensorThread, rechenThread;
    private Handler rechenHandler;
    private final java.util.concurrent.atomic.AtomicReference<Job> offen = new java.util.concurrent.atomic.AtomicReference<>();
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
    private double startEl;                   // Sollneigung (Elevation der Panoramamitte), beim Start festgelegt
    private ImageView blitzKnopf, lensKnopf;
    private boolean blitzAn;
    private long letzteAnzeige;
    private MediaRecorder recorder;
    private File audioDatei;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        ladeEinstellungen();

        if (!waehleKamera()) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        baueOberflaeche();

        java.util.List<String> fehlend = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fehlend.add(Manifest.permission.CAMERA);
            fehlend.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Settings.ortSpeichern(this) && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fehlend.add(Manifest.permission.ACCESS_COARSE_LOCATION);      // Ort ist optional: ohne Berechtigung wird nichts gespeichert
            fehlend.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!fehlend.isEmpty()) requestPermissions(fehlend.toArray(new String[0]), 1);
    }

    // ------------------------------------------------------------------ Aufbau

    /** Ein waehlbares "Objektiv": Kamera-ID + Zoomfaktor (0.5 = Ultraweitwinkel ueber die logische Kamera, z. B. Pixel). */
    private static final class Objektiv {
        final String id;
        final double zoom;
        final String name;
        Objektiv(String id, double zoom, String name) { this.id = id; this.zoom = zoom; this.name = name; }
    }

    private final java.util.List<Objektiv> objektive = new java.util.ArrayList<>();
    private int objIndex = 0;
    private double zoom = 1.0;
    private boolean hatBlitz;

    private void baueObjektivListe() throws CameraAccessException {
        objektive.clear();
        boolean erste = true;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            if (erste) {
                objektive.add(new Objektiv(id, 1.0, getString(R.string.lens_main)));
                android.util.Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null && zr.getLower() <= 0.55f) objektive.add(new Objektiv(id, 0.5, getString(R.string.lens_ultrawide)));
                erste = false;
            } else {
                objektive.add(new Objektiv(id, 1.0, getString(R.string.lens_other, id)));
            }
        }
    }

    private boolean waehleKamera() {
        try {
            if (objektive.isEmpty()) baueObjektivListe();
            if (objektive.isEmpty()) return false;
            if (objIndex >= objektive.size()) objIndex = 0;
            Objektiv o = objektive.get(objIndex);
            kameraId = o.id;
            zoom = o.zoom;
            cc = cm.getCameraCharacteristics(kameraId);

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (s.getWidth() * 3 == s.getHeight() * 4 && s.getWidth() <= maxBreite
                        && (best == null || s.getWidth() > best.getWidth())) best = s;
            }
            if (best == null) return false;
            groesse = best;

            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientierung = so == null ? 90 : so;
            Integer ts = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            zeitstempelEchtzeit = ts != null && ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;

            // Brennweite in Pixeln aus Sensorgroesse und Brennweite (lange Bildseite); der Zoomfaktor skaliert sie
            SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] fl = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            double lang = Math.max(phys.getWidth(), phys.getHeight());
            double fovLang = 2 * Math.atan(lang / (2 * fl[0]));
            fPxBasis = (groesse.getWidth() / 2.0) / Math.tan(fovLang / 2) * zoom;
            fPx = fPxBasis * fpxFaktor;

            Boolean fa = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hatBlitz = fa != null && fa;
            aktualisiereKameraKnoepfe();
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
    }

    /** Blitz-/Objektivknopf nur zeigen, wenn die Kamera das kann (laeuft immer im UI-Thread). */
    private void aktualisiereKameraKnoepfe() {
        runOnUiThread(() -> {
            if (blitzKnopf != null) {
                blitzKnopf.setVisibility(hatBlitz ? View.VISIBLE : View.GONE);
                if (!hatBlitz) blitzAn = false;
                Ui.zustand(blitzKnopf, blitzAn ? Ui.AN : Ui.AUS);
            }
            if (lensKnopf != null) lensKnopf.setVisibility(objektive.size() > 1 ? View.VISIBLE : View.GONE);
        });
    }

    private void ladeEinstellungen() {
        if (nimmtAuf) return;
        stereoWinkel = Settings.stereoWinkel(this);
        deltaGrad = Settings.delta(this);
        zielTempo = Settings.zielTempo(this);
        maxBreite = Settings.maxBreite(this);
        latenzNs = Settings.latenzNs(this);
        fpxFaktor = Settings.fpxFaktor(this);
        jpegQualitaet = Settings.jpegQualitaet(this);
        audioAn = Settings.audio(this);
        maxNick = Settings.maxNickrate(this);
        schaerfe = Settings.schaerfe(this);
        bewegung = Settings.bewegung(this);
        belichtungSperren = Settings.belichtungSperren(this);
        leitpunkte = Settings.leitpunkte(this);
        if (fPxBasis > 0) fPx = fPxBasis * fpxFaktor;
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
        info.setText(R.string.hint_start);
        LinearLayout zeile = new LinearLayout(this);
        zeile.setOrientation(LinearLayout.HORIZONTAL);
        zeile.setGravity(Gravity.CENTER_VERTICAL);
        zeile.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView zahnrad = Ui.zahnrad(this);
        zahnrad.setOnClickListener(v -> {
            if (nimmtAuf) Toast.makeText(this, R.string.finish_first, Toast.LENGTH_SHORT).show();
            else startActivity(new Intent(this, SettingsActivity.class));
        });
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(dp(42), dp(42));
        zlp.setMargins(dp(6), dp(4), dp(8), 0);
        blitzKnopf = Ui.runderKnopf(this, R.drawable.ic_flash, R.string.cd_flash);
        blitzKnopf.setOnClickListener(v -> schalteBlitz());
        blitzKnopf.setVisibility(hatBlitz ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(42), dp(42));
        blp.setMargins(dp(6), dp(4), 0, 0);
        zeile.addView(blitzKnopf, blp);
        lensKnopf = Ui.runderKnopf(this, R.drawable.ic_lens_switch, R.string.cd_lens_switch);
        lensKnopf.setOnClickListener(v -> wechsleObjektiv());
        lensKnopf.setVisibility(objektive.size() > 1 ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(42), dp(42));
        llp.setMargins(dp(6), dp(4), 0, 0);
        zeile.addView(lensKnopf, llp);
        Ui.zustand(blitzKnopf, blitzAn ? Ui.AN : Ui.AUS);
        zeile.addView(zahnrad, zlp);
        oben.addView(zeile);
        overlay.addView(oben, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // unten: Bedienpanel - nur runde Symbole. Links Galerie, Mitte Ausloeser, rechts Schalter.
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(24));

        LinearLayout links = new LinearLayout(this);
        links.setGravity(Gravity.CENTER);
        galerie = new ImageView(this);
        galerie.setImageResource(R.drawable.ic_gallery);
        galerie.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        galerie.setPadding(dp(13), dp(13), dp(13), dp(13));
        galerie.setContentDescription(getString(R.string.cd_gallery));
        galerie.setTooltipText(getString(R.string.cd_gallery));
        GradientDrawable rund = new GradientDrawable();
        rund.setShape(GradientDrawable.OVAL);
        rund.setColor(0x73000000);
        rund.setStroke(dp(2), Color.WHITE);
        galerie.setBackground(rund);
        galerie.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        galerie.setClipToOutline(true);
        galerie.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        links.addView(galerie, gewicht(dp(52), dp(52)));
        panel.addView(links, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        knopf = new ImageView(this);
        knopf.setScaleType(ImageView.ScaleType.FIT_CENTER);
        setzeAusloeser(false);
        knopf.setOnClickListener(v -> {
            if (nimmtAuf) beendeAufnahme(); else starteAufnahme();
        });
        panel.addView(knopf, gewicht(dp(72), dp(72)));   // exakt mittig: links/rechts haben gleiches Gewicht

        LinearLayout rechts = new LinearLayout(this);
        rechts.setGravity(Gravity.CENTER);
        sperreKnopf = Ui.runderKnopf(this, R.drawable.ic_lock, R.string.cd_lock);
        sperreKnopf.setOnClickListener(v -> {
            if (nimmtAuf) return;
            belichtungSperren = !belichtungSperren;
            Settings.p(this).edit().putBoolean(Settings.K_AE, belichtungSperren).apply();
            aktualisiereSperreKnopf();
            Toast.makeText(this, belichtungSperren ? R.string.lock_on : R.string.lock_off, Toast.LENGTH_SHORT).show();
        });
        leitKnopf = Ui.runderKnopf(this, R.drawable.ic_guide, R.string.cd_guides);
        leitKnopf.setOnClickListener(v -> {
            leitpunkte = !leitpunkte;
            Settings.p(this).edit().putBoolean(Settings.K_LEIT, leitpunkte).apply();
            aktualisiereLeitKnopf();
            Toast.makeText(this, leitpunkte ? R.string.guides_on : R.string.guides_off, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lpr = gewicht(dp(46), dp(46));
        lpr.setMargins(dp(4), 0, dp(4), 0);
        rechts.addView(sperreKnopf, lpr);
        rechts.addView(leitKnopf, gewicht(dp(46), dp(46)));
        ((LinearLayout.LayoutParams) leitKnopf.getLayoutParams()).setMargins(dp(4), 0, dp(4), 0);
        aktualisiereSperreKnopf();
        aktualisiereLeitKnopf();
        panel.addView(rechts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        overlay.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Griff, wenn das Panel versteckt ist (antippen oder nach oben wischen)
        griff = Ui.runderKnopf(this, R.drawable.ic_chevron_up, R.string.cd_show_controls);
        griff.setVisibility(View.GONE);
        griff.setOnClickListener(v -> zeigePanel(true));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        glp.bottomMargin = dp(16);
        overlay.addView(griff, glp);

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
        knopf.setImageResource(laeuft ? R.drawable.ic_shutter_recording : R.drawable.ic_shutter_idle);
        String cd = getString(laeuft ? R.string.cd_shutter_stop : R.string.cd_shutter_start);
        knopf.setContentDescription(cd);
        knopf.setTooltipText(cd);
    }

    private void aktualisiereLeitKnopf() {
        Ui.zustand(leitKnopf, leitpunkte ? Ui.AN : Ui.AUS);
        if (guide != null) guide.setVisibility(leitpunkte ? View.VISIBLE : View.INVISIBLE);
    }

    private void aktualisiereSperreKnopf() {
        Ui.zustand(sperreKnopf, belichtungSperren ? Ui.AN : Ui.AUS);
    }

    /** Zeigt die letzte Aufnahme als runde Miniatur (Knopf zur Galerie). */
    private void ladeMiniatur() {
        new Thread(() -> {
            try {
                Uri u = GalleryActivity.letzteAufnahme(this);
                if (u == null) return;
                Bitmap bm = getContentResolver().loadThumbnail(u, new Size(256, 256), null);
                runOnUiThread(() -> {
                    galerie.setPadding(0, 0, 0, 0);
                    galerie.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    galerie.setImageBitmap(bm);
                });
            } catch (Exception ignored) { }
        }).start();
    }

    // ------------------------------------------------------------------ Lebenszyklus

    // ------------------------------------------------------------------ Blitz, Objektiv, Neigung, Ort

    /** Sollneigung: kleine Neigungen (unter 6 Grad) gelten als "Horizont", sonst auf ganze Grad gerundet und begrenzt. */
    private static double snapElevation(double pitchDeg) {
        if (Math.abs(pitchDeg) < 6) return 0;
        return Math.max(-40, Math.min(58, Math.round(pitchDeg)));
    }

    private void schalteBlitz() {
        if (!hatBlitz) return;
        blitzAn = !blitzAn;
        Ui.zustand(blitzKnopf, blitzAn ? Ui.AN : Ui.AUS);
        try {
            if (anfrage != null && sitzung != null) {
                anfrage.set(CaptureRequest.FLASH_MODE, blitzAn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                sitzung.setRepeatingRequest(anfrage.build(), ergebnisCallback, kameraHandler);
            }
        } catch (CameraAccessException | IllegalStateException ignored) { }
    }

    private void wechsleObjektiv() {
        if (nimmtAuf || objektive.size() <= 1) {
            if (nimmtAuf) Toast.makeText(this, R.string.finish_first, Toast.LENGTH_SHORT).show();
            return;
        }
        objIndex = (objIndex + 1) % objektive.size();
        schliesseKamera();
        if (!waehleKamera()) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show();
            return;
        }
        surface.getHolder().setFixedSize(groesse.getWidth(), groesse.getHeight());   // je Kamera andere 4:3-Groesse moeglich
        if (guide != null) guide.setFocalRatio(fPx / groesse.getHeight());
        if (surfaceBereit) oeffneKamera();
        Toast.makeText(this, getString(R.string.lens_fmt, objektive.get(objIndex).name), Toast.LENGTH_SHORT).show();
    }

    // Ort fuer die Metadaten (nur wenn erlaubt und in den Einstellungen aktiv)
    private volatile double ortLat = Double.NaN, ortLon = Double.NaN;
    private volatile String ortName = "";
    private volatile String[] ortTags = new String[0];

    @SuppressWarnings("deprecation")
    private void ermittleOrt() {
        ortLat = ortLon = Double.NaN;
        ortName = "";
        ortTags = new String[0];
        if (!Settings.ortSpeichern(this)) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        android.location.Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best == null || System.currentTimeMillis() - best.getTime() > 12L * 3600 * 1000) return;   // zu alt = falscher Ort
        final double lat = best.getLatitude(), lon = best.getLongitude();
        ortLat = lat;
        ortLon = lon;
        new Thread(() -> {                                   // Ortsnamen im Hintergrund (Netzwerk) nachladen
            try {
                if (!android.location.Geocoder.isPresent()) return;
                java.util.List<android.location.Address> l = new android.location.Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (l == null || l.isEmpty()) return;
                android.location.Address a = l.get(0);
                java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
                for (String t : new String[]{a.getLocality(), a.getSubLocality(), a.getAdminArea(), a.getCountryName()})
                    if (t != null && !t.trim().isEmpty()) tags.add(t.trim());
                ortTags = tags.toArray(new String[0]);
                ortName = a.getLocality() != null ? a.getLocality() : (a.getAdminArea() != null ? a.getAdminArea() : "");
            } catch (Exception ignored) { }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        int alteBreite = maxBreite;
        ladeEinstellungen();
        if (maxBreite != alteBreite) { recreate(); return; }   // andere Kameraaufloesung -> Kamera neu einrichten
        if (guide != null) guide.setFocalRatio(fPx / groesse.getHeight());
        if (sperreKnopf != null) { aktualisiereSperreKnopf(); aktualisiereLeitKnopf(); }
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
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, R.string.no_rotation_sensor, Toast.LENGTH_LONG).show();
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
        rechenThread = new HandlerThread("rechnen");   // schwere Arbeit getrennt vom Kamera-Thread (sonst staut sich die Kamera)
        rechenThread.start();
        rechenHandler = new Handler(rechenThread.getLooper());
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
                    runOnUiThread(() -> Toast.makeText(CaptureActivity.this, getString(R.string.camera_error, err), Toast.LENGTH_LONG).show());
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

            anfrage = kamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            anfrage.addTarget(vorschau);
            anfrage.addTarget(leser.getSurface());
            anfrage.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (hatBlitz) anfrage.set(CaptureRequest.FLASH_MODE, blitzAn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            if (zoom != 1.0 && cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null)
                anfrage.set(CaptureRequest.CONTROL_ZOOM_RATIO, (float) zoom);          // 0.5 = Ultraweitwinkel
            // Schaerfe: Kantenanhebung der Kamera-Pipeline in hoechster Qualitaet
            if (hat(cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CaptureRequest.EDGE_MODE_HIGH_QUALITY))
                anfrage.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            
            // Prefer high target FPS range for short exposure times and crisp frames without motion blur
            android.util.Range<Integer>[] fpsRanges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null) {
                android.util.Range<Integer> bestRange = null;
                for (android.util.Range<Integer> r : fpsRanges) {
                    if (r.getUpper() >= 30 && (bestRange == null || r.getLower() > bestRange.getLower())) {
                        bestRange = r;
                    }
                }
                if (bestRange != null) {
                    anfrage.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange);
                }
            }

            // Weitwinkel: Verzeichnung in hoechster Qualitaet entzerren lassen (sonst biegen sich Kanten, Registrierung/Naht leiden)
            int[] dm = cc.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES);
            if (hat(dm, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY))
                anfrage.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
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
                                s.setRepeatingRequest(anfrage.build(), ergebnisCallback, kameraHandler);
                            } catch (CameraAccessException | IllegalStateException ignored) { }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> Toast.makeText(CaptureActivity.this, R.string.camera_session_failed, Toast.LENGTH_LONG).show());
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
        if (rechenThread != null) { rechenThread.quitSafely(); rechenThread = null; }
        offen.set(null);
        oeffnet = false;
    }

    // ------------------------------------------------------------------ Aufnahme

    private void starteAufnahme() {
        if (!gameYaw.hasData() || sitzung == null) {
            Toast.makeText(this, R.string.not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        if (startVerzoegert) return;
        startVerzoegert = true;
        vibriere(new long[]{0, 70});
        // Kurze Pause: Tippen und Vibration sollen das Geraet nicht mehr bewegen, wenn das erste Bild entsteht
        handler().postDelayed(this::starteAufnahmeJetzt, 350);
    }

    private boolean startVerzoegert;

    private android.os.Handler uiHandler;
    private android.os.Handler handler() {
        if (uiHandler == null) uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        return uiHandler;
    }

    @SuppressWarnings("deprecation")
    private void vibriere(long[] muster) {
        try {
            android.os.Vibrator v;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm = (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            v.vibrate(android.os.VibrationEffect.createWaveform(muster, -1));
        } catch (RuntimeException ignored) { }
    }

    private void starteAufnahmeJetzt() {
        startVerzoegert = false;
        if (!gameYaw.hasData() || sitzung == null) return;
        startYaw = gameYaw.latest();
        startKurs = hatKompass ? kompass.azimuth() : Double.NaN;
        // Sollneigung festhalten (z. B. 30 Grad nach oben fuer hohe Gebaeude): das Panorama-Fenster wird um diese Elevation
        // verschoben, bleibt aber schwerkraftausgerichtet (kein Verdrehen der Matrix noetig).
        startEl = snapElevation(gameYaw.pitch());
        ermittleOrt();
        stitcher = new StereoStitcher(groesse.getHeight(), groesse.getWidth(), fPx, stereoWinkel, deltaGrad, startYaw, true, bewegung, startEl);

        setzeSperren(true);
        starteAudio();
        startZeit = android.os.SystemClock.elapsedRealtime();
        vorherNick = Double.NaN;
        nimmtAuf = true;
        setzeAusloeser(true);
        info.setText(R.string.recording_hint);
    }

    private void setzeSperren(boolean an) {
        try {
            boolean ae = an && belichtungSperren;
            anfrage.set(CaptureRequest.CONTROL_AE_LOCK, ae);
            anfrage.set(CaptureRequest.CONTROL_AWB_LOCK, ae);
            // Fokus festhalten: kein Fokus-Pumpen und konstantes Sichtfeld (Fokus-Atmen) waehrend des Drehens.
            // AF_MODE_AUTO laesst die Linse an der letzten Position stehen, bis ein Trigger kommt.
            anfrage.set(CaptureRequest.CONTROL_AF_MODE, an && hat(cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AF_MODE_AUTO) ? CaptureRequest.CONTROL_AF_MODE_AUTO
                    : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            sitzung.setRepeatingRequest(anfrage.build(), ergebnisCallback, kameraHandler);
        } catch (CameraAccessException | IllegalStateException | NullPointerException ignored) { }
    }

    /** Wird fuer jedes Kamerabild im Kamera-Thread aufgerufen. */
    /** Merkt sich Belichtungszeit und Rolling-Shutter-Versatz, um den Aufnahmezeitpunkt genau zu bestimmen. */
    private final CameraCaptureSession.CaptureCallback ergebnisCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, android.hardware.camera2.TotalCaptureResult res) {
            Long e = res.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME);
            Long k = res.get(android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            float[] ic = res.get(android.hardware.camera2.CaptureResult.LENS_INTRINSIC_CALIBRATION);
            android.graphics.Rect aa = cc == null ? null : cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (ic != null && ic.length >= 1 && aa != null && groesse != null && aa.width() > 0)
                fPxGeraet = ic[0] * groesse.getWidth() / (double) aa.width();
            if (e != null) expNs = e;
            if (k != null) skewNs = k;
        }
    };

    private void bildDa(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            StereoStitcher s = stitcher;
            if (!nimmtAuf || s == null) return;
            // Zeitstempel = Beginn der Belichtung der ersten Zeile. Die Bildmitte (aufrecht) liegt in der Mitte
            // des Sensors: Belichtungsmitte + halber Rolling-Shutter-Versatz.
            long ts = zeitstempelEchtzeit ? img.getTimestamp() + expNs / 2 + skewNs / 2 + latenzNs : Long.MAX_VALUE;
            double yaw = gameYaw.yawAt(ts);
            float[] rot = gameYaw.rotationAt(ts);
            double psi = yaw - startYaw;
            if (Double.isNaN(psi) || rot == null) return;

            // Bewegungsfilter: Bei schnellem Nicken/Kippen ist die Lage zum Bildzeitpunkt zu ungenau
            // (Zeitfehler x Drehrate = vertikaler Versatz -> "Treppen" an waagerechten Kanten).
            double nick = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -rot[8]))));
            double kipp = Math.toDegrees(Math.atan2(rot[6], rot[7]));
            long jetztNs = android.os.SystemClock.elapsedRealtimeNanos();
            long zeit = zeitstempelEchtzeit ? ts : jetztNs;
            boolean ruhig = true;
            if (!Double.isNaN(vorherNick) && zeit > vorherTs) {
                double dt = (zeit - vorherTs) / 1e9;
                ruhig = Math.abs(nick - vorherNick) / dt <= maxNick && Math.abs(kipp - vorherKipp) / dt <= maxNick;
            }
            vorherNick = nick; vorherKipp = kipp; vorherTs = zeit;
            // unruhige Bilder werden nicht verworfen (das riss Luecken, der Kreis wurde nie fertig), sondern zaehlen weniger.
            // Das Bild wird nur kopiert und sofort freigegeben; gerechnet wird im eigenen Thread. Ist er noch beschaeftigt,
            // ersetzt das neueste Bild das wartende (die Fensterbreite des Stitchers passt sich dem Abstand an).
            Handler h = rechenHandler;
            if (h == null) return;
            Job neu = new Job(s, new YuvFrame(img, sensorOrientierung), psi, rot, ruhig ? 1.0 : 0.25);
            if (offen.getAndSet(neu) == null) h.post(this::arbeite);
        } finally {
            img.close();
        }
    }

    private static final class Job {
        final StereoStitcher s; final YuvFrame f; final double psi; final float[] rot; final double ws;
        Job(StereoStitcher s, YuvFrame f, double psi, float[] rot, double ws) { this.s = s; this.f = f; this.psi = psi; this.rot = rot; this.ws = ws; }
    }

    /** Laeuft im Rechen-Thread: Bild einsortieren, Anzeige aktualisieren, Ende erkennen. */
    private void arbeite() {
        Job j = offen.getAndSet(null);
        if (j == null || !nimmtAuf || j.s != stitcher) return;
        j.s.addFrame(j.f, j.psi, j.rot, j.ws);
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzteAnzeige > 100) {
            letzteAnzeige = jetzt;
            final double fort = j.s.progress();
            final double pitch = gameYaw.pitch() - startEl;
            final double p = j.psi;
            final double stp = j.s.stepDeg();
            final double rest = j.s.remainingDeg();
            runOnUiThread(() -> aktualisiereAnzeige(fort, pitch, p, stp, rest));
        }
        if (j.s.isComplete() || j.s.effPsi(j.psi) >= 370.0 || j.psi >= 395.0) runOnUiThread(this::beendeAufnahme);   // fertig; Notbremse bei 370 Grad
    }

    private void aktualisiereAnzeige(double fort, double pitch, double psi, double stepDeg, double rest) {
        fortschritt.setProgress((int) (fort * 1000));
        String hinweis;
        if (psi < -10) hinweis = getString(R.string.hint_turn_right);
        else if (Math.abs(pitch) > 15) hinweis = getString(R.string.hint_hold_upright, (int) Math.round(Math.abs(pitch)));
        else if (stepDeg > 0.7) hinweis = getString(R.string.hint_slower);        // wenige Bilder pro Grad -> Luecken/Streifen
        else if (fort > 0.995 && rest > 0) hinweis = getString(R.string.hint_finish, (int) Math.ceil(rest));   // fast fertig: wie weit noch
        else hinweis = getString(R.string.hint_keep_turning);
        info.setText(getString(R.string.progress_fmt, (int) Math.round(fort * 360), hinweis));
    }

    private void verwirfAufnahme() {
        nimmtAuf = false;
        if (stitcher != null) stitcher.close();
        stitcher = null;
        stopAudio();
        if (sitzung != null) setzeSperren(false);
        setzeAusloeser(false);
        fortschritt.setProgress(0);
    }

    private volatile String lastDiag = "";

    private void beendeAufnahme() {
        if (!nimmtAuf) return;
        nimmtAuf = false;
        vibriere(new long[]{0, 80, 90, 80});
        final StereoStitcher s = stitcher;
        stitcher = null;
        s.close();
        final byte[] ton = stopAudio();
        if (sitzung != null) setzeSperren(false);

        knopf.setEnabled(false);
        info.setText(R.string.saving);
        final double kurs = startKurs;
        new Thread(() -> {
            String meldung;
            try {
                Uri uri = speichere(s, ton, kurs);
                meldung = getString(R.string.saved_toast) + "\n#" + BuildInfo.NUMBER + " " + lastDiag
                        + String.format(java.util.Locale.US, " lens=%s f=%.0f/%.0f", objektive.isEmpty() ? "?" : objektive.get(objIndex).name, fPx, fPxGeraet);
            } catch (Exception | OutOfMemoryError e) {
                meldung = getString(R.string.save_error, String.valueOf(e.getMessage()));
            }
            final String m = meldung;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                knopf.setEnabled(true);
                setzeAusloeser(false);
                ladeMiniatur();
                fortschritt.setProgress(0);
                info.setText(R.string.done_again);
            });
        }).start();
    }

    // ------------------------------------------------------------------ Speichern

    private Uri speichere(StereoStitcher s, byte[] ton, double kurs) throws Exception {
        StereoStitcher.Result e = s.result(schaerfe);
        if (e.width < 100) throw new Exception(getString(R.string.too_little));

        byte[] links = jpeg(e.left, e, jpegQualitaet);
        byte[] rechts = jpeg(e.right, e, jpegQualitaet);

        VrJpgWriter.Meta m = new VrJpgWriter.Meta();
        m.fullW = e.panoW;
        m.fullH = e.panoW / 2;
        m.cropW = e.width;
        m.cropH = e.panoH;
        m.cropLeft = e.x0;
        // Fenster um die Sollneigung nach oben (oder unten) verschieben; Zeilen = echte, schwerkraftausgerichtete Elevation
        int shift = (int) Math.round(e.elCenterDeg * e.panoW / 360.0);
        m.cropTop = Math.max(0, Math.min(m.fullH - e.panoH, (m.fullH - e.panoH) / 2 - shift));
        m.author = Settings.author(this);
        java.util.List<String> tags = new java.util.ArrayList<>();
        tags.add("StereoPano");
        tags.add(BuildInfo.TAG);                      // Programmversion, mit der die Datei erzeugt wurde
        tags.add("diag:" + e.diag);                   // Kurzprotokoll der Aufnahme (Winkelkorrektur, Kreisschluss, Naht)
        lastDiag = e.diag;
        tags.addAll(java.util.Arrays.asList(ortTags));
        m.tags = tags.toArray(new String[0]);
        m.lat = ortLat;
        m.lon = ortLon;
        // Kompasskurs der Mitte des vollen Panoramas (Spalte 0 = Startrichtung)
        if (!Double.isNaN(kurs)) m.poseHeading = (kurs + 180) % 360;
        byte[] datei = VrJpgWriter.build(links, rechts, ton, m);

        String name = "PANO_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".vr.jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StereoPano");
        if (!ortName.isEmpty()) v.put(MediaStore.Images.Media.DESCRIPTION, ortName);      // Ort fuer die Galerie-Sortierung
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception(getString(R.string.mediastore_failed));
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            os.write(datei);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri;
    }

    private static byte[] jpeg(int[] pixel, StereoStitcher.Result e, int qualitaet) {
        Bitmap bmp = Bitmap.createBitmap(pixel, e.x0, e.panoW, e.width, e.panoH, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(4_000_000);
        bmp.compress(Bitmap.CompressFormat.JPEG, qualitaet, bo);
        bmp.recycle();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ Ton (wie Cardboard Camera)

    @SuppressWarnings("deprecation")
    private void starteAudio() {
        if (!audioAn) return;
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
        private final byte[] yb, ub, vb;
        private final int yRow, uRow, vRow, uPix, vPix, rawW, rawH;
        private final boolean rot90;

        private static byte[] kopie(ByteBuffer b) {
            ByteBuffer d = b.duplicate();
            d.rewind();
            byte[] a = new byte[d.remaining()];
            d.get(a);
            return a;
        }

        /** Kopiert die Ebenen (schnelles Blockkopieren), damit das Kamerabild sofort zurueckgegeben werden kann. */
        YuvFrame(Image img, int sensorOrientation) {
            Image.Plane[] p = img.getPlanes();
            yb = kopie(p[0].getBuffer());
            ub = kopie(p[1].getBuffer());
            vb = kopie(p[2].getBuffer());
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
        public int luma(int ux, int uy) {
            int x, y;
            if (rot90) { x = uy; y = rawH - 1 - ux; }
            else       { x = rawW - 1 - uy; y = ux; }
            return yb[y * yRow + x] & 0xFF;
        }

        @Override
        public int rgb(int ux, int uy) {
            int x, y;
            if (rot90) { x = uy; y = rawH - 1 - ux; }
            else       { x = rawW - 1 - uy; y = ux; }
            int yy = yb[y * yRow + x] & 0xFF;
            int cu = (ub[(y >> 1) * uRow + (x >> 1) * uPix] & 0xFF) - 128;
            int cv = (vb[(y >> 1) * vRow + (x >> 1) * vPix] & 0xFF) - 128;
            int r = clamp(yy + (int) (1.402f * cv));
            int g = clamp(yy - (int) (0.344f * cu + 0.714f * cv));
            int b = clamp(yy + (int) (1.772f * cu));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
    }
}
