package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

/** Shared solo/online artwork. Scene effects never modify gameplay. */
final class ArcanoidScene {
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int ocean,grid,mint,gold,white;
    private final int[] colors;
    ArcanoidScene(Context context){
        ocean=context.getColor(R.color.cw_game_ocean);grid=context.getColor(R.color.cw_game_grid);
        mint=context.getColor(R.color.cw_game_mint);gold=context.getColor(R.color.cw_game_gold);white=context.getColor(R.color.cw_game_white);
        colors=new int[]{mint,context.getColor(R.color.cw_game_brick_blue),context.getColor(R.color.cw_game_brick_lilac),gold,context.getColor(R.color.cw_game_coral)};
    }
    void board(Canvas c,float height){
        ink.setStyle(Paint.Style.FILL);ink.setColor(ocean);c.drawRoundRect(0,0,1000,height,28,28,ink);
        ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(3);ink.setColor(grid);c.drawRoundRect(3,3,997,height-3,28,28,ink);ink.setStyle(Paint.Style.FILL);
    }
    void brick(Canvas c,float x,float y,float height,int row){
        ink.setColor(colors[row%colors.length]);c.drawRoundRect(x,y,x+136,y+height,12,12,ink);
        ink.setColor(0x30ffffff);c.drawRoundRect(x+6,y+5,x+130,y+12,3,3,ink);
    }
    void paddle(Canvas c,float x,float y,boolean mine){
        ink.setColor(mine?mint:gold);c.drawRoundRect(x-95,y,x+95,y+22,11,11,ink);
        ink.setColor(0x70ffffff);c.drawRoundRect(x-76,y+5,x+76,y+9,2,2,ink);
    }
    void ball(Canvas c,float x,float y,float pulse){
        if(pulse>0){ink.setColor((Math.round(80*pulse)<<24)|(mint&0xffffff));c.drawCircle(x,y,20+(1-pulse)*32,ink);}
        ink.setColor(white);c.drawCircle(x,y,20,ink);ink.setColor(ocean);ink.setAlpha(45);c.drawCircle(x+4,y+5,8,ink);ink.setAlpha(255);
    }
}
