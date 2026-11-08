package com.example.stereopano;

import java.io.ByteArrayOutputStream;

/**
 * Baut ein minimales EXIF-APP1-Segment nur mit GPS-Koordinaten (Galerien, Google Fotos und Kartenansichten lesen GPS aus EXIF).
 * Das Segment wird von {@link VrJpgWriter} VOR dem XMP eingefuegt - das JPEG wird nirgends neu aufgebaut, daher bleibt
 * das Extended-XMP mit dem rechten Auge unangetastet (ExifInterface.saveAttributes wuerde die Datei neu schreiben).
 */
final class ExifGps {
    private ExifGps() {}

    /** Nutzlast des APP1-Segments: "Exif\0\0" + TIFF (Big Endian) mit IFD0 -> GPS-IFD. */
    static byte[] app1Payload(double lat, double lon) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(160);
        w(o, new byte[]{'E', 'x', 'i', 'f', 0, 0});
        w(o, new byte[]{'M', 'M', 0, 0x2A});
        i32(o, 8);                                   // Offset von IFD0
        // IFD0: ein Eintrag (GPSInfo-Zeiger)
        i16(o, 1);
        entry(o, 0x8825, 4, 1, 26);                  // GPS-IFD liegt bei Offset 26
        i32(o, 0);                                   // kein weiteres IFD
        // GPS-IFD: 5 Eintraege (aufsteigend nach Tag)
        i16(o, 5);
        o.write(0); o.write(0); i16(o, 1); i32(o, 4); w(o, new byte[]{2, 3, 0, 0});          // 0x0000 GPSVersionID
        i16(o, 1); i16(o, 2); i32(o, 2); w(o, new byte[]{(byte) (lat >= 0 ? 'N' : 'S'), 0, 0, 0});   // 0x0001 LatitudeRef
        entry(o, 2, 5, 3, 92);                       // 0x0002 Latitude -> Daten bei 92
        i16(o, 3); i16(o, 2); i32(o, 2); w(o, new byte[]{(byte) (lon >= 0 ? 'E' : 'W'), 0, 0, 0});   // 0x0003 LongitudeRef
        entry(o, 4, 5, 3, 116);                      // 0x0004 Longitude -> Daten bei 116
        i32(o, 0);
        dms(o, Math.abs(lat));
        dms(o, Math.abs(lon));
        return o.toByteArray();
    }

    private static void entry(ByteArrayOutputStream o, int tag, int type, int count, int value) {
        i16(o, tag); i16(o, type); i32(o, count); i32(o, value);
    }

    /** Grad, Minuten, Sekunden als drei Rationals (Sekunden mit 4 Nachkommastellen). */
    private static void dms(ByteArrayOutputStream o, double deg) {
        int d = (int) deg;
        double mm = (deg - d) * 60;
        int m = (int) mm;
        long s = Math.round((mm - m) * 60 * 10000);
        if (s >= 600000) { s -= 600000; m++; }
        if (m >= 60) { m -= 60; d++; }
        i32(o, d); i32(o, 1);
        i32(o, m); i32(o, 1);
        i32(o, (int) s); i32(o, 10000);
    }

    private static void w(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }
    private static void i16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }
    private static void i32(ByteArrayOutputStream o, int v) { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v); }
}
