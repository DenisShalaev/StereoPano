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
        StringBuilder main = new StringBuilder(1024);
        main.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            .append("<rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\"")
            .append(" xmlns:xmpNote=\"http://ns.adobe.com/xmp/note/\"")
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
        main.append(" xmpNote:HasExtendedXMP=\"").append(guid).append("\"/>")
            .append("</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>");
        byte[] mainBytes = main.toString().getBytes(StandardCharsets.UTF_8);

        // 3) Einfuegestelle: nach SOI (und nach JFIF-APP0, falls vorhanden)
        if (leftJpeg.length < 4 || (leftJpeg[0] & 0xFF) != 0xFF || (leftJpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("Kein gueltiges JPEG");
        }
        int pos = 2;
        if ((leftJpeg[2] & 0xFF) == 0xFF && (leftJpeg[3] & 0xFF) == 0xE0) {
            pos = 4 + (((leftJpeg[4] & 0xFF) << 8) | (leftJpeg[5] & 0xFF));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(leftJpeg.length + extBytes.length + 200_000);
        out.write(leftJpeg, 0, pos);

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.write(HDR_XMP);
        seg.write(mainBytes);
        writeApp1(out, seg.toByteArray());

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
