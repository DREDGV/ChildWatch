package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;

/** Resolution-independent, quiet illustrations of the actual three games. */
public final class GameArtworkView extends View {
    public static final String ARKANOID="arkanoid", BATTLESHIP="battleship", SNAKE="snake";
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String game=ARKANOID;
    public GameArtworkView(Context context){super(context);setup();}
    public GameArtworkView(Context context, AttributeSet attrs){super(context,attrs);setup();}
    private void setup(){setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setGame(String value){game=value;invalidate();}
    private int color(int id){return ContextCompat.getColor(getContext(),id);}
    private void ink(int id){paint.setColor(color(id));paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);}
    private void round(Canvas c,float x,float y,float w,float h,int id,float r){ink(id);c.drawRoundRect(x,y,x+w,y+h,r,r,paint);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(getWidth()==0||getHeight()==0)return;
        ink(R.color.cw_game_ocean);c.drawRect(0,0,getWidth(),getHeight(),paint);
        float scale=Math.min(getWidth()/360f,getHeight()/160f);
        c.save();c.translate((getWidth()-360*scale)/2,(getHeight()-160*scale)/2);c.scale(scale,scale);
        round(c,0,0,360,160,R.color.cw_game_ocean,0);
        if(BATTLESHIP.equals(game))fleet(c);else if(SNAKE.equals(game))snake(c);else bricks(c);
        c.restore();
    }
    private void grid(Canvas c){
        ink(R.color.cw_game_grid);paint.setStrokeWidth(.65f);
        for(int x=20;x<360;x+=20)c.drawLine(x,0,x,160,paint);
        for(int y=20;y<160;y+=20)c.drawLine(0,y,360,y,paint);
    }
    private void bricks(Canvas c){
        for(int row=0;row<3;row++)for(int col=0;col<7;col++){
            if((row==2&&col==3)||(row==1&&col==4))continue;
            int tint=row==0?R.color.cw_game_coral:row==1?R.color.cw_game_gold:R.color.cw_game_mint;
            round(c,35+col*42,23+row*21,36,14,tint,4);
        }
        ink(R.color.cw_game_grid);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
        Path trail=new Path();trail.moveTo(178,132);trail.lineTo(222,99);trail.lineTo(201,80);c.drawPath(trail,paint);
        round(c,145,136,74,9,R.color.cw_game_mint,5);
        ink(R.color.cw_game_white);c.drawCircle(201,80,6,paint);
    }
    private void fleet(Canvas c){
        grid(c);
        round(c,68,52,120,17,R.color.cw_game_mint,6);
        round(c,107,76,65,9,R.color.cw_game_ship_detail,3);
        round(c,238,32,17,75,R.color.cw_game_mint,6);
        round(c,56,112,56,17,R.color.cw_game_mint,6);
        ink(R.color.cw_game_white);for(int[] p:new int[][]{{210,120},{290,70},{140,25},{310,130}})c.drawCircle(p[0],p[1],3,paint);
        ink(R.color.cw_game_coral);paint.setStrokeWidth(4);paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(241,69,252,80,paint);c.drawLine(252,69,241,80,paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }
    private void snake(Canvas c){
        grid(c);ink(R.color.cw_game_mint);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(17);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);
        Path tail=new Path();tail.moveTo(71,120);tail.lineTo(110,120);tail.lineTo(110,80);tail.lineTo(190,80);tail.lineTo(190,40);tail.lineTo(248,40);c.drawPath(tail,paint);
        paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);
        round(c,239,30,25,21,R.color.cw_game_mint,9);
        ink(R.color.cw_game_ocean);c.drawCircle(256,35,2.4f,paint);c.drawCircle(256,46,2.4f,paint);
        ink(R.color.cw_game_coral);c.drawCircle(290,110,10,paint);
        round(c,289,95,3,7,R.color.cw_game_gold,1);
    }
}
