package com.example.stereopano;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(48, 48, 48, 48);
        l.addView(knopf("Aufnehmen", CaptureActivity.class));
        l.addView(knopf("Ansehen", ViewerActivity.class));
        setContentView(l);
        Ui.applyInsets(l);
    }

    private Button knopf(String text, Class<?> ziel) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(v -> startActivity(new Intent(this, ziel)));
        return b;
    }
}
