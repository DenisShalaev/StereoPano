package com.example.stereopano;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Druckvorbereitung fuer Linsenraster (Lentikular): Papierformat A4/A5/A6, Raster 75/100 LPI, Druckerauflosung,
 * mindestens 8 Ansichten. Aus linkem/rechtem Auge werden Zwischenansichten berechnet und streifenweise verschachtelt
 * (siehe LentiCore). Ausgabe: PNG mit DPI-Angabe nach Bilder/StereoPano/Lenticular.
 */
public class LentiActivity extends Activity {
    private static final int BG = 0xFF0F172A, KARTE = 0xFF1E293B, RAND = 0xFF334155;
    private static final int TEXT = Color.WHITE, GRAU = 0xFF94A3B8, AKZENT = 0xFF4FC3F7;
    private static final double[][] FORMAT_MM = {{297, 210}, {210, 148}, {148, 105}};   // A4, A5, A6 (quer: lang x kurz)
    private static final String[] FORMAT_NAME = {"A4", "A5", "A6"};
    private static final int[] LPI = {75, 100};
    private static final int[] DPI = {600, 800, 1200};

    private SharedPreferences sp;
    private Uri src;
    private byte[] daten;
    private VrJpgReader.Result meta;
    private Bitmap vorschauQuelle;
    private int srcW, srcH;
    private boolean ring;                     // volle 360 Grad (Fenster darf ueber die Naht laufen)
    private double ppd;                       // Pixel pro Grad im Quellbild

    private int lpiIdx, fmt, dpiIdx, views, depthPct, pos;
    private double lpiReal;
    private boolean portrait, reverse, marks;

    private ImageView vorschau;
    private TextView posText, info, status;
    private LinearLayout formular;
    private EditText lpiEdit;
    private Button machen, test, abbruch;
    private ProgressBar balken;
    private volatile boolean laeuft, abbrechen;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("lenti", MODE_PRIVATE);
        src = getIntent().getData();
        if (src == null) { finish(); return; }
        try {
            laden();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.lenti_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        lpiIdx = sp.getInt("lpi", 0);
        lpiReal = Double.longBitsToDouble(sp.getLong("lpiReal" + lpiIdx, Double.doubleToLongBits(LPI[lpiIdx])));
        fmt = sp.getInt("fmt", 0);
        dpiIdx = sp.getInt("dpi", 0);
        views = sp.getInt("views", 8);
        depthPct = sp.getInt("depth", 100);
        portrait = sp.getBoolean("portrait", false);
        reverse = sp.getBoolean("reverse", true);
        marks = sp.getBoolean("marks", true);
        pos = sp.getInt("pos", 500);
        bauen();
        aktualisiere();
    }

    private void laden() throws Exception {
        try (InputStream in = getContentResolver().openInputStream(src)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            daten = bo.toByteArray();
        }
        meta = VrJpgReader.parse(daten);
        if (meta.rightJpeg == null) throw new Exception(getString(R.string.export_no_stereo));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(daten, 0, daten.length, o);
        srcW = o.outWidth;
        srcH = o.outHeight;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = 4;
        vorschauQuelle = BitmapFactory.decodeByteArray(daten, 0, daten.length, o2);
        if (vorschauQuelle == null || srcW <= 0) throw new Exception(getString(R.string.export_unreadable));
        ring = meta.fullW <= 0 || meta.cropW >= meta.fullW * 0.98;
        ppd = (meta.fullW > 0 ? meta.fullW : srcW) / 360.0;
    }

    // ------------------------------------------------------------------ Oberflaeche

    private void bauen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(root);
        Ui.applyInsets(sv);

        TextView titel = text(getString(R.string.lenti_title), 20, TEXT);
        root.addView(titel);

        vorschau = new ImageView(this);
        vorschau.setAdjustViewBounds(true);
        vorschau.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vorschau.setBackgroundColor(0xFF000000);
        root.addView(vorschau, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        posText = text("", 13, GRAU);
        root.addView(posText);
        SeekBar sb = new SeekBar(this);
        sb.setMax(1000);
        sb.setProgress(pos);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { pos = p; aktualisiereVorschau(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sp.edit().putInt("pos", pos).apply(); }
        });
        root.addView(sb);

        formular = new LinearLayout(this);
        formular.setOrientation(LinearLayout.VERTICAL);
        root.addView(formular);

        status = text("", 14, AKZENT);
        status.setPadding(0, dp(10), 0, dp(4));
        root.addView(status);
        balken = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        balken.setMax(1000);
        balken.setVisibility(View.GONE);
        root.addView(balken);

