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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
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
    private static final int REQ_WAHL = 31;
    private final List<GooglePhotos.Item> items = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(24, 24, 24, 24);

        TextView t = new TextView(this);
        t.setText(R.string.gp_title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        root.addView(t);

        status = new TextView(this);
        status.setTextColor(0xFFAAAAAA);
        status.setPadding(0, 8, 0, 8);
        status.setText(Settings.p(this).getString(Settings.K_GACC, "").isEmpty() ? getString(R.string.gp_no_account) : Settings.p(this).getString(Settings.K_GACC, ""));
        root.addView(status);

        Button neu = knopf(R.string.gp_refresh, v -> aktualisiere());
        Button wahl = knopf(R.string.gp_pick, v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, getString(R.string.gp_pick)), REQ_WAHL);
        });
        Button alt = knopf(R.string.gp_old, v -> {
            Settings.p(this).edit().putBoolean(Settings.K_ALL, true).apply();
            Toast.makeText(this, R.string.gp_old_on, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, GalleryActivity.class));
            finish();
        });
        root.addView(neu);
        root.addView(wahl);
        root.addView(alt);

        TextView hinweis = new TextView(this);
        hinweis.setText(R.string.gp_hint);
        hinweis.setTextColor(0xFF888888);
        hinweis.setTextSize(12);
        hinweis.setPadding(0, 8, 0, 8);
        root.addView(hinweis);

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(Color.WHITE);
                return tv;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> holeUndOeffne(items.get(pos)));
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        if (GooglePhotos.hatKonto(this)) aktualisiere();
    }

    private Button knopf(int text, View.OnClickListener l) {
        Button k = new Button(this);
        k.setText(text);
        k.setAllCaps(false);
        k.setOnClickListener(l);
        return k;
    }

    private void aktualisiere() {
        status.setText(R.string.gp_loading);
        new Thread(() -> {
            try {
                List<GooglePhotos.Item> l = GooglePhotos.auflisten(this);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(l);
                    adapter.clear();
                    for (GooglePhotos.Item it : l) adapter.add(it.name + (it.time.isEmpty() ? "" : "   " + it.time.replace("T", " ").replace("Z", "")));
                    status.setText(getString(R.string.gp_count, l.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(getString(R.string.gp_failed, String.valueOf(e.getMessage()))));
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
        if (req != REQ_WAHL || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri src = data.getData();
        new Thread(() -> {
            try {
                String name = "IMPORT.vr.jpg";
                try (Cursor c = getContentResolver().query(src, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (i >= 0 && c.getString(i) != null) name = c.getString(i);
                    }
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000);
                try (InputStream in = getContentResolver().openInputStream(src)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                Uri u = vorhanden(name);
                if (u == null) u = importiere(bo.toByteArray(), name);
                oeffne(u);
            } catch (Exception | OutOfMemoryError e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.gp_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show());
            }
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
