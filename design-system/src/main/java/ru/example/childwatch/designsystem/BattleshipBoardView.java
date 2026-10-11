package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.Arrays;
import java.util.List;

/** Selection is separate from firing: small cells never cause accidental shots. */
public final class BattleshipBoardView extends View {
    public interface OnCellSelected { void select(int cell); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BattleshipEngine.Cell[] cells = new BattleshipEngine.Cell[100];
    private int selected = -1;
    private OnCellSelected listener;
    private final ExploreByTouchHelper accessibility;
    public BattleshipBoardView(Context context) {
        super(context); Arrays.fill(cells, BattleshipEngine.Cell.UNKNOWN);
        setFocusable(true);
        accessibility = new ExploreByTouchHelper(this) {
            @Override protected int getVirtualViewAt(float x,float y) { int i=index(x,y); return i<0?INVALID_ID:i; }
            @Override protected void getVisibleVirtualViews(List<Integer> ids) { for(int i=0;i<100;i++) ids.add(i); }
            @Override protected void onPopulateNodeForVirtualView(int id,AccessibilityNodeInfoCompat node) {
                node.setText(coordinate(id)+", "+description(cells[id]));
                node.setBoundsInParent(new Rect(Math.round(step()*(id%10+1)), Math.round(step()*(id/10+1)),
                    Math.round(step()*(id%10+2)),Math.round(step()*(id/10+2))));
                node.setSelected(id==selected); node.setClickable(true);
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            }
            @Override protected boolean onPerformActionForVirtualView(int id,int action,Bundle args) {
                if(action!=AccessibilityNodeInfoCompat.ACTION_CLICK) return false;
                choose(id); return true;
            }
        };
        ViewCompat.setAccessibilityDelegate(this,accessibility);
    }
    public static String coordinate(int cell) { return "АБВГДЕЖЗИК".charAt(cell%10)+String.valueOf(cell/10+1); }
    private static String description(BattleshipEngine.Cell c) {
        switch(c) { case SHIP:return "корабль";case MISS:return "промах";case HIT:return "попадание";case SUNK:return "потоплен";default:return "не проверено"; }
    }
    public void setBoard(BattleshipEngine.Cell[] values,int selection) {
        if(values.length!=100) throw new IllegalArgumentException("Board must contain 100 cells");
        cells=values.clone(); selected=selection; invalidate(); accessibility.invalidateRoot();
    }
    public void setOnCellSelected(OnCellSelected value) { listener=value; }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int size=MeasureSpec.getSize(widthSpec); setMeasuredDimension(size,size);
    }
    private float step() { return getWidth()/11f; }
    private int index(float x,float y) { int c=(int)(x/step())-1,r=(int)(y/step())-1;
        return c<0||r<0||c>9||r>9?-1:r*10+c; }
    private void choose(int i) { if(i>=0&&i<100&&listener!=null) listener.select(i); }
    @Override public boolean dispatchHoverEvent(MotionEvent e) { return accessibility.dispatchHoverEvent(e)||super.dispatchHoverEvent(e); }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(e.getAction()==MotionEvent.ACTION_UP) { choose(index(e.getX(),e.getY())); performClick(); }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private int color(int res) { return getContext().getColor(res); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float s=step();
        canvas.drawColor(color(R.color.cw_sea_surface));
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(s*.36f);
        paint.setColor(color(R.color.cw_sea_ink)); paint.setStyle(Paint.Style.FILL);
        for(int n=0;n<10;n++) { canvas.drawText(String.valueOf("АБВГДЕЖЗИК".charAt(n)),s*(n+1.5f),s*.66f,paint);
            canvas.drawText(String.valueOf(n+1),s*.5f,s*(n+1.65f),paint); }
        for(int i=0;i<100;i++) {
            float x=s*(i%10+1),y=s*(i/10+1); BattleshipEngine.Cell c=cells[i];
            paint.setStyle(Paint.Style.FILL);
            if(c==BattleshipEngine.Cell.SHIP||c==BattleshipEngine.Cell.SUNK) {
                paint.setColor(color(c==BattleshipEngine.Cell.SHIP?R.color.cw_sea_ship:R.color.cw_sea_hit));
                canvas.drawRoundRect(x+3,y+3,x+s-3,y+s-3,s*.12f,s*.12f,paint);
            }
            paint.setColor(color(c==BattleshipEngine.Cell.HIT?R.color.cw_sea_hit:R.color.cw_sea_ink));
            paint.setStrokeWidth(Math.max(2,s*.07f));
            if(c==BattleshipEngine.Cell.HIT||c==BattleshipEngine.Cell.SUNK) {
                if(c==BattleshipEngine.Cell.SUNK) paint.setColor(color(R.color.cw_sea_surface));
                canvas.drawLine(x+s*.28f,y+s*.28f,x+s*.72f,y+s*.72f,paint);
                canvas.drawLine(x+s*.72f,y+s*.28f,x+s*.28f,y+s*.72f,paint);
            } else if(c==BattleshipEngine.Cell.MISS) canvas.drawCircle(x+s*.5f,y+s*.5f,s*.09f,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(color(R.color.cw_sea_grid));
            canvas.drawRect(x,y,x+s,y+s,paint);
            if(i==selected) { paint.setStrokeWidth(3); paint.setColor(color(R.color.cw_sea_ink));canvas.drawRect(x+2,y+2,x+s-2,y+s-2,paint); }
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
