package ru.example.childwatch.designsystem;
import org.junit.Test;
import static org.junit.Assert.*;
public class LocalPaddleMotionTest {
    @Test public void inputRespondsBeforeNetworkAckAtAllThreeRtts(){
        for(int rtt:new int[]{50,150,300}){
            LocalPaddleMotion motion=new LocalPaddleMotion();motion.request(.9,500);
            assertEquals(500+2200*.016,motion.step(500,.016),.00001);
            for(int elapsed=16;elapsed<rtt;elapsed+=16)motion.step(500,.016);
            assertTrue(motion.step(500,.016)>500);
        }
    }
    @Test public void clampPauseAndCorrectionPreserveSpeedLimit(){
        LocalPaddleMotion motion=new LocalPaddleMotion();motion.request(2,500);
        double prior=500;for(int i=0;i<20;i++){double now=motion.step(500,.016);assertTrue(now-prior<=35.201);prior=now;}
        assertEquals(905,prior,0);motion.reset();assertEquals(300,motion.step(300,0),0);
        motion.request(.4,300);for(int i=0;i<10;i++)motion.step(300,.016);
        assertEquals(400,motion.step(400,.016),0);assertEquals(382.4,motion.step(300,.016),.0001);
    }
}
