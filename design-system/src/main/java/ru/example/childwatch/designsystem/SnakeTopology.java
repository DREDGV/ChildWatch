package ru.example.childwatch.designsystem;

/** Geometry of the joined field edges, shared by solo and confirmed online rendering. */
final class SnakeTopology {
    static float wrap(float value,int size){return value-(float)Math.floor(value/size)*size;}
    static float delta(float from,float to,int size){float d=to-from;return d>size/2f?d-size:d<-size/2f?d+size:d;}
    static float between(float from,float to,float amount,int size){return wrap(from+delta(from,to,size)*amount,size);}
    static boolean separated(float x,float y,float oldX,float oldY){return Math.abs(x-oldX)>SnakeEngine.COLUMNS/2f||Math.abs(y-oldY)>SnakeEngine.ROWS/2f;}
    static void segment(SnakeScene scene,android.graphics.Canvas canvas,float x,float y,boolean mine,boolean head,float dx,float dy){
        scene.segment(canvas,x,y,mine,head,dx,dy);
        if(x<1)scene.segment(canvas,x+SnakeEngine.COLUMNS,y,mine,head,dx,dy);
        if(x>SnakeEngine.COLUMNS-1)scene.segment(canvas,x-SnakeEngine.COLUMNS,y,mine,head,dx,dy);
        if(y<1)scene.segment(canvas,x,y+SnakeEngine.ROWS,mine,head,dx,dy);
        if(y>SnakeEngine.ROWS-1)scene.segment(canvas,x,y-SnakeEngine.ROWS,mine,head,dx,dy);
    }
}
