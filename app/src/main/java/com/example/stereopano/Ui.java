package com.example.stereopano;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.Toast;

final class Ui {
    private Ui() {}

    /** Edge-to-Edge: Inhalt unter Status-/Navigationsleiste verhindern. */
    static void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, ins) -> {
            Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    public enum ButtonState { ACTIVE, INACTIVE, DISABLED }

    static ImageButton createRoundButton(Context context, int iconResId, int titleResId, View.OnClickListener onClickListener) {
        ImageButton btn = new ImageButton(context);
        btn.setImageResource(iconResId);
        btn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);

        int dp10 = (int) (10 * context.getResources().getDisplayMetrics().density + 0.5f);
        btn.setPadding(dp10, dp10, dp10, dp10);

        String title = context.getString(titleResId);
        btn.setContentDescription(title);

        btn.setOnClickListener(v -> {
            if (onClickListener != null) onClickListener.onClick(v);
        });

        btn.setOnLongClickListener(v -> {
            Toast.makeText(context, title, Toast.LENGTH_SHORT).show();
            return true;
        });

        setRoundButtonState(btn, ButtonState.INACTIVE);
        return btn;
    }

    static void setRoundButtonState(ImageButton btn, ButtonState state) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);

        switch (state) {
            case ACTIVE:
                bg.setColor(0xFF00B0FF);
                bg.setStroke(2, 0xFFE0F7FA);
                btn.setImageAlpha(255);
                btn.setEnabled(true);
                break;
            case INACTIVE:
                bg.setColor(0x55000000);
                bg.setStroke(2, 0x66FFFFFF);
                btn.setImageAlpha(220);
                btn.setEnabled(true);
                break;
            case DISABLED:
                bg.setColor(0x1A000000);
                bg.setStroke(2, 0x22FFFFFF);
                btn.setImageAlpha(80);
                btn.setEnabled(false);
                break;
        }
        btn.setBackground(bg);
    }
}
