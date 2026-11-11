package com.example.stereopano;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Manuelle Naht: zeigt den ganzen aufgenommenen Streifen (360 Grad + Reserve) mit einem verschiebbaren 360-Grad-Rahmen und
 * darunter die Nahtstelle (Ende | Anfang des Rahmens). Nichts wird ueberblendet; erst beim Speichern wird die Helligkeit angeglichen.
 */
final class SeamPicker extends LinearLayout {
    interface Done { void ok(int shiftPx, int spanPx, int dyPx); void cancel(); }

    private final StereoStitcher.Result r;
    private final int w, ext, rows, row0;
    private int shift = 0;
    private int span;                 // Breite des Fensters in Kreisspalten (w = genau 360 Grad ohne Stauchung)
    private int dy = 0;               // vertikaler Versatz Ende gegen Anfang (Zeilen)
    private TextView deltaTxt, dyTxt;
    private final int dyMax;
    private boolean look;               // true: Finger auf der Vorschau verschiebt nichts (nur ansehen)
    private double lensRow = 0.5;      // Hoehe (Anteil 0..1) der festen Lupe
    private double lensZoom = 3;       // Pixel je Kreisspalte in der festen Lupe
    private LensPanel lens;
    private final StripView strip;
    private final PreviewView prev;

    SeamPicker(Context c, StereoStitcher.Result res, Done cb) {
        super(c);
        r = res;
        w = r.panoW;
        span = w;
        dyMax = Math.max(1, (int) Math.round(4.0 * w / 360.0));
        ext = r.extW;
        row0 = r.height > 0 ? r.y0 : 0;
        rows = r.height > 0 ? r.height : r.panoH;
        float d = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF0F172A);
        int pad = (int) (16 * d);
        setPadding(pad, pad, pad, pad);
        setOnApplyWindowInsetsListener((view, ins) -> {
            android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad);
            return android.view.WindowInsets.CONSUMED;
        });

        TextView title = new TextView(c);
        title.setText(R.string.seam_title);
        title.setTextColor(Ui.TEXTC);
        title.setTextSize(20);
        addView(title);
        TextView hint = new TextView(c);
        hint.setText(R.string.seam_hint);
        hint.setTextColor(Ui.GRAU);
        hint.setTextSize(13);
        hint.setPadding(0, (int) (4 * d), 0, (int) (10 * d));
        addView(hint);

        strip = new StripView(c);
        addView(strip, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (110 * d)));

        // Anzeige der Korrekturen (gesetzt wird per Finger auf der Nahtvorschau) und Auto
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (12 * d), 0, 0);
        deltaTxt = new TextView(c);
        deltaTxt.setTextColor(Ui.TEXTC);
        deltaTxt.setTextSize(14);
        deltaTxt.setMinWidth((int) (96 * d));
        row.addView(deltaTxt);
        dyTxt = new TextView(c);
        dyTxt.setTextColor(Ui.TEXTC);
        dyTxt.setTextSize(14);
        row.addView(dyTxt, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button auto = Ui.pille(c, R.string.seam_auto, false);
        auto.setOnClickListener(v -> {
            auto.setEnabled(false);
            Toast.makeText(c, R.string.seam_auto_running, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final double[] m = StereoStitcher.measureSpan(r);
                post(() -> {
                    auto.setEnabled(true);
                    if (m == null) Toast.makeText(c, R.string.seam_auto_none, Toast.LENGTH_LONG).show();
                    else { setSpan((int) Math.round(m[0])); setDy((int) Math.round(m[1])); }
                });
            }).start();
        });
        LayoutParams alp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        alp.leftMargin = (int) (8 * d);
        row.addView(auto, alp);
        addView(row);
        updateDelta();

        TextView pl = new TextView(c);
        pl.setText(R.string.seam_preview);
        pl.setTextColor(Ui.GRAU);
        pl.setTextSize(12);
        pl.setPadding(0, (int) (14 * d), 0, (int) (4 * d));
        addView(pl);
        prev = new PreviewView(c);
        // Vorschau + senkrechter Feinregler fuer die Hoehe direkt daneben
        LinearLayout pr = new LinearLayout(c);
        pr.setOrientation(HORIZONTAL);
        pr.addView(prev, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        final int fineV = Math.max(4, (int) Math.round(1.5 * w / 360.0));
        VSlider vs = new VSlider(c, fineV, new Fine() {
            int base;
            @Override public void start() { base = dy; }
            @Override public void set(int delta) { setDy(base + delta); }
        });
        pr.addView(vs, new LayoutParams((int) (48 * d), LayoutParams.MATCH_PARENT));
        addView(pr, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Feste Lupe: bleibt stehen, auch wenn man mit den Reglern feinjustiert
        lens = new LensPanel(c);
        LayoutParams llp = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (150 * d));
        llp.topMargin = (int) (8 * d);
        addView(lens, llp);

        // Feinregler: gelten relativ zum letzten Fingerwert und springen nach dem Loslassen zur Mitte zurueck
        final int fineH = Math.max(4, (int) Math.round(1.5 * w / 360.0));
        addView(fineRow(c, d, "\u2194", fineH, new Fine() {
            int base;
            @Override public void start() { base = span; }
            @Override public void set(int delta) { setSpan(base + delta); }
        }));

        LinearLayout tools = new LinearLayout(c);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(0, (int) (6 * d), 0, 0);
        final android.widget.Button mode = Ui.pille(c, R.string.seam_mode_move, false);
        mode.setOnClickListener(v -> {
            look = !look;
            mode.setText(look ? R.string.seam_mode_look : R.string.seam_mode_move);
            prev.invalidate();
        });
        tools.addView(mode);
        final TextView zt = new TextView(c);
        zt.setTextColor(Ui.GRAU);
        zt.setTextSize(13);
        zt.setPadding((int) (14 * d), 0, (int) (6 * d), 0);
        zt.setText(zoomText());
        android.widget.Button zm = Ui.pille(c, R.string.seam_mode_move, false), zp = Ui.pille(c, R.string.seam_mode_move, false);
        zm.setText("\u2212");
        zp.setText("+");
        final double[] levels = {1.5, 2, 3, 4, 6, 8};
        View.OnClickListener zl = v -> {
            int i = 0;
            for (int k = 0; k < levels.length; k++) if (Math.abs(levels[k] - lensZoom) < 0.01) i = k;
            i = Math.max(0, Math.min(levels.length - 1, i + (v == zp ? 1 : -1)));
            lensZoom = levels[i];
            zt.setText(zoomText());
            lens.invalidate();
        };
        zm.setOnClickListener(zl);
        zp.setOnClickListener(zl);
        tools.addView(zt);
        tools.addView(zm);
        tools.addView(zp);
        addView(tools);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.END);
        bar.setPadding(0, (int) (12 * d), 0, 0);
        android.widget.Button cancel = Ui.pille(c, R.string.seam_cancel, false);
        android.widget.Button save = Ui.pille(c, R.string.seam_save, true);
        cancel.setOnClickListener(v -> cb.cancel());
        save.setOnClickListener(v -> cb.ok(shift, span, dy));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (10 * d);
        bar.addView(cancel, lp);
        bar.addView(save);
        addView(bar);
    }

    private interface Fine { void start(); void set(int delta); }

    private String zoomText() { return String.format(java.util.Locale.US, "\ud83d\udd0d \u00d7%s", lensZoom == Math.floor(lensZoom) ? String.valueOf((int) lensZoom) : String.valueOf(lensZoom)); }

    private LinearLayout fineRow(Context c, float d, String sym, int half, Fine f) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(sym);
        t.setTextColor(Ui.TEXTC);
        t.setTextSize(16);
        t.setMinWidth((int) (28 * d));
        row.addView(t);
        final android.widget.SeekBar sb = new android.widget.SeekBar(c);
        Ui.regler(sb);
        sb.setMax(2 * half);
        sb.setProgress(half);
        final boolean[] user = {false};
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                if (fromUser && user[0]) f.set(p - half);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { user[0] = true; f.start(); }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { user[0] = false; s.setProgress(half); }
        });
        row.addView(sb, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
    }

    private void setSpan(int sp) {
        sp = Math.max(w - (int) (10.0 * w / 360.0), Math.min(sp, w + ext));
        span = sp;
        shift = Math.max(0, Math.min(shift, w + ext - span));
        updateDelta();
        strip.invalidate();
        prev.rebuild();
        if (lens != null) lens.invalidate();
    }

    private void setLensRow(float ty) {
        if (prev == null) return;
        double frac = prev.rowFrac(ty);
        lensRow = Math.max(0, Math.min(1, frac));
        if (lens != null) lens.invalidate();
    }

    private void setDy(int v) {
        dy = Math.max(-dyMax, Math.min(dyMax, v));
        updateDelta();
        prev.rebuild();
        if (lens != null) lens.invalidate();
    }

    private void updateDelta() {
        double deg = (span - w) * 360.0 / w;
        deltaTxt.setText(String.format(java.util.Locale.US, "\u2194 %s%.2f\u00b0", deg >= 0 ? "+" : "", deg));
        dyTxt.setText("\u2195 " + (dy >= 0 ? "+" : "") + dy + " px");
    }

    private int pixel(int v, int label) {
        int p = StereoStitcher.previewPixel(r, true, Math.max(0, Math.min(r.panoH - 1, v)), label);
        return p == 0 ? 0xFF000000 : (p | 0xFF000000);
    }

    /** Ganzer Streifen (Kreis + Erweiterung) verkleinert, mit Rahmen. */
    private final class StripView extends View {
        private Bitmap bmp;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX;
        private double k;      // Kreisspalten je Bildschirmpixel

        StripView(Context c) {
            super(c);
            dim.setColor(0xB30F172A);
            line.setColor(Ui.AKZENT);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3 * c.getResources().getDisplayMetrics().density);
        }

        @Override protected void onSizeChanged(int W, int H, int ow, int oh) {
            if (W <= 0 || H <= 0) return;
            int total = w + ext;
            int bw = Math.min(W, 1400), bh = Math.max(1, (int) ((long) rows * bw / total));
            bh = Math.min(bh, H);
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            int[] row = new int[bw];
            for (int y = 0; y < bh; y++) {
                int v = row0 + (int) ((long) y * rows / bh);
                for (int x = 0; x < bw; x++) row[x] = pixel(v, (int) ((long) x * total / bw));
                bmp.setPixels(row, 0, bw, 0, y, bw, 1);
            }
            k = total / (double) W;
        }

        @Override protected void onDraw(Canvas cv) {
            if (bmp == null) return;
            int W = getWidth(), H = getHeight();
            int th = (int) Math.min(H, (long) bmp.getHeight() * W / bmp.getWidth());
            int top = (H - th) / 2;
            cv.drawBitmap(bmp, null, new Rect(0, top, W, top + th), p);
            float x0 = (float) (shift / k), x1 = (float) ((shift + span) / k);
            cv.drawRect(0, top, x0, top + th, dim);
            cv.drawRect(x1, top, W, top + th, dim);
            float s = line.getStrokeWidth();
            cv.drawRoundRect(new RectF(x0, top + s / 2, x1, top + th - s / 2), s * 2, s * 2, line);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    shift = (int) Math.max(0, Math.min(w + ext - span, shift + (e.getX() - lastX) * k));
                    lastX = e.getX();
                    invalidate();
                    prev.rebuild();
                    return true;
                default:
                    return true;
            }
        }
    }

    /**
     * Nahtstelle: letzte 45 Grad des Rahmens | erste 45 Grad des Rahmens. Bedienung mit dem Finger:
     * waagerecht ziehen = Ende (linke Haelfte) gegen den Anfang verschieben (Kreiskorrektur),
     * am oberen oder unteren Rand ziehen = Hoehenversatz. Waehrend der Beruehrung zeigt eine Lupe die Nahtstelle vergroessert.
     */
    private final class PreviewView extends View {
        private static final float M = 3f;                 // Lupenvergroesserung gegenueber der Anzeige
        private Bitmap bmp, lensBmp;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int P = Math.max(8, (int) (45.0 * w / 360.0));
        private final float dens;
        private float sc = 1, bl, bt, bh = 1;                // Anzeigeabbildung des Bitmaps
        private boolean touching, vertical;
        private float tx, ty, lastX, lastY;
        private double accSpan, accDy;
        private final double[] hT = new double[16], hS = new double[16], hD = new double[16];
        private int hN;

        PreviewView(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
            line.setColor(Ui.AKZENT);
            line.setStrokeWidth(2 * dens);
            ring.setColor(Ui.AKZENT);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * dens);
            mark.setColor(0xCCFFFFFF);
            mark.setStrokeWidth(1.5f * dens);
        }

        @Override protected void onSizeChanged(int W, int H, int ow, int oh) { rebuild(); }

        double rowFrac(float y) { return bh > 0 ? (y - bt) / bh : 0.5; }

        /** Kreisspalten bzw. Zeilen je Anzeigepixel (Bitmap ist gleichmaessig skaliert). */
        private double perPx() { return (2.0 * P / 800.0) / sc; }

        void rebuild() {
            int pw = 800, ph = Math.max(1, (int) ((long) rows * pw / (2 * P)));
            if (bmp == null) bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            int[] row = new int[pw];
            for (int y = 0; y < ph; y++) {
                int v = row0 + (int) ((long) y * rows / ph);
                for (int x = 0; x < pw; x++) {
                    int j = (int) ((long) x * 2 * P / pw);
                    int label = j < P ? shift + span - P + j : shift + (j - P);
                    row[x] = pixel(j < P ? v + dy : v, label);
                }
                bmp.setPixels(row, 0, pw, 0, y, pw, 1);
            }
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    touching = true;
                    lastX = tx = e.getX();
                    lastY = ty = e.getY();
                    float rel = bh > 0 ? (ty - bt) / bh : 0.5f;
                    vertical = rel < 0.25f || rel > 0.75f;      // oberer / unterer Rand = Hoehe
                    accSpan = span;
                    accDy = dy;
                    hN = 0;
                    setLensRow(ty);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lastX, dyy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    tx = e.getX();
                    ty = e.getY();
                    double f = perPx();
                    setLensRow(ty);
                    if (!look) {
                        if (vertical) {
                            accDy -= dyy * f;                    // Ende nach unten ziehen = Quellzeile weiter oben
                            setDy((int) Math.round(accDy));
                        } else {
                            accSpan -= dx * f;                   // Ende nach rechts ziehen = frueherer Inhalt an der Naht
                            setSpan((int) Math.round(accSpan));
                        }
                        if (hN == hT.length) { System.arraycopy(hT, 1, hT, 0, hN - 1); System.arraycopy(hS, 1, hS, 0, hN - 1); System.arraycopy(hD, 1, hD, 0, hN - 1); hN--; }
                        hT[hN] = e.getEventTime(); hS[hN] = span; hD[hN] = dy; hN++;
                    }
                    invalidate();
                    return true;
                }
                default: {
                    // Beim Abheben rollt der Finger oft noch ein Stueck: den Wert von vor ~70 ms wiederherstellen, wenn es nur ein kleiner Weg war
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !look && hN > 0) {
                        double now = e.getEventTime(), lim = 8 * perPx();
                        int k = -1;
                        for (int i = hN - 1; i >= 0; i--) if (hT[i] <= now - 70) { k = i; break; }
                        if (k >= 0) {
                            if (vertical && Math.abs(dy - hD[k]) <= lim) setDy((int) hD[k]);
                            else if (!vertical && Math.abs(span - hS[k]) <= lim) setSpan((int) hS[k]);
                        }
                    }
                    touching = false;
                    invalidate();
                    return true;
                }
            }
        }

        @Override protected void onDraw(Canvas cv) {
            if (bmp == null) return;
            int W = getWidth(), H = getHeight();
            sc = Math.min(W / (float) bmp.getWidth(), H / (float) bmp.getHeight());
            float dw = bmp.getWidth() * sc, dh = bmp.getHeight() * sc;
            bl = (W - dw) / 2;
            bt = (H - dh) / 2;
            bh = dh;
            cv.drawBitmap(bmp, null, new RectF(bl, bt, bl + dw, bt + dh), p);
            cv.drawLine(W / 2f, bt, W / 2f, bt + dh, line);
            float my = (float) (bt + lensRow * dh);                  // Hoehe der festen Lupe
            cv.drawLine(bl, my, bl + 14 * dens, my, mark);
            cv.drawLine(bl + dw - 14 * dens, my, bl + dw, my, mark);
            if (touching) {
                if (vertical) {                                  // Hoehengriff sichtbar machen
                    float y = ty < bt + dh / 2 ? bt : bt + dh;
                    cv.drawLine(bl, y, bl + dw, y, ring);
                }
                drawLens(cv, W, H);
            }
        }

        /** Lupe: Nahtstelle in Hoehe des Fingers, dreifach vergroessert (links Ende, rechts Anfang), oberhalb des Fingers. */
        private void drawLens(Canvas cv, int W, int H) {
            int R = (int) (84 * dens), D = 2 * R;
            if (lensBmp == null || lensBmp.getWidth() != D) lensBmp = Bitmap.createBitmap(D, D, Bitmap.Config.ARGB_8888);
            double f = perPx() / M;                              // Kreisspalten bzw. Zeilen je Lupenpixel
            double v0 = row0 + (ty - bt) * perPx();              // Quellzeile unter dem Finger
            int[] px = new int[D];
            for (int y = 0; y < D; y++) {
                for (int x = 0; x < D; x++) {
                    int lx = x - R, ly = y - R;
                    if (lx * lx + ly * ly > R * R) { px[x] = 0; continue; }
                    int v = (int) Math.round(v0 + ly * f);
                    if (lx < 0) px[x] = pixel(v + dy, shift + span + (int) Math.floor(lx * f));
                    else px[x] = pixel(v, shift + (int) Math.floor(lx * f));
                }
                lensBmp.setPixels(px, 0, D, 0, y, D, 1);
            }
            float cx = Math.max(R, Math.min(W - R, tx));
            float cy = ty - R * 1.5f;
            if (cy < R) cy = ty + R * 1.5f;
            cy = Math.max(R, Math.min(H - R, cy));
            cv.drawBitmap(lensBmp, cx - R, cy - R, p);
            cv.drawCircle(cx, cy, R, ring);
            cv.drawLine(cx, cy - R, cx, cy + R, mark);           // Naht
            cv.drawLine(cx - R, cy, cx + R, cy, mark);           // Hoehe des Fingers
        }
    }

    /** Feste Lupe: Nahtstelle in der gewaehlten Hoehe, links Ende (mit Hoehenversatz), rechts Anfang; bleibt beim Feinjustieren stehen. */
    private final class LensPanel extends View {
        private Bitmap bmp;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);

        LensPanel(Context c) {
            super(c);
            float dn = c.getResources().getDisplayMetrics().density;
            line.setColor(Ui.AKZENT);
            line.setStrokeWidth(2 * dn);
            mark.setColor(0x88FFFFFF);
            mark.setStrokeWidth(1 * dn);
        }

        @Override protected void onDraw(Canvas cv) {
            int W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            if (bmp == null || bmp.getWidth() != W || bmp.getHeight() != H) bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            double v0 = row0 + lensRow * rows;
            int[] px = new int[W];
            for (int y = 0; y < H; y++) {
                int v = (int) Math.round(v0 + (y - H / 2) / lensZoom);
                for (int x = 0; x < W; x++) {
                    int off = (int) Math.floor((x - W / 2) / lensZoom);
                    px[x] = x < W / 2 ? pixel(v + dy, shift + span + off) : pixel(v, shift + off);
                }
                bmp.setPixels(px, 0, W, 0, y, W, 1);
            }
            cv.drawBitmap(bmp, 0, 0, p);
            cv.drawLine(W / 2f, 0, W / 2f, H, line);
            cv.drawLine(0, H / 2f, W, H / 2f, mark);
        }
    }

    /** Senkrechter Feinregler (Mitte = 0, oben = Bild nach oben); springt nach dem Loslassen zur Mitte zurueck. */
    private final class VSlider extends View {
        private final int half;
        private final Fine f;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double val;                    // -1 .. 1, positiv = oben
        private final float dn;

        VSlider(Context c, int half, Fine f) {
            super(c);
            this.half = half;
            this.f = f;
            dn = c.getResources().getDisplayMetrics().density;
            track.setColor(0xFF334155);
            track.setStrokeWidth(4 * dn);
            track.setStrokeCap(Paint.Cap.ROUND);
            tick.setColor(0xFF64748B);
            tick.setStrokeWidth(2 * dn);
            thumb.setColor(Ui.AKZENT);
        }

        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight(), pad = 18 * dn, cx = W / 2;
            cv.drawLine(cx, pad, cx, H - pad, track);
            cv.drawLine(cx - 8 * dn, H / 2, cx + 8 * dn, H / 2, tick);
            float y = (float) (H / 2 - val * (H / 2 - pad));
            cv.drawCircle(cx, y, 13 * dn, thumb);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float H = getHeight(), pad = 18 * dn;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    f.start();
                case MotionEvent.ACTION_MOVE:
                    val = Math.max(-1, Math.min(1, (H / 2 - e.getY()) / (H / 2 - pad)));
                    f.set((int) Math.round(val * half));
                    invalidate();
                    return true;
                default:
                    val = 0;
                    invalidate();
                    return true;
            }
        }
    }
}
