package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Isolated native surface: no family, network, messages or recording. */
public class ChatMediaFixtureActivity extends Activity {
    public ImageView image;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        image = new ImageView(this);
        root.addView(image, new LinearLayout.LayoutParams(256, 256));
        setContentView(root);
    }
}
