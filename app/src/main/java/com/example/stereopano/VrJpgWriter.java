package com.example.stereopano;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * Baut eine ".vr.jpg"-Datei im Format der Google Cardboard Camera:
 *  - Hauptbild = Panorama fuers linke Auge (normales JPEG, in jeder Galerie sichtbar)
 *  - APP1 "Standard-XMP": GPano-Metadaten + xmpNote:HasExtendedXMP
 *  - APP1 "Extended-XMP" (Adobe-Spezifikation, Bloecke zu je 65000 Byte):
 *        GImage:Mime / GImage:Data  = Panorama rechtes Auge (Base64)
 *        GAudio:Mime / GAudio:Data  = Tonspur (Base64, optional)
 *
 * Wichtig: Ein JPEG-Segment ist auf 64 KB begrenzt. Ein einzelner XMP-Block kann das Bild des
 * rechten Auges deshalb nicht aufnehmen - der Extended-XMP-Mechanismus ist zwingend noetig.
 */
final class VrJpgWriter {

    static final class Meta {
        int fullW, fullH;          // volles Aequirektangular-Panorama
        int cropW, cropH;          // tatsaechlich vorhandener Ausschnitt
        int cropLeft, cropTop;
        double poseHeading = Double.NaN;   // Kompasskurs der Bildmitte des vollen Panoramas
        double initialHeading = 0;         // Startblickrichtung (0 = linker Bildrand)
        String author;                      // dc:creator (Suche nach Autor)
        String[] tags;                      // dc:subject (Orte, Stichworte)
        double lat = Double.NaN, lon = Double.NaN;   // GPS (wird als EXIF geschrieben)
    }

    private static final byte[] HDR_XMP = ascii("http://ns.adobe.com/xap/1.0/\0");
    private static final byte[] HDR_EXT = ascii("http://ns.adobe.com/xmp/extension/\0");
    private static final int CHUNK = 65000;

    private VrJpgWriter() {}

    static byte[] build(byte[] leftJpeg, byte[] rightJpeg, byte[] audio, Meta m) throws IOException {
        // 1) Extended XMP: rechtes Auge + Audio
        StringBuilder ext = new StringBuilder(rightJpeg.length * 4 / 3 + 2048);
        ext.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
           .append("<rdf:Description rdf:about=\"\" xmlns:GImage=\"http://ns.google.com/photos/1.0/image/\"")
           .append(" xmlns:GAudio=\"http://ns.google.com/photos/1.0/audio/\"")
           .append(" GImage:Mime=\"image/jpeg\" GImage:Data=\"")
           .append(Base64.getEncoder().encodeToString(rightJpeg)).append('"');
        if (audio != null && audio.length > 0) {
            ext.append(" GAudio:Mime=\"audio/mp4a-latm\" GAudio:Data=\"")
               .append(Base64.getEncoder().encodeToString(audio)).append('"');
        }
        ext.append("/></rdf:RDF></x:xmpmeta>");
        byte[] extBytes = ext.toString().getBytes(StandardCharsets.UTF_8);
        String guid = md5Hex(extBytes);

        // 2) Standard-XMP mit GPano-Daten
        byte[] mainBytes = mainXmp(m, guid).getBytes(StandardCharsets.UTF_8);

        // 3) Einfuegen
        byte[] exif = (!Double.isNaN(m.lat) && !Double.isNaN(m.lon)) ? ExifGps.app1Payload(m.lat, m.lon) : null;
        return insert(leftJpeg, mainBytes, extBytes, guid, exif);
    }

    /** Flaches 360-Grad-Panorama (nur GPano, ohne zweites Auge/Ton) - fuer normale Panorama-Betrachter. */
    static byte[] flat(byte[] jpegWithoutXmp, Meta m) throws IOException {
        // EXIF (GPS) bleibt erhalten, weil withoutXmp nur die XMP-Segmente entfernt
        return insert(jpegWithoutXmp, mainXmp(m, null).getBytes(StandardCharsets.UTF_8), null, null, null);
    }

