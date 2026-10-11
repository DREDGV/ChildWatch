package ru.example.childwatch.designsystem;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Executes the actual production Java engine, not a copied physics implementation. */
public final class ArcanoidGoldenRunner {
    static ArcanoidEngine engine;
    static double[] saved;
    private static void emit() {
        double[] snapshot=engine.snapshot();StringBuilder json=new StringBuilder("[");
        for(int i=0;i<snapshot.length;i++){if(i>0)json.append(',');json.append(Double.toString(snapshot[i]));}
        System.out.println(json.append(']').toString());
    }
    public static void main(String[] args) throws Exception {
        engine=new ArcanoidEngine();engine.restart(ArcanoidEngine.Mode.LOCAL_COOP);emit();
        BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String line;
        while((line=reader.readLine())!=null){
            String[] fields=line.split(" ");
            switch(fields[0]){
                case "launch":engine.launchOrResume();break;
                case "pause":engine.pause();break;
                case "update":engine.update(Double.parseDouble(fields[1]));break;
                case "move":if(fields[1].equals("0"))engine.movePaddle(Double.parseDouble(fields[2]));else engine.moveSecondPaddle(Double.parseDouble(fields[2]));break;
                case "force":engine.x=Double.parseDouble(fields[1]);engine.y=Double.parseDouble(fields[2]);engine.vx=Double.parseDouble(fields[3]);engine.vy=Double.parseDouble(fields[4]);engine.state=ArcanoidEngine.State.PLAYING;break;
                case "last":int last=Long.numberOfTrailingZeros(engine.bricks);engine.score+=(Long.bitCount(engine.bricks)-1)*10;engine.bricks=1L<<last;
                    engine.x=ArcanoidEngine.brickX(last)+50;engine.y=ArcanoidEngine.brickY(last)-20;engine.vx=0;engine.vy=700;engine.state=ArcanoidEngine.State.PLAYING;break;
                case "next":engine.nextLevel();break;
                case "save":saved=engine.snapshot();break;
                case "restore":if(!engine.restore(saved))throw new IllegalStateException("Invalid fixture restore");break;
                default:throw new IllegalArgumentException("Unknown command");
            }
            emit();
        }
    }
}
