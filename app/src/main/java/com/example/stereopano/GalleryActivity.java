package com.example.stereopano;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Galerie der Panoramen. Standard: nur der eigene Ordner (Bilder/StereoPano). Optional (Einstellungen): alle ".vr.jpg"
 * auf dem Geraet, auch von der alten Cardboard Camera - dafuer ist eine Medien-Leseberechtigung noetig.
 * Sortierung (Datum, Name, Ort, Groesse) laeuft im Arbeitsspeicher; der Ort steht in der MediaStore-Beschreibung.
 * Antippen = Betrachter, lange druecken = Menue (teilen, exportieren, loeschen).
 */
public class GalleryActivity extends Activity {

    private static final String PFAD = Environment.DIRECTORY_PICTURES + "/StereoPano%";
    private static final int REQ_BERECHTIGUNG = 11, REQ_LOESCHEN = 12;

    private static final class Item {
        long id, datum, groesse;
        Uri uri;
        String name, ort, autor = "";
    }

    /** Zeilen der Liste: String = Gruppenueberschrift, Item[2] = zwei Miniaturen nebeneinander. */
    private final List<Object> zeilen = new ArrayList<>();
    private final LruCache<Long, Bitmap> cache = new LruCache<>(48);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private BaseAdapter adapter;
    private TextView leer;
    private int sortModus;
    private boolean berechtigungAngefragt;
    private final java.util.Set<Long> gewaehlt = new java.util.HashSet<>();
    private boolean auswahl;
    private LinearLayout auswahlLeiste;
    private TextView auswahlZahl;

    private static String leseRecht() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private static boolean alleErlaubt(Context c) {
        return Settings.alleAnzeigen(c) && c.checkSelfPermission(leseRecht()) == PackageManager.PERMISSION_GRANTED;
    }

