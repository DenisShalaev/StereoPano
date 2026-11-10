package com.example.stereopano;

/**
 * Laufende Build-Nummer. Wird bei JEDER Aenderung des Programms um 1 erhoeht.
 * Sie steht in den Tags jeder neuen Aufnahme ("build:N", XMP dc:subject) und im Einstellungsbildschirm,
 * damit sich jede Datei einer bestimmten Programmversion zuordnen laesst.
 */
final class BuildInfo {
    static final int NUMBER = 49;
    static final String TAG = "build:" + NUMBER;

    private BuildInfo() { }
}
