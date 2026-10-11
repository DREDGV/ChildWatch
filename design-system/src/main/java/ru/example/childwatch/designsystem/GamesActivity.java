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
        bind(R.id.gamesArcanoid, R.id.gamesArcanoidArtwork, GameArtworkView.ARKANOID);
        bind(R.id.gamesBattleship, R.id.gamesBattleshipArtwork, GameArtworkView.BATTLESHIP);
        bind(R.id.gamesSnake, R.id.gamesSnakeArtwork, GameArtworkView.SNAKE);
    }
    private void bind(int card, int artwork, String game) {
        ((GameArtworkView)findViewById(artwork)).setGame(game);
        int title, description, modes;
        if(GameArtworkView.BATTLESHIP.equals(game)) {
            title=R.string.cw_catalog_battleship_title; description=R.string.cw_catalog_battleship_desc;
            modes=R.string.cw_catalog_battleship_modes;
        } else if(GameArtworkView.SNAKE.equals(game)) {
            title=R.string.cw_catalog_snake_title; description=R.string.cw_catalog_snake_desc;
            modes=R.string.cw_catalog_solo;
        } else {
            title=R.string.cw_arcanoid_title; description=R.string.cw_catalog_arkanoid_desc;
            modes=R.string.cw_catalog_solo;
        }
        findViewById(card).setContentDescription(getString(title)+". "+getString(description)+" "+getString(modes)+". "+getString(R.string.cw_catalog_choose));
        findViewById(card).setOnClickListener(view -> startActivity(
            new Intent(this, GameDetailsActivity.class).putExtra(GameDetailsActivity.EXTRA_GAME, game)));
    }
}
