package com.example.stereopano;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F172A);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText(R.string.title_settings);
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, 32);
        root.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(32, 32, 32, 32);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(24);
        bg.setColor(0xFF1E293B);
        bg.setStroke(2, 0xFF334155);
        card.setBackground(bg);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(120, 120);
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.bottomMargin = 16;
        card.addView(logo, logoLp);

        TextView appName = new TextView(this);
        appName.setText(R.string.app_name);
        appName.setTextSize(20);
        appName.setTextColor(Color.WHITE);
        appName.setGravity(Gravity.CENTER);
        card.addView(appName);

        TextView about = new TextView(this);
        about.setText(R.string.settings_about);
        about.setTextSize(14);
        about.setTextColor(0xFF94A3B8);
        about.setGravity(Gravity.CENTER);
        card.addView(about);

        root.addView(card);

        setContentView(root);
        Ui.applyInsets(root);
    }
}
