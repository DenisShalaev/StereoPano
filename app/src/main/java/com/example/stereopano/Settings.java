package com.example.stereopano;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Zentrale Einstellungen (SharedPreferences "pano"). Die Schluessel kompass/gyro/halb/linsen
 * werden auch direkt vom Betrachter beschrieben und bleiben dadurch synchron.
 * Regler werden als ganze Zahlen gespeichert (Zehntel/Hundertstel), um Float-Rundung zu vermeiden.
 */
final class Settings {
    static final String PREFS = "pano";

    // Aufnahme
    static final String K_WINKEL = "stereo_winkel";      // Zehntel Grad
    static final String K_DELTA = "delta";               // Zehntel Grad
    static final String K_TEMPO = "ziel_tempo";          // Grad/s
    static final String K_BREITE = "max_breite";         // Pixel (lange Kameraseite)
    static final String K_LATENZ = "latenz_ms";          // Millisekunden
    static final String K_FPX = "fpx_prozent";           // Prozent
    static final String K_WKORR_UW = "winkel_korr_uw";     // wie K_WKORR, fuer das Ultraweitwinkel (Standard 4,4 %)
    static final String K_WKORR = "winkel_korr";          // Zehntel Prozent (45 = 4,5 %)
    static final String K_STAB = "max_nickrate";         // Grad/s (20 = aus)
    static final String K_MOVE = "bewegung";             // 0..100: Empfindlichkeit der Erkennung bewegter Objekte (0 = aus)
    static final String K_AUTHOR = "autor";                // Autorenname (XMP dc:creator)
    static final String K_LOC = "ort_speichern";           // Ort (GPS + Ortsnamen) in die Datei schreiben
    static final String K_GHOST = "start_geist";           // am Ende der Aufnahme das erste Bild halbtransparent einblenden
    static final String K_SEAMOPT = "naht_optimiert";      // verbesserte Naht (Pfad kleinster Abweichung + Mehrband-Mischung)
    static final String K_ALL = "alle_vr_jpg";             // auch .vr.jpg anderer Apps (z. B. Cardboard Camera) zeigen
    static final String K_SAVE = "speicherziel";           // 0 = Telefon, 1 = Telefon + Google Fotos, 2 = nur Google Fotos
    static final String K_GACC = "google_konto";           // Konto (E-Mail) fuer Google Fotos
    static final String K_LOOPMODE = "schluss_modus";      // 0 = automatisch (Bild + Gyro), 1 = nur Gyro
    static final String K_SORT = "sortierung";             // Sortierung der Galerie
    static final String K_SHARP = "schaerfe";            // 0..100 (Nachschaerfung des fertigen Panoramas)
    static final String K_QUALI = "jpeg_quali";          // 70..100
    static final String K_AE = "belichtung_sperren";
    static final String K_LEIT = "leitpunkte";
    static final String K_AUDIO = "audio";
    // Betrachter
    static final String K_KOMPASS = "kompass";
    static final String K_GYRO = "gyro";
    static final String K_HALB = "halb";
    static final String K_LINSEN = "linsen";
    static final String K_K1 = "lens_k1";                // Hundertstel
    static final String K_K2 = "lens_k2";                // Hundertstel
    static final String K_SBSFOV = "sbs_fov";            // Grad (vertikal)

    static final String[] ALLE = {K_WINKEL, K_DELTA, K_TEMPO, K_BREITE, K_LATENZ, K_FPX, K_WKORR, K_WKORR_UW, K_STAB, K_MOVE, K_SHARP, K_AUTHOR, K_LOC, K_ALL, K_SEAMOPT, K_GHOST, K_LOOPMODE, K_SAVE, K_GACC, K_SORT, K_QUALI, K_AE, K_LEIT,
            K_AUDIO, K_KOMPASS, K_GYRO, K_HALB, K_LINSEN, K_K1, K_K2, K_SBSFOV};

    private Settings() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // --- Aufnahme
    static double stereoWinkel(Context c) { return p(c).getInt(K_WINKEL, 35) / 10.0; }
    static double delta(Context c) { return p(c).getInt(K_DELTA, 3) / 10.0; }
    static double zielTempo(Context c) { return p(c).getInt(K_TEMPO, 8); }
    static int maxBreite(Context c) { return p(c).getInt(K_BREITE, 1920); }
    static long latenzNs(Context c) { return p(c).getInt(K_LATENZ, 0) * 1_000_000L; }
    static double winkelKorr(Context c) { return p(c).getInt(K_WKORR, 35) / 1000.0; }
    static double winkelKorr(Context c, boolean ultraweit) { return ultraweit ? p(c).getInt(K_WKORR_UW, 44) / 1000.0 : winkelKorr(c); }
    static double fpxFaktor(Context c) { return p(c).getInt(K_FPX, 100) / 100.0; }
    /** Maximale Nick-/Kippgeschwindigkeit in Grad/s, bei der ein Bild noch verwendet wird. */
    static double maxNickrate(Context c) {
        int v = p(c).getInt(K_STAB, 6);
        return v >= 20 ? Double.POSITIVE_INFINITY : v;
    }
    static int bewegung(Context c) { return p(c).getInt(K_MOVE, 0); }
    static int speicherZiel(Context c) { return p(c).getInt(K_SAVE, 0); }
    static String author(Context c) { String a = p(c).getString(K_AUTHOR, ""); return a == null ? "" : a.trim(); }
    static boolean ortSpeichern(Context c) { return p(c).getBoolean(K_LOC, true); }
    static boolean geist(Context c) { return p(c).getBoolean(K_GHOST, false); }
    static boolean alleAnzeigen(Context c) { return p(c).getBoolean(K_ALL, false); }
    static float schaerfe(Context c) { return p(c).getInt(K_SHARP, 50) / 100f; }
    static int jpegQualitaet(Context c) { return p(c).getInt(K_QUALI, 90); }
    static boolean belichtungSperren(Context c) { return p(c).getBoolean(K_AE, true); }
    static boolean leitpunkte(Context c) { return p(c).getBoolean(K_LEIT, true); }
    static boolean audio(Context c) { return p(c).getBoolean(K_AUDIO, true); }

    // --- Betrachter
    static float lensK1(Context c) { return p(c).getInt(K_K1, 34) / 100f; }
    static float lensK2(Context c) { return p(c).getInt(K_K2, 55) / 100f; }
    static float sbsFov(Context c) { return p(c).getInt(K_SBSFOV, 45); }

    static void zuruecksetzen(Context c) {
        SharedPreferences.Editor e = p(c).edit();
        for (String k : ALLE) e.remove(k);
        e.apply();
    }
}
