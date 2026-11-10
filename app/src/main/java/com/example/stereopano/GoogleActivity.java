package com.example.stereopano;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Fotos: eigene hochgeladene Panoramen auflisten und auf das Telefon holen (danach Betrachter/Druck wie gewohnt),
 * beliebige Fotos ueber die System-Auswahl importieren (auch alte Cardboard-Camera-Dateien) und alte
 * .vr.jpg-Dateien auf dem Telefon in der Galerie einblenden.
 */
public class GoogleActivity extends Activity {
    private static final int REQ_WAHL = 31, REQ_ZIP = 32;
    private final List<GooglePhotos.Item> items = new ArrayList<>();
    private TextView status;

    private static final int BG = 0xFF0F172A, KARTE = 0xFF1E293B, RAND = 0xFF334155, TEXT = Color.WHITE, GRAU = 0xFF94A3B8, AKZENT = 0xFF4FC3F7;
    private float d;
    private LinearLayout liste;

    private int dp(int v) { return (int) (v * d + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout kopf = new LinearLayout(this);
        kopf.setOrientation(LinearLayout.HORIZONTAL);
        kopf.setGravity(Gravity.CENTER_VERTICAL);
        kopf.setPadding(dp(8), dp(8), dp(16), dp(8));
        TextView zurueck = new TextView(this);
        zurueck.setText("\u2190");
        zurueck.setTextSize(24);
        zurueck.setTextColor(TEXT);
        zurueck.setGravity(Gravity.CENTER);
        zurueck.setContentDescription(getString(R.string.cd_back));
        zurueck.setOnClickListener(v -> finish());
        kopf.addView(zurueck, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = new TextView(this);
        t.setText(R.string.gp_title);
        t.setTextSize(20);
        t.setTextColor(TEXT);
        t.setPadding(dp(12), 0, 0, 0);
        kopf.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(kopf);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        liste.setPadding(dp(16), 0, dp(16), dp(24));
        sv.addView(liste);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.applyInsets(root);

        String konto = Settings.p(this).getString(Settings.K_GACC, "");
        status = new TextView(this);
        status.setTextColor(AKZENT);
        status.setTextSize(13);
        status.setPadding(dp(4), dp(4), dp(4), dp(12));
        status.setText(konto.isEmpty() ? getString(R.string.gp_no_account) : konto);
        liste.addView(status);

        zeile(getString(R.string.gp_refresh), null, v -> aktualisiere());
        zeile(getString(R.string.gp_pick), null, v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, getString(R.string.gp_pick)), REQ_WAHL);
        });
        zeile(getString(R.string.gp_zip), null, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
            startActivityForResult(i, REQ_ZIP);
        });
        zeile(getString(R.string.gp_old), null, v -> {
            Settings.p(this).edit().putBoolean(Settings.K_ALL, true).apply();
            Toast.makeText(this, R.string.gp_old_on, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, GalleryActivity.class));
            finish();
        });

        TextView hinweis = new TextView(this);
        hinweis.setText(R.string.gp_hint);
        hinweis.setTextColor(GRAU);
        hinweis.setTextSize(12);
        hinweis.setPadding(dp(4), dp(8), dp(4), dp(8));
        liste.addView(hinweis);

        itemsBox = new LinearLayout(this);
        itemsBox.setOrientation(LinearLayout.VERTICAL);
        liste.addView(itemsBox);
        if (GooglePhotos.hatKonto(this)) aktualisiere();
    }

    private LinearLayout itemsBox;

    private android.graphics.drawable.GradientDrawable rund() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(KARTE);
        g.setStroke(dp(1), RAND);
        return g;
    }

    /** Karte im Stil der Einstellungen; Antippen fuehrt die Aktion aus. */
    private LinearLayout zeile(String titel, String info, View.OnClickListener l) {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding(dp(16), dp(12), dp(16), dp(12));
        k.setBackground(rund());
        TextView a = new TextView(this);
        a.setText(titel);
        a.setTextColor(TEXT);
        a.setTextSize(16);
        k.addView(a);
        if (info != null) {
            TextView i = new TextView(this);
            i.setText(info);
            i.setTextColor(GRAU);
            i.setTextSize(12);
            i.setPadding(0, dp(2), 0, 0);
            k.addView(i);
        }
        k.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        (itemsBox != null && l == null ? itemsBox : liste).addView(k, lp);
        return k;
    }

    private void zeigeItems() {
        itemsBox.removeAllViews();
        for (GooglePhotos.Item it : items) {
            LinearLayout k = new LinearLayout(this);
            k.setOrientation(LinearLayout.VERTICAL);
            k.setPadding(dp(16), dp(12), dp(16), dp(12));
            k.setBackground(rund());
            TextView a = new TextView(this);
            a.setText(it.name);
            a.setTextColor(TEXT);
            a.setTextSize(16);
            k.addView(a);
            if (!it.time.isEmpty()) {
                TextView i = new TextView(this);
                i.setText(it.time.replace("T", " ").replace("Z", ""));
                i.setTextColor(GRAU);
                i.setTextSize(12);
                k.addView(i);
            }
            k.setOnClickListener(v -> new Ui.Dlg(this).setTitle(it.name).setItems(new String[]{
                    getString(R.string.gp_m_open), getString(R.string.gp_m_link), getString(R.string.gp_m_check)}, (dlg, w) -> {
                if (w == 0) holeUndOeffne(it);
                else if (w == 1) link(it);
                else pruefe(it);
            }).show());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            itemsBox.addView(k, lp);
        }
    }

    private String fehler(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("UnregisteredOnApiConsole") || m.contains("Unregistered"))
            return getString(R.string.gp_unreg);
        return getString(R.string.gp_failed, m);
    }

    private void aktualisiere() {
        status.setText(R.string.gp_loading);
        new Thread(() -> {
            try {
                List<GooglePhotos.Item> l = GooglePhotos.auflisten(this);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(l);
                    zeigeItems();
                    status.setText(getString(R.string.gp_count, l.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(fehler(e)));
            }
        }).start();
    }

    private void link(GooglePhotos.Item it) {
        status.setText(R.string.gp_loading);
        new Thread(() -> {
            try {
                String url = GooglePhotos.linkTeilen(this, it);
                runOnUiThread(() -> {
                    status.setText(url);
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
                    startActivity(Intent.createChooser(s, getString(R.string.share_title)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(fehler(e)));
            }
        }).start();
    }

    private void pruefe(GooglePhotos.Item it) {
        status.setText(R.string.gp_downloading);
        new Thread(() -> {
            try {
                byte[] g = GooglePhotos.laden(it);
                VrJpgReader.Result r = VrJpgReader.parse(g);
                String m = getString(R.string.gp_check_result, r.rightJpeg != null ? "\u2713" : "\u2717", r.audio != null ? "\u2713" : "\u2717", "-", g.length / 1024);
                runOnUiThread(() -> status.setText(m));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(fehler(e)));
            }
        }).start();
    }

    private void holeUndOeffne(GooglePhotos.Item it) {
        Toast.makeText(this, R.string.gp_downloading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Uri u = vorhanden(it.name);
                if (u == null) u = importiere(GooglePhotos.laden(it), it.name);
                oeffne(u);
            } catch (Exception | OutOfMemoryError e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.gp_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_ZIP && data.getData() != null) { importZip(data.getData()); return; }
        if (req != REQ_WAHL) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        status.setText(R.string.gp_loading);
        new Thread(() -> {
            int ok = 0, stereo = 0;
            Uri last = null;
            String fehler = null;
            for (Uri src : uris) {
                try {
                    String name = "IMPORT_" + System.currentTimeMillis() + ".vr.jpg";
                    try (Cursor c = getContentResolver().query(src, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (i >= 0 && c.getString(i) != null) name = c.getString(i);
                        }
                    }
                    byte[] daten = lies(getContentResolver().openInputStream(src));
                    Uri u = vorhanden(name);
                    if (u == null) u = importiere(daten, name);
                    last = u;
                    ok++;
                    if (VrJpgReader.parse(daten).rightJpeg != null) stereo++;
                } catch (Exception | OutOfMemoryError e) {
                    fehler = String.valueOf(e.getMessage());
                }
            }
            final int fok = ok, fst = stereo;
            final Uri fl = last;
            final String ff = fehler;
            runOnUiThread(() -> {
                status.setText(getString(R.string.gp_import_done, fok, fst) + (ff != null ? "\n" + getString(R.string.gp_failed, ff) : ""));
                if (fok == 1 && fl != null) oeffne(fl);
            });
        }).start();
    }

    private static byte[] lies(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000);
        try (InputStream i = in) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    /** Alle JPEG-Dateien eines ZIP-Archivs (z. B. heruntergeladenes Google-Fotos-Album oder Takeout) in den eigenen Ordner uebernehmen. */
    private void importZip(Uri zip) {
        status.setText(R.string.gp_loading);
        new Thread(() -> {
            int ok = 0, stereo = 0, flach = 0, schon = 0;
            String fehler = null;
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(zip))) {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    String low = n.toLowerCase(java.util.Locale.ROOT);
                    if (e.isDirectory() || !(low.endsWith(".jpg") || low.endsWith(".jpeg"))) continue;
                    String name = n.substring(n.lastIndexOf('/') + 1);
                    try {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000);
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = zin.read(buf)) > 0) {
                            bo.write(buf, 0, k);
                            if (bo.size() > 120_000_000) throw new Exception("too large: " + name);
                        }
                        byte[] daten = bo.toByteArray();
                        if (vorhanden(name) != null) { schon++; continue; }
                        importiere(daten, name);
                        ok++;
                        if (VrJpgReader.parse(daten).rightJpeg != null) stereo++; else flach++;
                    } catch (Exception | OutOfMemoryError x) {
                        fehler = String.valueOf(x.getMessage());
                    }
                    final int cnt = ok;
                    runOnUiThread(() -> status.setText(getString(R.string.gp_loading) + " " + cnt));
                }
            } catch (Exception x) {
                fehler = String.valueOf(x.getMessage());
            }
            final int fok = ok, fst = stereo, ffl = flach, fsc = schon;
            final String ff = fehler;
            runOnUiThread(() -> status.setText(getString(R.string.gp_zip_done, fok, fst, ffl, fsc) + (ff != null ? "\n" + getString(R.string.gp_failed, ff) : "")));
        }).start();
    }

    private void oeffne(Uri u) {
        runOnUiThread(() -> startActivity(new Intent(this, ViewerActivity.class)
                .setAction(Intent.ACTION_VIEW).setData(u).putExtra("von_galerie", true)));
    }

    private Uri vorhanden(String name) {
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{name, Environment.DIRECTORY_PICTURES + "/StereoPano%"}, null)) {
            if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
        }
        return null;
    }

    /** Legt die Datei im eigenen Ordner Pictures/StereoPano ab (dort erscheint sie in der Galerie, Druck inklusive). */
    private Uri importiere(byte[] daten, String name) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StereoPano");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception(getString(R.string.mediastore_failed));
        try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(daten); }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri;
    }
}
