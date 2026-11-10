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
    private android.widget.SeekBar dyBar;
    private android.widget.SeekBar deltaBar;
    private boolean setting;
    private final StripView strip;
    private final PreviewView prev;

    SeamPicker(Context c, StereoStitcher.Result res, Done cb) {
        super(c);
        r = res;
        w = r.panoW;
        span = w;
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

        // Kreiskorrektur: wie viele Grad der Umlauf wirklich hat (Fenster wird beim Speichern auf 360 Grad gestaucht/gedehnt)
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (12 * d), 0, 0);
        deltaTxt = new TextView(c);
        deltaTxt.setTextColor(Ui.GRAU);
        deltaTxt.setTextSize(12);
        deltaTxt.setMinWidth((int) (84 * d));
        row.addView(deltaTxt);
        deltaBar = new android.widget.SeekBar(c);
        Ui.regler(deltaBar);
        final double ppd = w / 360.0;
        final int minDeg10 = -100, maxDeg10 = (int) Math.round(ext / ppd * 10);
        deltaBar.setMax(maxDeg10 - minDeg10);
        deltaBar.setProgress(-minDeg10);
        deltaBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                if (!user || setting) return;
                setSpan(w + (int) Math.round((p + minDeg10) / 10.0 * ppd));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        row.addView(deltaBar, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
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
        // vertikaler Versatz (z. B. Wand/Decke an der Naht)
        LinearLayout row2 = new LinearLayout(c);
        row2.setOrientation(HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        dyTxt = new TextView(c);
        dyTxt.setTextColor(Ui.GRAU);
        dyTxt.setTextSize(12);
        dyTxt.setMinWidth((int) (84 * d));
        row2.addView(dyTxt);
        final int dyMax = Math.max(1, (int) Math.round(4.0 * w / 360.0));
        dyBar = new android.widget.SeekBar(c);
        Ui.regler(dyBar);
        dyBar.setMax(2 * dyMax);
        dyBar.setProgress(dyMax);
        dyBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                if (!user || setting) return;
                setDy(p - dyMax);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        row2.addView(dyBar, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(row2);
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
        int mx = dyBar.getMax() / 2;
        dy = Math.max(-mx, Math.min(mx, v));
        updateDelta();
        prev.rebuild();
    }

    private void updateDelta() {
        double deg = (span - w) * 360.0 / w;
        deltaTxt.setText(String.format(java.util.Locale.US, "%s%.1f°", deg >= 0 ? "+" : "", deg));
        setting = true;
        deltaBar.setProgress((int) Math.round(deg * 10) + 100);
        dyTxt.setText("\u2195 " + (dy >= 0 ? "+" : "") + dy + " px");
        dyBar.setProgress(dy + dyBar.getMax() / 2);
        setting = false;
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

    /** Nahtstelle: letzte 45 Grad des Rahmens | erste 45 Grad des Rahmens. */
    private final class PreviewView extends View {
        private Bitmap bmp;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int P = Math.max(8, (int) (45.0 * w / 360.0));

        PreviewView(Context c) {
            super(c);
            line.setColor(Ui.AKZENT);
            line.setStrokeWidth(2 * c.getResources().getDisplayMetrics().density);
        }

        @Override protected void onSizeChanged(int W, int H, int ow, int oh) { rebuild(); }

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

        @Override protected void onDraw(Canvas cv) {
            if (bmp == null) return;
            int W = getWidth(), H = getHeight();
            float sc = Math.min(W / (float) bmp.getWidth(), H / (float) bmp.getHeight());
            float dw = bmp.getWidth() * sc, dh = bmp.getHeight() * sc;
            float l = (W - dw) / 2, t = (H - dh) / 2;
            cv.drawBitmap(bmp, null, new RectF(l, t, l + dw, t + dh), p);
            cv.drawLine(W / 2f, t, W / 2f, t + dh, line);
        }
    }
}
