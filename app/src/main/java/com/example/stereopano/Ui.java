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

    static final int KARTE = 0xFF1E293B, RAND = 0xFF334155, TEXTC = 0xFFFFFFFF, GRAU = 0xFF94A3B8, AKZENT = 0xFF4FC3F7;

    private static android.graphics.drawable.GradientDrawable rund(float d, float r, int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(r * d);
        g.setColor(fill);
        g.setStroke((int) Math.max(1, d), stroke);
        return g;
    }

    /** Ein Dialog im gemeinsamen Stil (dunkle Karte, abgerundet, Akzentfarbe); statt AlertDialog.Builder verwenden. */
    static final class Dlg extends android.app.AlertDialog.Builder {
        private final Context ctx;

        Dlg(Context c) {
            super(c, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            ctx = c;
        }

        @Override
        public android.app.AlertDialog show() {
            android.app.AlertDialog dlg = create();
            dlg.show();
            float d = ctx.getResources().getDisplayMetrics().density;
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(rund(d, 16, KARTE, RAND), (int) (12 * d)));
            }
            int tid = ctx.getResources().getIdentifier("alertTitle", "id", "android");
            android.widget.TextView t = tid != 0 ? dlg.findViewById(tid) : null;
            if (t != null) t.setTextColor(TEXTC);
            android.widget.TextView m = dlg.findViewById(android.R.id.message);
            if (m != null) m.setTextColor(0xFFCBD5E1);
            for (int w : new int[]{android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL}) {
                android.widget.Button b = dlg.getButton(w);
                if (b != null) { b.setTextColor(AKZENT); b.setAllCaps(false); }
            }
            android.widget.ListView lv = dlg.getListView();
            if (lv != null) {
                lv.setDivider(new android.graphics.drawable.ColorDrawable(RAND));
                lv.setDividerHeight((int) Math.max(1, d));
            }
            return dlg;
        }
    }

    /** Ziehregler (SeekBar) in Akzentfarbe. */
    static void regler(android.widget.SeekBar s) {
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(AKZENT));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(AKZENT));
        s.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(RAND));
    }

    /** Kontrollkaestchen in Akzentfarbe. */
    static void haken(android.widget.CheckBox c) {
        c.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{AKZENT, GRAU}));
        c.setTextColor(TEXTC);
    }

    /** Schalter in Akzentfarbe. */
    static void schalter(android.widget.Switch sw) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{AKZENT, GRAU}));
        sw.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{0x884FC3F7, RAND}));
    }

    /** Eingabefeld: weisser Text, abgerundete Karte mit Rand. */
    static void eingabe(android.widget.EditText e) {
        float d = e.getResources().getDisplayMetrics().density;
        e.setTextColor(TEXTC);
        e.setHintTextColor(GRAU);
        e.setBackground(rund(d, 10, KARTE, RAND));
        e.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
    }
}
