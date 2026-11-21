package ru.example.childwatch.designsystem;

/** Only the player's platform is predicted; never ball, score or collisions. */
final class LocalPaddleMotion {
    private double position=Double.NaN;private Double desired;
    void reset(){position=Double.NaN;desired=null;}
    void request(double normalized,double confirmed){
        if(!Double.isFinite(normalized))return;
        if(!Double.isFinite(position))position=confirmed;
        desired=Math.max(95,Math.min(905,normalized*1000));
    }
    double step(double confirmed,double seconds){
        if(!Double.isFinite(position))position=confirmed;
        if(desired!=null&&Math.abs(confirmed-desired)<3)desired=null;
        double target=desired==null?confirmed:desired,speed=desired==null?1100:2200;
        double limit=speed*Math.max(0,Math.min(.05,seconds));
        position+=Math.max(-limit,Math.min(limit,target-position));return position;
    }
}
