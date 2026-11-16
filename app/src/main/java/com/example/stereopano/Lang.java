package com.example.stereopano;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Eigene Sprachwahl der App (unabhaengig von der Systemsprache): Index 0 = Systemsprache. */
final class Lang {
    private Lang() {}

    static final String KEY = "app_lang";
    static final String[] CODES = {"", "en", "de", "fr", "it", "es", "ru", "uk"};
    static final String[] NAMES = {null, "English", "Deutsch", "Français", "Italiano", "Español", "Русский", "Українська"};

    static String get(Context c) { return c.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).getString(KEY, ""); }
    static void set(Context c, String code) { c.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply(); }
    static int index(Context c) { String s = get(c); for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(s)) return i; return 0; }

    /** Kontext mit der gewaehlten Sprache (bei Systemsprache unveraendert) - fuer attachBaseContext jeder Activity. */
    static Context wrap(Context base) {
        String code = get(base);
        if (code.isEmpty()) return base;
        Locale loc = new Locale(code); Locale.setDefault(loc);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        return base.createConfigurationContext(cfg);
    }
}
