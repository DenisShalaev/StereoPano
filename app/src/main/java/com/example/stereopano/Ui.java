package com.example.stereopano;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

final class Ui {
    private Ui() {}

    /** Edge-to-Edge (Android 15+): Inhalt unter Status-/Navigationsleiste verhindern. */
    static void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, ins) -> {
            Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
    }
}
