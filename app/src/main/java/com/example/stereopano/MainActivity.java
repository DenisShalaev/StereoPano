package com.example.stereopano;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 64, 64, 64);
        root.setBackgroundColor(0xFF0F172A);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(240, 240);
        logoLp.bottomMargin = 32;
        root.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText("StereoPano");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("3D & VR Panorama Camera");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF94A3B8);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = 80;
        root.addView(subtitle, subLp);

        root.addView(cardButton("Aufnehmen", "Stereo-Panorama aufnehmen", R.drawable.ic_capture, CaptureActivity.class));
        root.addView(cardButton("Galerie", "Aufnahmen ansehen & teilen", R.drawable.ic_gallery, GalleryActivity.class));

        setContentView(root);
        Ui.applyInsets(root);
    }

    private View cardButton(String mainText, String subText, int iconRes, Class<?> targetActivity) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(40, 32, 40, 32);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(32);
        bg.setColor(0xFF1E293B);
        bg.setStroke(2, 0xFF334155);
        btn.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(72, 72);
        iconLp.rightMargin = 32;
        btn.addView(icon, iconLp);

        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);

        TextView tvMain = new TextView(this);
        tvMain.setText(mainText);
        tvMain.setTextSize(18);
        tvMain.setTextColor(Color.WHITE);

        TextView tvSub = new TextView(this);
        tvSub.setText(subText);
        tvSub.setTextSize(12);
        tvSub.setTextColor(0xFF94A3B8);

        textLayout.addView(tvMain);
        textLayout.addView(tvSub);

        btn.addView(textLayout, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btn.setOnClickListener(v -> startActivity(new Intent(this, targetActivity)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 24;
        return btn;
    }
}