        machen = knopf(getString(R.string.lenti_make), v -> starte(false));
        test = knopf(getString(R.string.lenti_test), v -> starte(true));
        abbruch = knopf(getString(R.string.lenti_cancel), v -> abbrechen = true);
        abbruch.setVisibility(View.GONE);
        root.addView(machen);
        root.addView(test);
        root.addView(abbruch);
        setContentView(sv);
    }

    /** Formular neu aufbauen (Auswahlen aendern sich gegenseitig). */
    private void aktualisiere() {
        formular.removeAllViews();
        formular.addView(ueberschrift(getString(R.string.lenti_lpi)));
        formular.addView(segment(new String[]{"75 LPI", "100 LPI"}, lpiIdx, i -> {
            lpiIdx = i;
            lpiReal = Double.longBitsToDouble(sp.getLong("lpiReal" + i, Double.doubleToLongBits(LPI[i])));
            sp.edit().putInt("lpi", i).apply();
            aktualisiere();
        }));
        formular.addView(ueberschrift(getString(R.string.lenti_lpi_real)));
        lpiEdit = new EditText(this);
        lpiEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        lpiEdit.setTextColor(TEXT);
        lpiEdit.setText(String.format(Locale.US, "%.3f", lpiReal));
        lpiEdit.setOnFocusChangeListener((v, f) -> { if (!f) uebernehmeLpi(); });
        lpiEdit.setOnEditorActionListener((v, a, e) -> { uebernehmeLpi(); return false; });
        formular.addView(lpiEdit);
        LinearLayout best = new LinearLayout(this);
        best.setOrientation(LinearLayout.HORIZONTAL);
        best.addView(text(getString(R.string.lenti_test_best) + "  ", 13, GRAU));
        for (int i = 1; i <= 9; i++) {
            final int row = i;
            TextView t = text(String.valueOf(i), 15, AKZENT);
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            t.setOnClickListener(v -> {
                lpiReal = LPI[lpiIdx] * (1 + (row - 5) * 0.0025);
                sp.edit().putLong("lpiReal" + lpiIdx, Double.doubleToLongBits(lpiReal)).apply();
                aktualisiere();
            });
            best.addView(t);
        }
        formular.addView(best);

        formular.addView(ueberschrift(getString(R.string.lenti_format)));
        formular.addView(segment(FORMAT_NAME, fmt, i -> { fmt = i; sp.edit().putInt("fmt", i).apply(); aktualisiere(); }));
        formular.addView(check(getString(R.string.lenti_portrait), portrait, c -> { portrait = c; sp.edit().putBoolean("portrait", c).apply(); aktualisiere(); }));

        formular.addView(ueberschrift(getString(R.string.lenti_dpi)));
        String[] dn = new String[DPI.length];
        for (int i = 0; i < DPI.length; i++) dn[i] = String.valueOf(DPI[i]);
        formular.addView(segment(dn, dpiIdx, i -> { dpiIdx = i; sp.edit().putInt("dpi", i).apply(); aktualisiere(); }));

        double period = DPI[dpiIdx] / lpiReal;
        formular.addView(text(String.format(Locale.US, getString(R.string.lenti_px_per_lens), period), 13, GRAU));
        int maxViews = (int) Math.floor(period + 1e-9);
        List<Integer> options = new ArrayList<>();
        for (int n = 8; n <= Math.min(maxViews, 24); n += (n < 12 ? 2 : 4)) options.add(n);
        if (options.isEmpty()) {
            TextView w = text(String.format(Locale.US, getString(R.string.lenti_warn_views), DPI[dpiIdx], lpiReal, period), 14, 0xFFFFB74D);
            w.setPadding(0, dp(6), 0, dp(6));
            formular.addView(w);
        } else {
            if (!options.contains(views)) views = options.get(0);
            formular.addView(ueberschrift(getString(R.string.lenti_views)));
            String[] vn = new String[options.size()];
            int sel = 0;
            for (int i = 0; i < vn.length; i++) { vn[i] = String.valueOf(options.get(i)); if (options.get(i) == views) sel = i; }
            formular.addView(segment(vn, sel, i -> { views = options.get(i); sp.edit().putInt("views", views).apply(); aktualisiere(); }));
        }
        machen.setEnabled(!options.isEmpty() && !laeuft);
        test.setEnabled(!laeuft);

        formular.addView(ueberschrift(getString(R.string.lenti_depth) + ": " + depthPct + " %"));
        SeekBar ds = new SeekBar(this);
        ds.setMax(150);
        ds.setProgress(depthPct);
        ds.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { depthPct = Math.max(10, p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sp.edit().putInt("depth", depthPct).apply(); aktualisiere(); }
        });
        formular.addView(ds);
        formular.addView(check(getString(R.string.lenti_marks), marks, c -> { marks = c; sp.edit().putBoolean("marks", c).apply(); aktualisiere(); }));
        formular.addView(check(getString(R.string.lenti_flip), reverse, c -> { reverse = c; sp.edit().putBoolean("reverse", c).apply(); }));

        int[] px = druckPixel();
        formular.addView(text(String.format(Locale.US, getString(R.string.lenti_hint), px[0], px[1]), 13, GRAU));
        aktualisiereVorschau();
    }

    private void uebernehmeLpi() {
        try {
            double v = Double.parseDouble(lpiEdit.getText().toString().replace(',', '.'));
            if (v >= 30 && v <= 200) {
                lpiReal = v;
                sp.edit().putLong("lpiReal" + lpiIdx, Double.doubleToLongBits(v)).apply();
            }
        } catch (NumberFormatException ignored) { }
    }

    /** Randbreiten fuer die Ausrichtungsstreifen: {seitlich, oben/unten, Luecke} in Pixeln (alles 0 ohne Streifen). */
    private int[] rand() {
        if (!marks) return new int[]{0, 0, 0};
        int dpi = DPI[dpiIdx];
        return new int[]{(int) Math.round(7 / 25.4 * dpi), (int) Math.round(5 / 25.4 * dpi), (int) Math.round(1.2 / 25.4 * dpi)};
    }

    private int[] druckPixel() {
        double[] mm = FORMAT_MM[fmt];
        double wmm = portrait ? mm[1] : mm[0], hmm = portrait ? mm[0] : mm[1];
        return new int[]{(int) Math.round(wmm / 25.4 * DPI[dpiIdx]), (int) Math.round(hmm / 25.4 * DPI[dpiIdx])};
    }

    /** Fenster der Panorama in Quellpixeln: {links, oben, breite, hoehe}. */
    private int[] fenster() {
        double[] mm = FORMAT_MM[fmt];
        int[] pxs = druckPixel(), rd = rand();
        double aspect = (pxs[0] - 2.0 * rd[0]) / (pxs[1] - 2.0 * rd[1]);
        int wh = srcH, ww = (int) Math.round(aspect * wh);
        if (ww > srcW) { ww = srcW; wh = (int) Math.round(ww / aspect); }
        ww &= ~1; wh &= ~1;
        int top = (srcH - wh) / 2;
        double f = pos / 1000.0;
        int left = ring ? (int) Math.round(f * srcW) : (int) Math.round(f * (srcW - ww));
        return new int[]{left, top, ww, wh};
    }

    private void aktualisiereVorschau() {
        if (vorschau == null || vorschauQuelle == null) return;
        int[] fw = fenster();
        double k = vorschauQuelle.getWidth() / (double) srcW;
        int pw = 720, ph = Math.max(1, (int) Math.round(pw * fw[3] / (double) fw[2]));
        Bitmap bm = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        double sc = pw / (double) fw[2];
        int sl = (int) Math.round(fw[0] * k), st = (int) Math.round(fw[1] * k), sw = (int) Math.round(fw[2] * k), sh = (int) Math.round(fw[3] * k);
        int vw = vorschauQuelle.getWidth();
        if (sl + sw <= vw) {
            cv.drawBitmap(vorschauQuelle, new Rect(sl, st, sl + sw, st + sh), new Rect(0, 0, pw, ph), null);
        } else {
            int a = vw - sl;                                         // Teil bis zum Rand, Rest vom Anfang
            int dwA = (int) Math.round(a / (double) sw * pw);
            cv.drawBitmap(vorschauQuelle, new Rect(sl, st, vw, st + sh), new Rect(0, 0, dwA, ph), null);
            cv.drawBitmap(vorschauQuelle, new Rect(0, st, sw - a, st + sh), new Rect(dwA, 0, pw, ph), null);
        }
        vorschau.setImageBitmap(bm);
        double centre = ((fw[0] + fw[2] / 2.0) / srcW) * 360.0 % 360.0;
        posText.setText(String.format(Locale.US, getString(R.string.lenti_window), (int) Math.round(centre)));
    }

    // ------------------------------------------------------------------ Erzeugen

    private void starte(boolean testBlatt) {
        if (laeuft) return;
        uebernehmeLpi();
        laeuft = true;
        abbrechen = false;
        setBusy(true);
        final int[] px = druckPixel();
        final int dpi = DPI[dpiIdx];
        final double lpi = lpiReal;
        final int n = views;
        final boolean rev = reverse;
        final double depth = depthPct / 100.0;
        final int[] fw = fenster();
        final String basis = getIntent().getStringExtra("name") != null ? getIntent().getStringExtra("name") : "pano";
        final String name = basis.replaceAll("(?i)\\.vr\\.jpg$|(?i)\\.jpe?g$", "") + (testBlatt ? "_lenti_test_" : "_lenti_")
                + FORMAT_NAME[fmt] + (portrait ? "p" : "") + "_" + LPI[lpiIdx] + "lpi_" + dpi + "dpi" + (testBlatt ? "" : "_" + n + "v") + ".png";
        new Thread(() -> {
            Uri ziel = null;
            try {
                ContentValues cvv = new ContentValues();
                cvv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cvv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cvv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StereoPano/Lenticular");
                cvv.put(MediaStore.Images.Media.IS_PENDING, 1);
                ziel = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cvv);
                if (ziel == null) throw new Exception(getString(R.string.mediastore_failed));
                try (OutputStream os = new BufferedOutputStream(getContentResolver().openOutputStream(ziel), 1 << 16)) {
                    if (testBlatt) {
                        LentiCore.testSheet(px[0], px[1], dpi, lpi, n, rev, os);
                    } else {
                        runOnUiThread(() -> status.setText(R.string.lenti_prepare));
                        int[] wl = fensterPixel(daten, fw), wr = fensterPixel(meta.rightJpeg, fw);
                        LentiCore.Params p = new LentiCore.Params();
                        int[] rd = rand();
                        p.marginX = rd[0]; p.marginY = rd[1]; p.gap = rd[2];
                        p.outW = px[0]; p.outH = px[1]; p.dpi = dpi; p.lpi = lpi; p.views = n; p.reverse = rev; p.depth = depth;
                        int maxD = (int) Math.round(6.0 * ppd);
                        LentiCore.render(wl, wr, fw[2], fw[3], maxD, p, os, f -> {
                            final int pr = (int) (f * 1000);
                            runOnUiThread(() -> { balken.setProgress(pr); status.setText(getString(R.string.lenti_saving, pr / 10)); });
                            return !abbrechen;
                        });
                    }
                }
                cvv.clear();
                cvv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(ziel, cvv, null, null);
                final String fn = name;
                runOnUiThread(() -> { status.setText(testBlatt ? getString(R.string.lenti_test_done) : getString(R.string.lenti_saved, fn)); fertig(); });
            } catch (java.util.concurrent.CancellationException c) {
                if (ziel != null) getContentResolver().delete(ziel, null, null);
                runOnUiThread(() -> { status.setText(""); fertig(); });
            } catch (Throwable e) {
                if (ziel != null) getContentResolver().delete(ziel, null, null);
                final String m = String.valueOf(e instanceof OutOfMemoryError ? "OutOfMemory" : e.getMessage());
                runOnUiThread(() -> { status.setText(getString(R.string.lenti_failed, m)); fertig(); });
            }
        }, "lenti").start();
    }

    /** Fensterpixel (ARGB) eines JPEG; das Fenster darf bei vollem Kreis ueber die Naht laufen. */
    private int[] fensterPixel(byte[] jpeg, int[] fw) {
        Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bm == null) throw new IllegalStateException(getString(R.string.export_unreadable));
        try {
            int ww = fw[2], wh = fw[3], bw = bm.getWidth();
            int[] out = new int[ww * wh];
            for (int y = 0; y < wh; y++) {
                int left = fw[0];
                if (left + ww <= bw) {
                    bm.getPixels(out, y * ww, ww, left, fw[1] + y, ww, 1);
                } else {
                    int a = bw - left;
                    bm.getPixels(out, y * ww, ww, left, fw[1] + y, a, 1);
                    bm.getPixels(out, y * ww + a, ww, 0, fw[1] + y, ww - a, 1);
                }
            }
            return out;
        } finally {
            bm.recycle();
        }
    }

    private void setBusy(boolean b) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        balken.setVisibility(b ? View.VISIBLE : View.GONE);
        balken.setProgress(0);
        abbruch.setVisibility(b ? View.VISIBLE : View.GONE);
        machen.setEnabled(!b);
        test.setEnabled(!b);
    }

    private void fertig() {
        laeuft = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setBusy(false);
        aktualisiere();
    }

    // ------------------------------------------------------------------ kleine Bausteine

    private int dp(int v) { return Math.round(v * d); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView ueberschrift(String s) {
        TextView t = text(s, 15, TEXT);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private Button knopf(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View check(String s, boolean v, java.util.function.Consumer<Boolean> cb) {
        android.widget.CheckBox c = new android.widget.CheckBox(this);
        c.setText(s);
        c.setTextColor(TEXT);
        c.setChecked(v);
        c.setOnCheckedChangeListener((b, on) -> cb.accept(on));
        return c;
    }

    private View segment(String[] labels, int sel, IntConsumer cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = text(labels[i], 15, i == sel ? Color.BLACK : TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            g.setColor(i == sel ? AKZENT : KARTE);
            g.setStroke(dp(1), RAND);
            t.setBackground(g);
            t.setOnClickListener(v -> cb.accept(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            row.addView(t, lp);
        }
        return row;
    }
}
