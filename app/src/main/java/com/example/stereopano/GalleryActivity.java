package com.example.stereopano;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Galerie der eigenen Panoramen (Bilder/StereoPano). Antippen = Betrachter, lange druecken = Loeschen.
 * Eigene Dateien sind unter Scoped Storage ohne Zusatzberechtigung lesbar und loeschbar.
 */
public class GalleryActivity extends Activity {

    private static final String PFAD = Environment.DIRECTORY_PICTURES + "/StereoPano%";

    private static final class Item { long id; Uri uri; String name; }

    private final List<Item> items = new ArrayList<>();
    private final LruCache<Long, Bitmap> cache = new LruCache<>(48);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private BaseAdapter adapter;
    private TextView leer;

    private static Cursor abfrage(Context c) {
        return c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME},
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", new String[]{PFAD},
                MediaStore.Images.Media.DATE_ADDED + " DESC");
    }

    /** Neueste Aufnahme oder null. */
    static Uri letzteAufnahme(Context c) {
        try (Cursor cur = abfrage(c)) {
            if (cur != null && cur.moveToFirst())
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0));
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView titel = new TextView(this);
        titel.setText(R.string.gallery_title);
        titel.setTextColor(Color.WHITE);
        titel.setTextSize(20);
        titel.setPadding(32, 24, 32, 24);
        LinearLayout kopf = new LinearLayout(this);
        kopf.setOrientation(LinearLayout.HORIZONTAL);
        kopf.setGravity(Gravity.CENTER_VERTICAL);
        kopf.setPadding(0, 0, 24, 0);
        kopf.addView(titel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        float dd = getResources().getDisplayMetrics().density;
        kopf.addView(Ui.zahnrad(this), new LinearLayout.LayoutParams((int) (44 * dd), (int) (44 * dd)));
        root.addView(kopf);

        leer = new TextView(this);
        leer.setText(R.string.gallery_empty);
        leer.setTextColor(0xFFAAAAAA);
        leer.setGravity(Gravity.CENTER);
        leer.setPadding(32, 64, 32, 64);
        root.addView(leer);

        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(8);
        grid.setVerticalSpacing(8);
        grid.setPadding(8, 0, 8, 8);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return items.get(i).id; }
            @Override public View getView(int i, View v, ViewGroup p) {
                ImageView iv = v instanceof ImageView ? (ImageView) v : new ImageView(GalleryActivity.this);
                int h = (getResources().getDisplayMetrics().widthPixels / 2 - 16) / 2;    // Seitenverhaeltnis 2:1
                iv.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFF222222);
                final Item it = items.get(i);
                iv.setTag(it.id);
                Bitmap bm = cache.get(it.id);
                if (bm != null) { iv.setImageBitmap(bm); return iv; }
                iv.setImageDrawable(null);
                pool.execute(() -> {
                    try {
                        Bitmap t = getContentResolver().loadThumbnail(it.uri, new Size(640, 320), null);
                        cache.put(it.id, t);
                        runOnUiThread(() -> { if (it.id == (Long) iv.getTag()) iv.setImageBitmap(t); });
                    } catch (Exception ignored) { }
                });
                return iv;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, i, id) ->
                startActivity(new Intent(this, ViewerActivity.class).setAction(Intent.ACTION_VIEW).setData(items.get(i).uri).putExtra("von_galerie", true)));
        grid.setOnItemLongClickListener((p, v, i, id) -> { zeigeMenue(items.get(i)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        laden();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void laden() {
        new Thread(() -> {
            List<Item> neu = new ArrayList<>();
            try (Cursor c = abfrage(this)) {
                while (c != null && c.moveToNext()) {
                    Item it = new Item();
                    it.id = c.getLong(0);
                    it.name = c.getString(1);
                    it.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id);
                    neu.add(it);
                }
            }
            runOnUiThread(() -> {
                items.clear();
                items.addAll(neu);
                adapter.notifyDataSetChanged();
                leer.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void zeigeMenue(Item it) {
        String[] eintraege = {getString(R.string.menu_share), getString(R.string.menu_flat), getString(R.string.menu_sbs),
                getString(R.string.menu_anaglyph), getString(R.string.menu_delete)};
        new AlertDialog.Builder(this).setTitle(it.name).setItems(eintraege, (d, w) -> {
            if (w == 0) teilen(it);
            else if (w == 4) frageLoeschen(it);
            else exportiere(it, w - 1);
        }).show();
    }

    private void teilen(Item it) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("image/jpeg");
        s.putExtra(Intent.EXTRA_STREAM, it.uri);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, getString(R.string.share_title)));
    }

    private void exportiere(Item it, int art) {
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String m;
            try {
                Uri u = Exporter.exportiere(this, it.uri, it.name, art);
                runOnUiThread(() -> Toast.makeText(this, R.string.exported, Toast.LENGTH_LONG).show());
                return;
            } catch (Exception | OutOfMemoryError e) {
                m = getString(R.string.export_failed, String.valueOf(e.getMessage()));
            }
            final String msg = m;
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void frageLoeschen(Item it) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, it.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.menu_delete, (d, w) -> {
                    try {
                        getContentResolver().delete(it.uri, null, null);
                        cache.remove(it.id);
                        laden();
                    } catch (SecurityException e) {
                        Toast.makeText(this, R.string.delete_not_allowed, Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }
}