    private static Cursor abfrage(Context c) {
        String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {PFAD};
        if (alleErlaubt(c)) {                         // zusaetzlich alle .vr.jpg im Geraetespeicher (alte Pixel-/Cardboard-Panoramen)
            sel = "(" + sel + " OR " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?)";
            args = new String[]{PFAD, "%.vr.jpg"};
        }
        return c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DESCRIPTION},
                sel, args, null);
    }

    /** Neueste Aufnahme im eigenen Ordner oder null. */
    static Uri letzteAufnahme(Context c) {
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", new String[]{PFAD},
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cur != null && cur.moveToFirst())
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0));
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sortModus = Settings.p(this).getInt(Settings.K_SORT, 0);

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
        int sz = (int) (44 * dd);
        ImageView sortKnopf = Ui.runderKnopf(this, R.drawable.ic_sort, R.string.cd_sort);
        sortKnopf.setOnClickListener(v -> zeigeSortierMenue());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
        slp.rightMargin = (int) (8 * dd);
        ImageView gKnopf = Ui.runderKnopf(this, R.drawable.ic_cloud, R.string.gp_button);
        gKnopf.setOnClickListener(v -> startActivity(new Intent(this, GoogleActivity.class)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(sz, sz);
        glp.rightMargin = (int) (8 * dd);
        kopf.addView(gKnopf, glp);
        kopf.addView(sortKnopf, slp);
        kopf.addView(Ui.zahnrad(this), new LinearLayout.LayoutParams(sz, sz));
        root.addView(kopf);

        auswahlLeiste = new LinearLayout(this);
        auswahlLeiste.setOrientation(LinearLayout.HORIZONTAL);
        auswahlLeiste.setGravity(Gravity.CENTER_VERTICAL);
        auswahlLeiste.setBackgroundColor(0xFF1E2A33);
        auswahlLeiste.setPadding(24, 8, 16, 8);
        auswahlZahl = new TextView(this);
        auswahlZahl.setTextColor(Color.WHITE);
        auswahlZahl.setTextSize(16);
        auswahlLeiste.addView(auswahlZahl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button alle = Ui.pille(this, R.string.select_all, false);
        alle.setOnClickListener(v -> {
            for (Object z : zeilen) if (z instanceof Item[]) for (Item it : (Item[]) z) if (it != null) gewaehlt.add(it.id);
            aktualisiereAuswahl();
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = 8;
        auswahlLeiste.addView(alle, pl);
        android.widget.Button del = Ui.pille(this, R.string.actions, true);
        del.setOnClickListener(v -> zeigeAuswahlMenue());
        auswahlLeiste.addView(del, pl);
        android.widget.Button abbr = Ui.pille(this, R.string.cancel, false);
        abbr.setOnClickListener(v -> beendeAuswahl());
        auswahlLeiste.addView(abbr, pl);
        auswahlLeiste.setVisibility(View.GONE);
        root.addView(auswahlLeiste);

        leer = new TextView(this);
        leer.setText(R.string.gallery_empty);
        leer.setTextColor(0xFFAAAAAA);
        leer.setGravity(Gravity.CENTER);
        leer.setPadding(32, 64, 32, 64);
        root.addView(leer);

        ListView grid = new ListView(this);
        grid.setDivider(null);
        grid.setPadding(8, 0, 8, 8);
        grid.setClipToPadding(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return zeilen.size(); }
            @Override public Object getItem(int i) { return zeilen.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return zeilen.get(i) instanceof String ? 0 : 1; }
            @Override public boolean isEnabled(int i) { return false; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Object z = zeilen.get(i);
                if (z instanceof String) {
                    TextView t = v instanceof TextView ? (TextView) v : new TextView(GalleryActivity.this);
                    t.setText((String) z);
                    t.setTextColor(0xFF4FC3F7);
                    t.setTextSize(15);
                    t.setPadding(12, i == 0 ? 8 : 28, 12, 8);
                    return t;
                }
                Item[] paar = (Item[]) z;
                LinearLayout r = v instanceof LinearLayout ? (LinearLayout) v : null;
                if (r == null) {
                    r = new LinearLayout(GalleryActivity.this);
                    r.setOrientation(LinearLayout.HORIZONTAL);
                    for (int k = 0; k < 2; k++) {
                        ImageView iv = new SymbolView(GalleryActivity.this);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setBackgroundColor(0xFF222222);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                        lp.setMargins(4, 4, 4, 4);
                        r.addView(iv, lp);
                    }
                }
                int h = (getResources().getDisplayMetrics().widthPixels / 2 - 16) / 2 + 8;    // Seitenverhaeltnis 2:1
                r.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
                for (int k = 0; k < 2; k++) bindeMiniatur((ImageView) r.getChildAt(k), paar[k]);
                return r;
            }
        };
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Alle .vr.jpg gewuenscht, aber noch keine Leseberechtigung: einmal anfragen
        if (Settings.alleAnzeigen(this) && checkSelfPermission(leseRecht()) != PackageManager.PERMISSION_GRANTED && !berechtigungAngefragt) {
            berechtigungAngefragt = true;
            requestPermissions(new String[]{leseRecht()}, REQ_BERECHTIGUNG);
        }
        laden();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_BERECHTIGUNG) {
            if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, R.string.storage_permission_needed, Toast.LENGTH_LONG).show();
            laden();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOESCHEN) { if (auswahl) beendeAuswahl(); laden(); }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void bindeMiniatur(ImageView iv, Item it) {
        if (it == null) {
            iv.setVisibility(View.INVISIBLE);
            iv.setOnClickListener(null);
            iv.setOnLongClickListener(null);
            iv.setTag(null);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        iv.setTag(it.id);
        if (gewaehlt.contains(it.id)) iv.setColorFilter(0xAA4FC3F7, android.graphics.PorterDuff.Mode.SRC_ATOP);
        else iv.clearColorFilter();
        iv.setOnClickListener(v -> {
            if (auswahl) { schalteAuswahl(it); return; }
            startActivity(new Intent(this, ViewerActivity.class)
                    .setAction(Intent.ACTION_VIEW).setData(it.uri).putExtra("von_galerie", true));
        });
        iv.setOnLongClickListener(v -> {
            if (auswahl) schalteAuswahl(it); else zeigeMenue(it);
            return true;
        });
        Bitmap bm = cache.get(it.id);
        Integer known = stereoMap.get(it.id);
        setzeSymbol(iv, known);
        if (bm != null) { iv.setImageBitmap(bm); return; }
        iv.setImageDrawable(null);
        pool.execute(() -> {
            try {
                Bitmap t = getContentResolver().loadThumbnail(it.uri, new Size(640, 320), null);
                final int st = symbolFlags(it.uri);
                stereoMap.put(it.id, st);
                cache.put(it.id, t);
                runOnUiThread(() -> { Object tag = iv.getTag(); if (tag != null && it.id == (Long) tag) { iv.setImageBitmap(t); setzeSymbol(iv, st); } });
            } catch (Exception ignored) { }
        });
    }

    static final int SYM_STEREO = 1, SYM_AUDIO = 2;

    /** Bit 1 = Stereo, Bit 2 = Ton (GAudio im Extended-XMP). */
    private int symbolFlags(Uri uri) {
        int f = istStereo(uri) ? SYM_STEREO : 0;
        if ((f & SYM_STEREO) != 0 && hatTon(uri)) f |= SYM_AUDIO;
        return f;
    }

    /**
     * Ton vorhanden? Das Extended-XMP besteht aus 65000-Byte-Bloecken direkt hinter dem Standard-XMP; GAudio steht am Ende.
     * Es werden die Bloecke von hinten gelesen, bis GAudio:Data (Ton) oder GImage:Data (kein Ton) auftaucht.
     */
    private boolean hatTon(Uri uri) {
        final byte[] ext = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.ISO_8859_1);
        try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return false;
            java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.nio.channels.FileChannel ch = fis.getChannel();
            java.util.ArrayList<long[]> bloecke = new java.util.ArrayList<>();      // {Dateiposition der Nutzdaten, Laenge}
            long pos = 2;
            java.nio.ByteBuffer hd = java.nio.ByteBuffer.allocate(4 + ext.length);
            while (bloecke.size() < 400) {
                hd.clear();
                if (ch.read(hd, pos) < 4) break;
                int m = hd.get(1) & 0xFF;
                if ((hd.get(0) & 0xFF) != 0xFF || m == 0xDA || m == 0xD9) break;
                int len = ((hd.get(2) & 0xFF) << 8) | (hd.get(3) & 0xFF);
                if (m == 0xE1 && hd.limit() >= 4 + ext.length && hd.position() >= 4 + ext.length) {
                    boolean gleich = true;
                    for (int i = 0; i < ext.length; i++) if (hd.get(4 + i) != ext[i]) { gleich = false; break; }
                    if (gleich) {
                        long start = pos + 4 + ext.length + 32 + 8;
                        long n = len - 2 - ext.length - 32 - 8;
                        if (n > 0) bloecke.add(new long[]{start, n});
                    }
                }
                pos += 2 + len;
            }
            byte[] vorher = new byte[0];
            for (int i = bloecke.size() - 1; i >= 0; i--) {
                long[] b = bloecke.get(i);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) b[1]);
                ch.read(bb, b[0]);
                String s = new String(bb.array(), 0, bb.position(), StandardCharsets.ISO_8859_1);
                String t = s + new String(vorher, StandardCharsets.ISO_8859_1);      // Marker kann an der Blockgrenze liegen
                if (t.contains("GAudio:Data")) return true;
                if (t.contains("GImage:Data")) return false;
                vorher = s.length() > 40 ? s.substring(0, 40).getBytes(StandardCharsets.ISO_8859_1) : s.getBytes(StandardCharsets.ISO_8859_1);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Stereo, wenn der Standard-XMP einen Verweis auf Extended-XMP (rechtes Auge) oder GImage-Daten enthaelt; liest nur den Dateianfang. */
    private boolean istStereo(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] b = new byte[262144];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            String x = new String(b, 0, n, StandardCharsets.ISO_8859_1);
            return x.contains("HasExtendedXMP") || x.contains("GImage:Data");
        } catch (Exception e) {
            return false;
        }
    }

    private final java.util.Map<Long, Integer> stereoMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static void setzeSymbol(ImageView iv, Integer flags) {
        if (iv instanceof SymbolView) ((SymbolView) iv).setFlags(flags);
    }

    /**
     * Miniatur mit weissen Symbolen oben rechts, unabhaengig vom Zuschnitt des Bildes:
     * ein Kreis = Mono, zwei sich schneidende Kreise = Stereo, Lautsprecher = Ton vorhanden
     * (dieselben Symbole zeigt der Desktop-Viewer).
     */
    private static final class SymbolView extends ImageView {
        private Integer flags;
        private final android.graphics.Paint sh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint wh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path body = new android.graphics.Path(), wave = new android.graphics.Path();

        SymbolView(android.content.Context c) {
            super(c);
            sh.setStyle(android.graphics.Paint.Style.STROKE);
            sh.setColor(0x99000000);
            sh.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            wh.setStyle(android.graphics.Paint.Style.STROKE);
            wh.setColor(0xFFFFFFFF);
            fill.setStyle(android.graphics.Paint.Style.FILL);
            fill.setColor(0xFFFFFFFF);
        }

        void setFlags(Integer f) {
            flags = f;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            if (flags == null || getDrawable() == null) return;
            boolean stereo = (flags & SYM_STEREO) != 0, audio = (flags & SYM_AUDIO) != 0;
            float w = getWidth();
            float r = w * 0.04f, sw = Math.max(2.5f, w * 0.008f), gap = r * 1.15f;
            float m = w * 0.04f;
            float cy = r + m, cx2 = w - m - r, cx1 = cx2 - (stereo ? gap : 0);
            sh.setStrokeWidth(sw * 2.4f);
            wh.setStrokeWidth(sw);
            c.drawCircle(cx1, cy, r, sh);
            if (stereo) c.drawCircle(cx2, cy, r, sh);
            c.drawCircle(cx1, cy, r, wh);
            if (stereo) c.drawCircle(cx2, cy, r, wh);
            if (audio) {
                float z = r * 2.2f, x0 = w - m - z * 1.12f, y0 = getHeight() - m - z;      // unten rechts
                body.reset();
                body.moveTo(x0, y0 + .35f * z);
                body.lineTo(x0 + .3f * z, y0 + .35f * z);
                body.lineTo(x0 + .65f * z, y0 + .08f * z);
                body.lineTo(x0 + .65f * z, y0 + .92f * z);
                body.lineTo(x0 + .3f * z, y0 + .65f * z);
                body.lineTo(x0, y0 + .65f * z);
                body.close();
                wave.reset();
                wave.moveTo(x0 + .82f * z, y0 + .33f * z);
                wave.quadTo(x0 + 1.05f * z, y0 + .5f * z, x0 + .82f * z, y0 + .67f * z);
                sh.setStrokeWidth(sw * 2.4f);
                c.drawPath(body, sh);
                c.drawPath(wave, sh);
                c.drawPath(body, fill);
                c.drawPath(wave, wh);
            }
        }
    }

    private static final Pattern AUTOR = Pattern.compile("<dc:creator>.*?<rdf:li[^>]*>([^<]*)</rdf:li>", Pattern.DOTALL);

    /** Autor aus dem XMP am Dateianfang (dc:creator); leer, wenn keiner gesetzt ist. */
    private String leseAutor(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return "";
            byte[] b = new byte[65536];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            Matcher m = AUTOR.matcher(new String(b, 0, n, StandardCharsets.UTF_8));
            if (m.find()) {
                return m.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&apos;", "'").replace("&amp;", "&").trim();
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Gruppenname (Ueberschrift) eines Bildes passend zur Sortierung. */
    private String gruppe(Item it, int modus, DateFormat df) {
        switch (modus) {
            case 2: return it.name.isEmpty() ? "#" : it.name.substring(0, 1).toUpperCase(Locale.getDefault());
            case 3: return it.ort.isEmpty() ? getString(R.string.group_no_place) : it.ort;
            case 4: {
                double mb = it.groesse / 1048576.0;
                if (mb >= 20) return "\u2265 20 MB";
                if (mb >= 10) return "10\u201320 MB";
                if (mb >= 5) return "5\u201310 MB";
                if (mb >= 2) return "2\u20135 MB";
                return "< 2 MB";
            }
            case 5: return it.autor.isEmpty() ? getString(R.string.group_no_author) : it.autor;
            default: return df.format(new Date(it.datum * 1000L));
        }
    }

    // ------------------------------------------------------------------ Laden und Sortieren

    private void laden() {
        new Thread(() -> {
            List<Item> neu = new ArrayList<>();
            try (Cursor c = abfrage(this)) {
                while (c != null && c.moveToNext()) {
                    Item it = new Item();
                    it.id = c.getLong(0);
                    it.name = c.getString(1) == null ? "" : c.getString(1);
                    it.datum = c.getLong(2);
                    it.groesse = c.getLong(3);
                    it.ort = c.getString(4) == null ? "" : c.getString(4).trim();
                    it.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id);
                    neu.add(it);
                }
            }
            final int modus = sortModus;
            if (modus == 5) for (Item it : neu) it.autor = leseAutor(it.uri);
            Collections.sort(neu, (a, b) -> {
                switch (modus) {
                    case 1: return Long.compare(a.datum, b.datum);                               // Datum: aelteste zuerst
                    case 2: return a.name.compareToIgnoreCase(b.name);                           // Name A-Z
                    case 3: {                                                                    // Ort A-Z, ohne Ort am Ende, dann neueste zuerst
                        if (a.ort.isEmpty() != b.ort.isEmpty()) return a.ort.isEmpty() ? 1 : -1;
                        int k = a.ort.compareToIgnoreCase(b.ort);
                        return k != 0 ? k : Long.compare(b.datum, a.datum);
                    }
                    case 4: return Long.compare(b.groesse, a.groesse);                           // Groesse: groesste zuerst
                    case 5: {                                                                    // Autor A-Z, ohne Autor am Ende, dann neueste zuerst
                        if (a.autor.isEmpty() != b.autor.isEmpty()) return a.autor.isEmpty() ? 1 : -1;
                        int k = a.autor.compareToIgnoreCase(b.autor);
                        return k != 0 ? k : Long.compare(b.datum, a.datum);
                    }
                    default: return Long.compare(b.datum, a.datum);                              // Datum: neueste zuerst
                }
            });
            // Zeilen bilden: Ueberschrift (Gruppe + Anzahl), darunter je zwei Miniaturen pro Zeile
            final DateFormat df = DateFormat.getDateInstance(DateFormat.LONG);
            final List<Object> neueZeilen = new ArrayList<>();
            int i = 0;
            while (i < neu.size()) {
                String g = gruppe(neu.get(i), modus, df);
                int j = i;
                while (j < neu.size() && gruppe(neu.get(j), modus, df).equals(g)) j++;
                neueZeilen.add(g + "  \u00B7  " + (j - i));
                for (int k = i; k < j; k += 2) neueZeilen.add(new Item[]{neu.get(k), k + 1 < j ? neu.get(k + 1) : null});
                i = j;
            }
            runOnUiThread(() -> {
                zeilen.clear();
                zeilen.addAll(neueZeilen);
                adapter.notifyDataSetChanged();
                leer.setVisibility(zeilen.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void zeigeSortierMenue() {
        String[] opt = {getString(R.string.sort_date_new), getString(R.string.sort_date_old), getString(R.string.sort_name),
                getString(R.string.sort_place), getString(R.string.sort_size), getString(R.string.sort_author)};
        new Ui.Dlg(this).setTitle(R.string.sort_title)
                .setSingleChoiceItems(opt, sortModus, (d, w) -> {
                    sortModus = w;
                    Settings.p(this).edit().putInt(Settings.K_SORT, w).apply();
                    d.dismiss();
                    laden();
                }).show();
    }

    // ------------------------------------------------------------------ Menue

    private void zeigeMenue(Item it) {
        String[] eintraege = {getString(R.string.menu_share), getString(R.string.menu_flat), getString(R.string.menu_sbs),
                getString(R.string.menu_anaglyph), getString(R.string.menu_delete), getString(R.string.menu_select), getString(R.string.menu_lenti)};
        new Ui.Dlg(this).setTitle(it.name).setItems(eintraege, (d, w) -> {
            if (w == 0) teilen(it);
            else if (w == 4) frageLoeschen(it);
            else if (w == 6) startActivity(new Intent(this, LentiActivity.class).setData(it.uri).putExtra("name", it.name));
            else if (w == 5) { auswahl = true; gewaehlt.clear(); gewaehlt.add(it.id); aktualisiereAuswahl(); }
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
                Exporter.exportiere(this, it.uri, it.name, art);
                runOnUiThread(() -> Toast.makeText(this, R.string.exported, Toast.LENGTH_LONG).show());
                return;
            } catch (Exception | OutOfMemoryError e) {
                m = getString(R.string.export_failed, String.valueOf(e.getMessage()));
            }
            final String msg = m;
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** Aktionen fuer alle ausgewaehlten Bilder: dieselben wie im Menue eines einzelnen Bildes. */
    private void zeigeAuswahlMenue() {
        if (gewaehlt.isEmpty()) return;
        String[] eintraege = {getString(R.string.menu_share), getString(R.string.menu_flat), getString(R.string.menu_sbs),
                getString(R.string.menu_anaglyph), getString(R.string.menu_delete)};
        new Ui.Dlg(this).setTitle(getString(R.string.selected_count, gewaehlt.size())).setItems(eintraege, (d, w) -> {
            List<Item> sel = ausgewaehlteItems();
            if (sel.isEmpty()) return;
            if (w == 0) teilenViele(sel);
            else if (w == 4) frageLoeschenAuswahl();
            else exportiereViele(sel, w - 1);
        }).show();
    }

    private List<Item> ausgewaehlteItems() {
        List<Item> out = new ArrayList<>();
        for (Object z : zeilen)
            if (z instanceof Item[]) for (Item it : (Item[]) z) if (it != null && gewaehlt.contains(it.id)) out.add(it);
        return out;
    }

    private void teilenViele(List<Item> sel) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Item it : sel) uris.add(it.uri);
        Intent s = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        s.setType("image/jpeg");
        if (uris.size() == 1) s.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, getString(R.string.share_title)));
    }

    private void exportiereViele(List<Item> sel, int art) {
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            String fehler = null;
            for (Item it : sel) {
                try {
                    Exporter.exportiere(this, it.uri, it.name, art);
                    ok++;
                } catch (Exception | OutOfMemoryError e) {
                    fehler = getString(R.string.export_failed, String.valueOf(e.getMessage()));
                }
            }
            final String m = fehler == null ? getString(R.string.exported_many, ok) : fehler + " (" + ok + "/" + sel.size() + ")";
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); beendeAuswahl(); });
        }).start();
    }

    private void schalteAuswahl(Item it) {
        if (!gewaehlt.remove(it.id)) gewaehlt.add(it.id);
        aktualisiereAuswahl();
    }

    private void aktualisiereAuswahl() {
        auswahlLeiste.setVisibility(auswahl ? View.VISIBLE : View.GONE);
        auswahlZahl.setText(getString(R.string.selected_count, gewaehlt.size()));
        adapter.notifyDataSetChanged();
    }

    private void beendeAuswahl() {
        auswahl = false;
        gewaehlt.clear();
        aktualisiereAuswahl();
    }

    @Override
    public void onBackPressed() {
        if (auswahl) beendeAuswahl(); else super.onBackPressed();
    }

    @SuppressWarnings("deprecation")
    private void frageLoeschenAuswahl() {
        if (gewaehlt.isEmpty()) return;
        final List<Uri> uris = new ArrayList<>();
        final List<Long> ids = new ArrayList<>(gewaehlt);
        for (Long id : ids) uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
        new Ui.Dlg(this)
                .setMessage(getString(R.string.delete_many_confirm, uris.size()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.menu_delete, (d, w) -> {
                    try {
                        for (Uri u : uris) getContentResolver().delete(u, null, null);
                        for (Long id : ids) cache.remove(id);
                        beendeAuswahl();
                        laden();
                    } catch (SecurityException e) {
                        // Fremde Dateien: ein einziger Systemdialog fuer alle
                        try {
                            IntentSender is = MediaStore.createDeleteRequest(getContentResolver(), uris).getIntentSender();
                            startIntentSenderForResult(is, REQ_LOESCHEN, null, 0, 0, 0);
                        } catch (Exception ex) {
                            Toast.makeText(this, R.string.delete_not_allowed, Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
    }

    @SuppressWarnings("deprecation")
    private void frageLoeschen(Item it) {
        new Ui.Dlg(this)
                .setMessage(getString(R.string.delete_confirm, it.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.menu_delete, (d, w) -> {
                    try {
                        getContentResolver().delete(it.uri, null, null);
                        cache.remove(it.id);
                        laden();
                    } catch (SecurityException e) {
                        // Fremde Datei (z. B. Cardboard Camera): Loeschen muss der Nutzer per Systemdialog bestaetigen
                        try {
                            IntentSender is = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(it.uri)).getIntentSender();
                            startIntentSenderForResult(is, REQ_LOESCHEN, null, 0, 0, 0);
                        } catch (Exception ex) {
                            Toast.makeText(this, R.string.delete_not_allowed, Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
    }
}
