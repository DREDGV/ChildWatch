package ru.example.childwatch.designsystem;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;

/** Common full-page game chrome, with space reserved for system bars and scalable text. */
public abstract class GameScreen extends AppCompatActivity {
    protected LinearLayout content;
    protected TextView message;
    protected int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    protected void screen(String title) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.cw_color_background));setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left,b.top,b.right,b.bottom);return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);header.setPadding(dp(12),0,dp(12),0);root.addView(header);
        MaterialButton back=button("Назад",false,()->finish());header.addView(back);
        TextView heading=label(title,24);heading.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.setMargins(dp(12),0,0,0);header.addView(heading,hp);
        ViewCompat.setAccessibilityHeading(heading,true);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(8),dp(16),dp(24));scroll.addView(content);
        message=label("",16);message.setVisibility(View.GONE);content.addView(message);
    }
    protected TextView label(String text,int size) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(getColor(R.color.cw_color_on_surface));
        view.setPadding(0,dp(8),0,dp(8));return view;
    }
    protected MaterialButton button(String text,boolean primary,Runnable click) {
        MaterialButton b=new MaterialButton(this,null,primary?com.google.android.material.R.attr.materialButtonStyle:com.google.android.material.R.attr.borderlessButtonStyle);
        b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->click.run());return b;
    }
    protected MaterialButton addButton(String text,boolean primary,Runnable click) {
        MaterialButton b=button(text,primary,click);content.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;
    }
    protected void notice(String text) { message.setText(text);message.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE); }
}
