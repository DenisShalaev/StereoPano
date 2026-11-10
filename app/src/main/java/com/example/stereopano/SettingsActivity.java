package com.example.stereopano;

import android.app.Activity;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Einstellungen (Zahnrad). Alle Werte werden sofort gespeichert; Aufnahme- und Betrachter-Bildschirm
 * lesen sie beim naechsten Anzeigen (onResume) neu ein.
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFF0F172A, KARTE = 0xFF1E293B, RAND = 0xFF334155;
    private static final int TEXT = Color.WHITE, GRAU = 0xFF94A3B8, AKZENT = 0xFF4FC3F7;

    private SharedPreferences sp;
    private LinearLayout liste;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Settings.p(this);
        d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Kopfzeile: Zurueck | Zahnrad | Titel
        LinearLayout kopf = new LinearLayout(this);
        kopf.setOrientation(LinearLayout.HORIZONTAL);
        kopf.setGravity(Gravity.CENTER_VERTICAL);
        kopf.setPadding(dp(8), dp(8), dp(16), dp(8));
        TextView zurueck = new TextView(this);
        zurueck.setText("\u2190");
        zurueck.setTextSize(24);
        zurueck.setTextColor(TEXT);
        zurueck.setGravity(Gravity.CENTER);
        zurueck.setContentDescription(getString(R.string.cd_back));
        zurueck.setOnClickListener(v -> finish());
        kopf.addView(zurueck, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageView zahnrad = new ImageView(this);
        zahnrad.setImageResource(R.drawable.ic_settings);
        kopf.addView(zahnrad, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView titel = new TextView(this);
        titel.setText(R.string.settings_title);
        titel.setTextSize(20);
        titel.setTextColor(TEXT);
        titel.setPadding(dp(12), 0, 0, 0);
        kopf.addView(titel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(kopf);

        ScrollView sv = new ScrollView(this);
        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        liste.setPadding(dp(16), 0, dp(16), dp(24));
        sv.addView(liste);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyInsets(root);
        baue();
    }

    private int dp(int v) { return (int) (v * d + 0.5f); }

    // ------------------------------------------------------------------ Inhalt

    private void baue() {
        liste.removeAllViews();
        Locale loc = Locale.getDefault();

        abschnitt(R.string.sec_capture);
        regler(R.string.set_angle_t, R.string.set_angle_d, Settings.K_WINKEL, 15, 60, 1, 35,
                v -> String.format(loc, "%.1f\u00B0", v / 10.0));
        regler(R.string.set_delta_t, R.string.set_delta_d, Settings.K_DELTA, 2, 15, 1, 6,
                v -> String.format(loc, "%.1f\u00B0", v / 10.0));
        regler(R.string.set_tempo_t, R.string.set_tempo_d, Settings.K_TEMPO, 4, 15, 1, 8, v -> v + "\u00B0/s");
        auswahl(R.string.set_res_t, R.string.set_res_d, Settings.K_BREITE, new int[]{960, 1280, 1600, 1920},
                new String[]{getString(R.string.res_960), getString(R.string.res_1280), getString(R.string.res_1600),
                        getString(R.string.res_1920)}, 1920);
        regler(R.string.set_move_t, R.string.set_move_d, Settings.K_MOVE, 0, 100, 5, 0,
                v -> v == 0 ? getString(R.string.off) : v + " %");
        regler(R.string.set_sharp_t, R.string.set_sharp_d, Settings.K_SHARP, 0, 100, 5, 50,
                v -> v == 0 ? getString(R.string.off) : v + " %");
        regler(R.string.set_quality_t, R.string.set_quality_d, Settings.K_QUALI, 70, 100, 1, 90, v -> v + " %");
        schalter(R.string.set_ae_t, R.string.set_ae_d, Settings.K_AE, true);
        schalter(R.string.set_ghost_t, R.string.set_ghost_d, Settings.K_GHOST, false);
        schalter(R.string.set_guides_t, R.string.set_guides_d, Settings.K_LEIT, true);
        schalter(R.string.set_audio_t, R.string.set_audio_d, Settings.K_AUDIO, true);

        abschnitt(R.string.sec_calibration);
        regler(R.string.set_latency_t, R.string.set_latency_d, Settings.K_LATENZ, -50, 50, 1, 0, v -> v + " ms");
        regler(R.string.set_stab_t, R.string.set_stab_d, Settings.K_STAB, 2, 20, 1, 6,
                v -> v >= 20 ? getString(R.string.off) : v + "\u00B0/s");
        auswahl(R.string.set_loop_t, R.string.set_loop_d, Settings.K_LOOPMODE, new int[]{0, 1},
                new String[]{getString(R.string.loop_auto), getString(R.string.loop_gyro)}, 0);
        regler(R.string.set_wcorr_t, R.string.set_wcorr_d, Settings.K_WKORR, 0, 100, 1, 35, v -> String.format(java.util.Locale.US, "%.1f %%", v / 10.0));
        regler(R.string.set_wcorr_uw_t, R.string.set_wcorr_uw_d, Settings.K_WKORR_UW, 0, 100, 1, 44, v -> String.format(java.util.Locale.US, "%.1f %%", v / 10.0));
        regler(R.string.set_focal_t, R.string.set_focal_d, Settings.K_FPX, 90, 110, 1, 100, v -> v + " %");

        abschnitt(R.string.sec_viewer);
        schalter(R.string.set_compass_t, R.string.set_compass_d, Settings.K_KOMPASS, true);
        schalter(R.string.set_gyro_t, R.string.set_gyro_d, Settings.K_GYRO, true);
        schalter(R.string.set_lens_t, R.string.set_lens_d, Settings.K_LINSEN, true);
        regler(R.string.set_k1_t, R.string.set_k1_d, Settings.K_K1, 0, 80, 1, 34, v -> String.format(loc, "%.2f", v / 100.0));
        regler(R.string.set_k2_t, R.string.set_k2_d, Settings.K_K2, 0, 120, 1, 55, v -> String.format(loc, "%.2f", v / 100.0));
        schalter(R.string.set_half_t, R.string.set_half_d, Settings.K_HALB, false);
        regler(R.string.set_sbsfov_t, R.string.set_sbsfov_d, Settings.K_SBSFOV, 30, 80, 1, 45, v -> v + "\u00B0");

        abschnitt(R.string.sec_files);
        textfeld(R.string.set_author_t, R.string.set_author_d, Settings.K_AUTHOR);
        schalter(R.string.set_loc_t, R.string.set_loc_d, Settings.K_LOC, true);
        schalter(R.string.set_all_t, R.string.set_all_d, Settings.K_ALL, false);

        abschnitt(R.string.sec_google);
        auswahl(R.string.set_target_t, R.string.set_target_d, Settings.K_SAVE, new int[]{0, 1, 2},
                new String[]{getString(R.string.target_phone), getString(R.string.target_both), getString(R.string.target_google)}, 0);
        kontoZeile();

        abschnitt(R.string.sec_about);
        TextView info = new TextView(this);
        info.setText(getString(R.string.about_text, version()) + "\nBuild " + BuildInfo.NUMBER);
        info.setTextColor(GRAU);
        info.setTextSize(13);
        info.setPadding(dp(4), dp(4), dp(4), dp(12));
        liste.addView(info);

        TextView reset = new TextView(this);
        reset.setText(R.string.reset_defaults);
        reset.setTextColor(0xFFFF8A80);
        reset.setTextSize(15);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(16), dp(14), dp(16), dp(14));
        reset.setBackground(rund(KARTE, RAND));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset, (dlg, w) -> {
                    Settings.zuruecksetzen(this);
                    baue();
                    Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
                }).show());
        liste.addView(reset);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ Bausteine

    private GradientDrawable rund(int fuell, int rand) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(fuell);
        g.setStroke(dp(1), rand);
        return g;
    }

    private void abschnitt(int res) {
        TextView v = new TextView(this);
        v.setText(getString(res).toUpperCase(Locale.getDefault()));
        v.setTextColor(AKZENT);
        v.setTextSize(12);
        v.setLetterSpacing(0.1f);
        v.setPadding(dp(4), dp(20), dp(4), dp(8));
        liste.addView(v);
    }

    private LinearLayout karte() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(12));
        l.setBackground(rund(KARTE, RAND));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        liste.addView(l, lp);
        return l;
    }

    private TextView text(String s, int farbe, int groesse) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(farbe);
        t.setTextSize(groesse);
        return t;
    }

    private void schalter(int titel, int info, String key, boolean def) {
        LinearLayout k = karte();
        LinearLayout zeile = new LinearLayout(this);
        zeile.setOrientation(LinearLayout.HORIZONTAL);
        zeile.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(getString(titel), TEXT, 16));
        TextView i = text(getString(info), GRAU, 12);
        i.setPadding(0, dp(2), dp(8), 0);
        tx.addView(i);
        zeile.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, an) -> sp.edit().putBoolean(key, an).apply());
        zeile.addView(sw);
        k.addView(zeile);
        k.setOnClickListener(v -> sw.toggle());
    }

    private void regler(int titel, int info, String key, int min, int max, int step, int def, IntFunction<String> fmt) {
        LinearLayout k = karte();
        LinearLayout zeile = new LinearLayout(this);
        zeile.setOrientation(LinearLayout.HORIZONTAL);
        zeile.addView(text(getString(titel), TEXT, 16), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int cur = Math.max(min, Math.min(max, sp.getInt(key, def)));
        TextView wert = text(fmt.apply(cur), AKZENT, 15);
        zeile.addView(wert);
        k.addView(zeile);
        TextView i = text(getString(info), GRAU, 12);
        i.setPadding(0, dp(2), 0, dp(4));
        k.addView(i);
        SeekBar sb = new SeekBar(this);
        sb.setMax((max - min) / step);
        sb.setProgress((cur - min) / step);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = min + p * step;
                wert.setText(fmt.apply(v));
                if (fromUser) sp.edit().putInt(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        k.addView(sb);
    }

    private void auswahl(int titel, int info, String key, int[] werte, String[] labels, int def) {
        LinearLayout k = karte();
        LinearLayout zeile = new LinearLayout(this);
        zeile.setOrientation(LinearLayout.HORIZONTAL);
        zeile.addView(text(getString(titel), TEXT, 16), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView wert = text(labels[index(werte, sp.getInt(key, def))], AKZENT, 14);
        zeile.addView(wert);
        k.addView(zeile);
        TextView i = text(getString(info), GRAU, 12);
        i.setPadding(0, dp(2), 0, 0);
        k.addView(i);
        k.setOnClickListener((View v) -> new AlertDialog.Builder(this).setTitle(getString(titel))
                .setItems(labels, (dlg, w) -> {
                    sp.edit().putInt(key, werte[w]).apply();
                    wert.setText(labels[w]);
                }).show());
    }

    private static final int REQ_KONTO = 21, REQ_GKONTO = 22;
    private TextView gKontoWert;

    private void kontoZeile() {
        LinearLayout k = karte();
        LinearLayout zeile = new LinearLayout(this);
        zeile.setOrientation(LinearLayout.HORIZONTAL);
        zeile.addView(text(getString(R.string.set_gacc_t), TEXT, 16), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String a = sp.getString(Settings.K_GACC, "");
        gKontoWert = text(a.isEmpty() ? getString(R.string.gp_none) : a, AKZENT, 14);
        zeile.addView(gKontoWert);
        k.addView(zeile);
        TextView i = text(getString(R.string.set_gacc_d), GRAU, 12);
        i.setPadding(0, dp(2), 0, 0);
        k.addView(i);
        k.setOnClickListener(v -> {
            try {
                startActivityForResult(AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"}, null, null, null, null), REQ_GKONTO);
            } catch (Exception e) {
                Toast.makeText(this, R.string.account_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private TextView autorWert;

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_GKONTO && res == RESULT_OK && data != null) {
            String n = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (n != null && !n.trim().isEmpty()) {
                sp.edit().putString(Settings.K_GACC, n.trim()).apply();
                if (gKontoWert != null) gKontoWert.setText(n.trim());
            }
        }
        if (req == REQ_KONTO && res == RESULT_OK && data != null) {
            String n = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (n != null && !n.trim().isEmpty()) {
                sp.edit().putString(Settings.K_AUTHOR, n.trim()).apply();
                if (autorWert != null) autorWert.setText(n.trim());
            }
        }
    }

    /** Google-Konto waehlen (Systemdialog, keine Berechtigung noetig); liefert die E-Mail-Adresse des Kontos. */
    @SuppressWarnings("deprecation")
    private void waehleKonto() {
        try {
            Intent i = AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"}, null, null, null, null);
            startActivityForResult(i, REQ_KONTO);
        } catch (Exception e) {
            Toast.makeText(this, R.string.account_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Zeile mit einem Textwert (z. B. Autorenname); Antippen oeffnet ein Eingabefenster. */
    private void textfeld(int titel, int info, String key) {
        LinearLayout k = karte();
        LinearLayout zeile = new LinearLayout(this);
        zeile.setOrientation(LinearLayout.HORIZONTAL);
        zeile.addView(text(getString(titel), TEXT, 16), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String cur = sp.getString(key, "");
        TextView wert = text(cur == null || cur.isEmpty() ? "\u2014" : cur, AKZENT, 14);
        zeile.addView(wert);
        if (Settings.K_AUTHOR.equals(key)) autorWert = wert;
        k.addView(zeile);
        TextView i = text(getString(info), GRAU, 12);
        i.setPadding(0, dp(2), 0, 0);
        k.addView(i);
        k.setOnClickListener(v -> {
            final android.widget.EditText et = new android.widget.EditText(this);
            et.setSingleLine(true);
            et.setText(sp.getString(key, ""));
            et.setSelection(et.getText().length());
            AlertDialog.Builder db = new AlertDialog.Builder(this).setTitle(getString(titel)).setView(et)
                    .setNegativeButton(R.string.cancel, null);
            if (Settings.K_AUTHOR.equals(key)) db.setNeutralButton(R.string.from_google, (dlg, w) -> waehleKonto());
            db
                    .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                        String t = et.getText().toString().trim();
                        sp.edit().putString(key, t).apply();
                        wert.setText(t.isEmpty() ? "\u2014" : t);
                    }).show();
        });
    }

    private static int index(int[] werte, int v) {
        for (int i = 0; i < werte.length; i++) if (werte[i] == v) return i;
        return 0;
    }
}
