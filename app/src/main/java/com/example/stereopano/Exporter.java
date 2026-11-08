package com.example.stereopano;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Exportiert eine .vr.jpg in gaengige Formate (nach Bilder/PanoExport):
 *  FLACH    - normales 360-Grad-Panorama (linkes Auge + GPano-Metadaten), fuer jeden Panorama-Betrachter
 *  SBS      - Side-by-Side (links | rechts) fuer Videobrillen / 3D-Fernseher
 *  ANAGLYPH - Rot/Cyan-Anaglyphe fuer Papierbrillen
 */
final class Exporter {
    static final int FLACH = 0, SBS = 1, ANAGLYPH = 2;
    private static final String ZIEL = Environment.DIRECTORY_PICTURES + "/PanoExport";

    private Exporter() {}

    static Uri exportiere(Context c, Uri src, String name, int art) throws Exception {
        byte[] daten;
        try (InputStream in = c.getContentResolver().openInputStream(src)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            daten = bo.toByteArray();
        }
        VrJpgReader.Result m = VrJpgReader.parse(daten);
        String basis = name.replaceAll("(?i)\\.vr\\.jpg$|(?i)\\.jpe?g$", "");
        byte[] out;
        String ziel;

        if (art == FLACH) {
            byte[] sauber = VrJpgWriter.withoutXmp(daten);
            if (m.fullW > 0) {
                VrJpgWriter.Meta mm = new VrJpgWriter.Meta();
                mm.fullW = m.fullW; mm.fullH = m.fullH; mm.cropW = m.cropW; mm.cropH = m.cropH;
                mm.cropLeft = m.cropLeft; mm.cropTop = m.cropTop; mm.poseHeading = m.poseHeading;
                mm.author = m.author;
                mm.tags = m.tags.toArray(new String[0]);
                out = VrJpgWriter.flat(sauber, mm);
            } else {
                out = sauber;
            }
            ziel = basis + "_2D.jpg";
        } else {
            if (m.rightJpeg == null) throw new IOException(c.getString(R.string.export_no_stereo));
            Bitmap l = BitmapFactory.decodeByteArray(daten, 0, daten.length);
            Bitmap r = BitmapFactory.decodeByteArray(m.rightJpeg, 0, m.rightJpeg.length);
            if (l == null || r == null) throw new IOException(c.getString(R.string.export_unreadable));
            int w = l.getWidth(), h = l.getHeight();
            Bitmap res;
            if (art == SBS) {
                res = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(res);
                cv.drawBitmap(l, 0, 0, null);
                cv.drawBitmap(r, w, 0, null);
                ziel = basis + "_SBS.jpg";
            } else {
                res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                int[] zl = new int[w], zr = new int[w];
                for (int y = 0; y < h; y++) {
                    l.getPixels(zl, 0, w, 0, y, w, 1);
                    r.getPixels(zr, 0, w, 0, y, w, 1);
                    for (int x = 0; x < w; x++) zl[x] = 0xFF000000 | (zl[x] & 0x00FF0000) | (zr[x] & 0x0000FFFF);
                    res.setPixels(zl, 0, w, 0, y, w, 1);
                }
                ziel = basis + "_Anaglyphe.jpg";
            }
            l.recycle();
            r.recycle();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(6_000_000);
            res.compress(Bitmap.CompressFormat.JPEG, 92, bo);
            res.recycle();
            out = bo.toByteArray();
        }
        return speichere(c, out, ziel);
    }

    private static Uri speichere(Context c, byte[] data, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, ZIEL);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException(c.getString(R.string.mediastore_failed));
        try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
            os.write(data);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        c.getContentResolver().update(uri, v, null, null);
        return uri;
    }
}
