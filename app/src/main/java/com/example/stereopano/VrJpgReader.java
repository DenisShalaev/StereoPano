package com.example.stereopano;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Liest eine ".vr.jpg"-Datei (Google Cardboard Camera Format):
 * sucht Standard-XMP (GPano-Daten) und setzt die Extended-XMP-Bloecke wieder zusammen,
 * um das Bild des rechten Auges (GImage:Data) und die Tonspur (GAudio:Data) zu extrahieren.
 * Das linke Auge ist das Hauptbild der Datei selbst.
 */
final class VrJpgReader {

    static final class Result {
        byte[] rightJpeg;     // null, falls kein Stereo-Bild gefunden wurde
        byte[] audio;         // null, falls kein Ton vorhanden
        int fullW, fullH, cropW, cropH, cropLeft, cropTop;   // 0, falls keine GPano-Daten
        double poseHeading = Double.NaN;
        String author;
        java.util.List<String> tags = new java.util.ArrayList<>();
    }

    private static final byte[] HDR_XMP = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HDR_EXT = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.US_ASCII);

    private VrJpgReader() {}

    static Result parse(byte[] b) {
        Result res = new Result();
        byte[] ext = null;
        String mainXml = null;

        int pos = 2;
        while (pos + 4 <= b.length && (b[pos] & 0xFF) == 0xFF) {
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;                   // Bilddaten beginnen
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {    // Marker ohne Laenge
                pos += 2;
                continue;
            }
            int len = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            int data = pos + 4;
            int dataEnd = Math.min(b.length, pos + 2 + len);

            if (marker == 0xE1) {
                if (startsWith(b, data, HDR_XMP)) {
                    int s = data + HDR_XMP.length;
                    String xml = new String(b, s, dataEnd - s, StandardCharsets.UTF_8);
                    if (mainXml == null) mainXml = xml;
                } else if (startsWith(b, data, HDR_EXT)) {
                    int p = data + HDR_EXT.length + 32;          // GUID ueberspringen
                    if (p + 8 <= dataEnd) {
                        int total = readInt(b, p);
                        int off = readInt(b, p + 4);
                        int chunkStart = p + 8;
                        int chunkLen = dataEnd - chunkStart;
                        if (total > 0 && total < 200_000_000 && off >= 0 && off + chunkLen <= total) {
                            if (ext == null) ext = new byte[total];
                            if (ext.length == total) System.arraycopy(b, chunkStart, ext, off, chunkLen);
                        }
                    }
                }
            }
            pos += 2 + len;
        }

        String extXml = ext != null ? new String(ext, StandardCharsets.UTF_8) : null;

        if (mainXml != null) {
            res.fullW = toInt(attr(mainXml, "GPano:FullPanoWidthPixels"));
            res.fullH = toInt(attr(mainXml, "GPano:FullPanoHeightPixels"));
            res.cropW = toInt(attr(mainXml, "GPano:CroppedAreaImageWidthPixels"));
            res.cropH = toInt(attr(mainXml, "GPano:CroppedAreaImageHeightPixels"));
            res.cropLeft = toInt(attr(mainXml, "GPano:CroppedAreaLeftPixels"));
            res.cropTop = toInt(attr(mainXml, "GPano:CroppedAreaTopPixels"));
            java.util.List<String> a = items(mainXml, "dc:creator");
            if (!a.isEmpty()) res.author = a.get(0);
            res.tags = items(mainXml, "dc:subject");
            String pose = attr(mainXml, "GPano:PoseHeadingDegrees");
            if (pose != null) {
                try { res.poseHeading = Double.parseDouble(pose.trim()); } catch (NumberFormatException ignored) { }
            }
        }

        // Bild/Ton koennen im Extended-XMP oder (bei kleinen Dateien) im Standard-XMP stehen
        for (String xml : new String[]{extXml, mainXml}) {
            if (xml == null) continue;
            if (res.rightJpeg == null) res.rightJpeg = decode(attr(xml, "GImage:Data"));
            if (res.audio == null) res.audio = decode(attr(xml, "GAudio:Data"));
        }
        return res;
    }

    /** Liest Attribut name="..." oder Element <name>...</name>. */
    static String attr(String xml, String name) {
        int i = xml.indexOf(name + "=\"");
        if (i >= 0) {
            int s = i + name.length() + 2;
            int e = xml.indexOf('"', s);
            return e > 0 ? xml.substring(s, e) : null;
        }
        i = xml.indexOf("<" + name + ">");
        if (i >= 0) {
            int s = i + name.length() + 2;
            int e = xml.indexOf("</" + name + ">", s);
            return e > 0 ? xml.substring(s, e) : null;
        }
        return null;
    }

    /** Liest die rdf:li-Eintraege innerhalb von <container>...</container> (XML-Entities werden aufgeloest). */
    static java.util.List<String> items(String xml, String container) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int a = xml.indexOf("<" + container + ">");
        if (a < 0) return out;
        int b = xml.indexOf("</" + container + ">", a);
        if (b < 0) return out;
        String part = xml.substring(a, b);
        int i = 0;
        while ((i = part.indexOf("<rdf:li>", i)) >= 0) {
            int e = part.indexOf("</rdf:li>", i);
            if (e < 0) break;
            out.add(unesc(part.substring(i + 8, e)));
            i = e + 9;
        }
        return out;
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            String clean = base64.replace("&#xA;", "").replace("&#10;", "").replace("&#xD;", "");
            return Base64.getMimeDecoder().decode(clean);   // toleriert Zeilenumbrueche
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int toInt(String s) {
        if (s == null) return 0;
        try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return 0; }
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static boolean startsWith(byte[] b, int off, byte[] prefix) {
        if (off + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[off + i] != prefix[i]) return false;
        return true;
    }
}
