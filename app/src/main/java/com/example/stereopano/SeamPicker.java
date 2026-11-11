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
        addView(prev, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

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
    }

    private void setDy(int v) {
        dy = Math.max(-dyMax, Math.min(dyMax, v));
        updateDelta();
        prev.rebuild();
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
                    if (vertical) {
                        accDy -= dyy * f;                        // Ende nach unten ziehen = Quellzeile weiter oben
                        setDy((int) Math.round(accDy));
                    } else {
                        accSpan -= dx * f;                       // Ende nach rechts ziehen = frueherer Inhalt an der Naht
                        setSpan((int) Math.round(accSpan));
                    }
                    invalidate();
                    return true;
                }
                default:
                    touching = false;
                    invalidate();
                    return true;
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
}