    private static String mainXmp(Meta m, String guid) {
        StringBuilder main = new StringBuilder(1024);
        main.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            .append("<rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\"")
            .append(" xmlns:xmpNote=\"http://ns.adobe.com/xmp/note/\"")
            .append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
            .append(" GPano:UsePanoramaViewer=\"True\" GPano:ProjectionType=\"equirectangular\"")
            .append(" GPano:FullPanoWidthPixels=\"").append(m.fullW).append('"')
            .append(" GPano:FullPanoHeightPixels=\"").append(m.fullH).append('"')
            .append(" GPano:CroppedAreaImageWidthPixels=\"").append(m.cropW).append('"')
            .append(" GPano:CroppedAreaImageHeightPixels=\"").append(m.cropH).append('"')
            .append(" GPano:CroppedAreaLeftPixels=\"").append(m.cropLeft).append('"')
            .append(" GPano:CroppedAreaTopPixels=\"").append(m.cropTop).append('"')
            .append(" GPano:InitialViewHeadingDegrees=\"").append(String.format(Locale.US, "%.1f", m.initialHeading)).append('"');
        if (!Double.isNaN(m.poseHeading)) {
            main.append(" GPano:PoseHeadingDegrees=\"").append(String.format(Locale.US, "%.1f", m.poseHeading)).append('"');
        }
        if (guid != null) main.append(" xmpNote:HasExtendedXMP=\"").append(guid).append('"');
        main.append(">");
        if (m.author != null && !m.author.trim().isEmpty()) {
            main.append("<dc:creator><rdf:Seq><rdf:li>").append(esc(m.author.trim())).append("</rdf:li></rdf:Seq></dc:creator>");
        }
        if (m.tags != null) {
            StringBuilder t = new StringBuilder();
            for (String tag : m.tags) if (tag != null && !tag.trim().isEmpty()) t.append("<rdf:li>").append(esc(tag.trim())).append("</rdf:li>");
            if (t.length() > 0) main.append("<dc:subject><rdf:Bag>").append(t).append("</rdf:Bag></dc:subject>");
        }
        main.append("</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>");
        return main.toString();
    }

    /** XML-Sonderzeichen in Namen/Tags (&, <, >, Anfuehrungszeichen) maskieren. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n') b.append(c);    // Steuerzeichen sind in XML 1.0 verboten
            }
        }
        return b.toString();
    }

    /** Entfernt alle XMP-APP1-Segmente (Standard und Extended) - Rest des JPEG bleibt unveraendert. */
    static byte[] withoutXmp(byte[] jpg) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(jpg.length);
        o.write(jpg, 0, 2);
        int pos = 2;
        while (pos + 4 <= jpg.length && (jpg[pos] & 0xFF) == 0xFF) {
            int marker = jpg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                o.write(jpg, pos, 2);
                pos += 2;
                continue;
            }
            int len = ((jpg[pos + 2] & 0xFF) << 8) | (jpg[pos + 3] & 0xFF);
            boolean xmp = marker == 0xE1 && (starts(jpg, pos + 4, HDR_XMP) || starts(jpg, pos + 4, HDR_EXT));
            if (!xmp) o.write(jpg, pos, 2 + len);
            pos += 2 + len;
        }
        o.write(jpg, pos, jpg.length - pos);
        return o.toByteArray();
    }

    private static boolean starts(byte[] b, int off, byte[] prefix) {
        if (off + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[off + i] != prefix[i]) return false;
        return true;
    }

    /** Fuegt Standard-XMP (und ggf. Extended-XMP-Bloecke) nach SOI/JFIF ein. */
    private static byte[] insert(byte[] leftJpeg, byte[] mainBytes, byte[] extBytes, String guid, byte[] exifPayload) throws IOException {
        if (leftJpeg.length < 4 || (leftJpeg[0] & 0xFF) != 0xFF || (leftJpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("Kein gueltiges JPEG");
        }
        int pos = 2;
        if ((leftJpeg[2] & 0xFF) == 0xFF && (leftJpeg[3] & 0xFF) == 0xE0) {
            pos = 4 + (((leftJpeg[4] & 0xFF) << 8) | (leftJpeg[5] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(leftJpeg.length + (extBytes == null ? 0 : extBytes.length) + 200_000);
        out.write(leftJpeg, 0, pos);

        if (exifPayload != null) writeApp1(out, exifPayload);      // EXIF (GPS) vor dem XMP
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.write(HDR_XMP);
        seg.write(mainBytes);
        writeApp1(out, seg.toByteArray());

        if (extBytes != null) {
            byte[] guidAscii = ascii(guid);
            for (int off = 0; off < extBytes.length; off += CHUNK) {
                int len = Math.min(CHUNK, extBytes.length - off);
                seg.reset();
                seg.write(HDR_EXT);
                seg.write(guidAscii);               // 32 Byte
                writeInt(seg, extBytes.length);     // Gesamtlaenge
                writeInt(seg, off);                 // Offset dieses Blocks
                seg.write(extBytes, off, len);
                writeApp1(out, seg.toByteArray());
            }
        }
        out.write(leftJpeg, pos, leftJpeg.length - pos);
        return out.toByteArray();
    }

    private static void writeApp1(ByteArrayOutputStream out, byte[] payload) throws IOException {
        int l = payload.length + 2;
        if (l > 0xFFFF) throw new IOException("APP1-Segment zu gross");
        out.write(0xFF);
        out.write(0xE1);
        out.write(l >> 8);
        out.write(l & 0xFF);
        out.write(payload);
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String md5Hex(byte[] data) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
