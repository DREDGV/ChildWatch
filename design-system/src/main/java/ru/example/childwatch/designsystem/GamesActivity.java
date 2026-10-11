package ru.example.childwatch.designsystem;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Dedicated catalog shared by both applications; only ready games are listed. */
public class GamesActivity extends AppCompatActivity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.cw_games_menu);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.gamesRoot), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        findViewById(R.id.gamesClose).setOnClickListener(view -> finish());
        findViewById(R.id.gamesArcanoid).setOnClickListener(view ->
            startActivity(new Intent(this, ArcanoidActivity.class)));
    }
}
