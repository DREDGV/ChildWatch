package ru.example.childwatch.designsystem;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;

/** Full-page rules and mode selection; unavailable modes are never playable placeholders. */
public final class GameDetailsActivity extends AppCompatActivity {
    public static final String EXTRA_GAME="game";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        String game=getIntent().getStringExtra(EXTRA_GAME);
        if(!GameArtworkView.ARKANOID.equals(game)&&!GameArtworkView.BATTLESHIP.equals(game)&&!GameArtworkView.SNAKE.equals(game)){finish();return;}
        boolean sea=GameArtworkView.BATTLESHIP.equals(game),snake=GameArtworkView.SNAKE.equals(game);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this,R.color.cw_color_background));setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setPadding(dp(12),dp(8),dp(12),dp(8));root.addView(header);
        MaterialButton back=button(R.string.cw_games_close,false);header.addView(back);back.setOnClickListener(v->finish());
        NestedScrollView scroll=new NestedScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),dp(24));scroll.addView(body);
        TextView title=text(sea?R.string.cw_catalog_battleship_title:snake?R.string.cw_catalog_snake_title:R.string.cw_arcanoid_title,28,true);
        ViewCompat.setAccessibilityHeading(title,true);body.addView(title);
        GameArtworkView art=new GameArtworkView(this);art.setGame(game);LinearLayout.LayoutParams artParams=new LinearLayout.LayoutParams(-1,dp(184));artParams.topMargin=dp(20);body.addView(art,artParams);
        TextView rulesHeading=text(R.string.cw_catalog_rules,20,true);ViewCompat.setAccessibilityHeading(rulesHeading,true);add(body,rulesHeading,24);
        add(body,text(sea?R.string.cw_catalog_battleship_rules:snake?R.string.cw_catalog_snake_rules:R.string.cw_catalog_arkanoid_rules,16,false),8);
        MaterialButton solo=button(sea?R.string.cw_catalog_computer:R.string.cw_catalog_play,true);solo.setId(R.id.gameDetailsSolo);add(body,solo,24);
        solo.setOnClickListener(v->startActivity(new Intent(this,sea?BattleshipActivity.class:snake?SnakeActivity.class:ArcanoidActivity.class)));
        if(sea){MaterialButton family=button(R.string.cw_catalog_family,false);family.setId(R.id.gameDetailsFamily);add(body,family,8);
            family.setOnClickListener(v->startActivity(new Intent(this,FamilyGameLobbyActivity.class)));
            add(body,text(R.string.cw_catalog_family_connection,14,false),8);
        }
    }
    private TextView text(int res,int size,boolean bold){TextView v=new TextView(this);v.setText(res);v.setTextSize(size);v.setTextColor(ContextCompat.getColor(this,bold?R.color.cw_color_on_surface:R.color.cw_color_on_surface_variant));if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private MaterialButton button(int text,boolean primary){MaterialButton b=new MaterialButton(this);b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(56));if(!primary){b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,R.color.cw_color_surface)));b.setTextColor(ContextCompat.getColor(this,R.color.cw_color_primary));b.setStrokeColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,R.color.cw_color_outline_variant)));b.setStrokeWidth(dp(1));}return b;}
    private void add(LinearLayout body,android.view.View view,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);body.addView(view,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
