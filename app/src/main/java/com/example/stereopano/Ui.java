package com.example.stereopano;

import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;

/** Gemeinsame UI-Helfer: einheitliche runde Symbol-Knoepfe (ohne Beschriftung) und Insets. */
final class Ui {
    static final int AUS = 0, AN = 1, NICHT_VERFUEGBAR = 2, NORMAL = 3;

    private Ui() {}

    /** ImageView, die immer quadratisch bleibt (kleinere Seite gewinnt) -> bleibt rund, auch im Querformat. */
    static final class RundKnopf extends ImageView {
        RundKnopf(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            int s = Math.min(getMeasuredWidth(), getMeasuredHeight());
            setMeasuredDimension(s, s);
        }
    }

    /** Runder Symbol-Knopf. Die Beschreibung dient Bedienungshilfen und als Tooltip (lange druecken). */
    static ImageView runderKnopf(Context c, int icon, int beschreibung) {
        float d = c.getResources().getDisplayMetrics().density;
        ImageView v = new RundKnopf(c);
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = (int) (10 * d);
        v.setPadding(p, p, p, p);
        v.setContentDescription(c.getString(beschreibung));
        v.setTooltipText(c.getString(beschreibung));
        zustand(v, NORMAL);
        return v;
    }

    /** NORMAL = einfacher Knopf, AN/AUS = Schalter (AN blau hinterlegt), NICHT_VERFUEGBAR = abgeblendet. */
    static void zustand(ImageView v, int z) {
        float d = v.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(z == AN ? 0xE61E88E5 : 0x73000000);
        bg.setStroke((int) Math.max(1, d), z == AN ? 0xFFFFFFFF : 0x66FFFFFF);
        v.setBackground(bg);
        v.setImageAlpha(z == AUS ? 150 : z == NICHT_VERFUEGBAR ? 70 : 255);
    }

    /** Runder Zahnrad-Knopf, oeffnet die Einstellungen. */
    static ImageView zahnrad(Context c) {
        ImageView v = runderKnopf(c, R.drawable.ic_settings, R.string.cd_settings);
        v.setOnClickListener(x -> c.startActivity(new Intent(c, SettingsActivity.class)));
        return v;
    }

    /** Edge-to-Edge (Android 15+): Inhalt unter Status-/Navigationsleiste verhindern. */
    static void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, ins) -> {
            Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    /** Textknopf im gemeinsamen Stil (abgerundet, Karte mit Rand; primaer = Akzentfarbe). */
    static android.widget.Button pille(Context c, int textRes, boolean primaer) {
        float d = c.getResources().getDisplayMetrics().density;
        android.widget.Button b = new android.widget.Button(c);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primaer ? 0xFF000000 : 0xFFFFFFFF);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(14 * d);
        g.setColor(primaer ? 0xFF4FC3F7 : 0xFF1E293B);
        g.setStroke((int) Math.max(1, d), primaer ? 0xFF4FC3F7 : 0xFF334155);
        b.setBackground(g);
        return b;
    }
}
