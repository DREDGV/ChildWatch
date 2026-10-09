package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class MapSpeedEstimatorTest {
    private static final long WALL = 1_800_000_000_000L;
    private static MapSpeedEstimator.Sample sample(double x, double y, int seconds, double accuracy,
            Float speed, Float speedAccuracy) {
        return new MapSpeedEstimator.Sample("child", y / 111194.9266, x / 111194.9266,
                WALL + seconds * 1000L, accuracy, speed, speedAccuracy,
                seconds * 1_000_000_000L, "boot-a");
    }
    @Test public void measuredSpeedDoesNotDependOnHorizontalAccuracy() {
        MapSpeedEstimator.Result r = MapSpeedEstimator.measured(sample(0,0,1,150,3f,.2f), WALL+1000);
        assertEquals(3, r.valueMps, 0); assertEquals(MapSpeedEstimator.Source.MEASURED, r.source);
        assertEquals(MapSpeedEstimator.Quality.HIGH, r.quality);
    }
    @Test public void confidenceAndFreshnessAreExplicit() {
        assertEquals(MapSpeedEstimator.Quality.HIGH, MapSpeedEstimator.quality(5,.5));
        assertEquals(MapSpeedEstimator.Quality.ACCEPTABLE, MapSpeedEstimator.quality(5,1.2));
        assertEquals(MapSpeedEstimator.Quality.UNRELIABLE, MapSpeedEstimator.quality(5,1.3));
        MapSpeedEstimator.Sample s=sample(0,0,1,3,3f,.2f);
        assertEquals(MapSpeedEstimator.Freshness.AGING, MapSpeedEstimator.measured(s,WALL+17000).freshness);
        assertNull(MapSpeedEstimator.measured(s,WALL+47000).valueMps);
        assertNull(MapSpeedEstimator.measured(s,WALL-31000).valueMps);
    }
    @Test public void noLegacyClockFallbackAndNoIsolatedZero() {
        MapSpeedEstimator e=new MapSpeedEstimator();
        for(int i=0;i<20;i++) {
            MapSpeedEstimator.Sample s=new MapSpeedEstimator.Sample("child",0,i*.00001,WALL+i*1000,1,null,null,null,null);
            assertNull(e.accept(s,s.capturedAtMs).valueMps);
        }
        assertNull(MapSpeedEstimator.measured(sample(0,0,0,1,0f,.1f),WALL).valueMps);
    }
    @Test public void stopNeedsSeveralGoodMeasurementsAndTenSeconds() {
        MapSpeedEstimator e=new MapSpeedEstimator();
        for(int i=0;i<10;i++) assertNull(e.accept(sample(0,0,i,1,0f,.1f),WALL+i*1000).valueMps);
        assertEquals(0,e.accept(sample(0,0,10,1,0f,.1f),WALL+10000).valueMps,0);
        assertEquals(2,e.accept(sample(2,0,11,1,2f,.1f),WALL+11000).valueMps,0);
    }
    @Test public void straightWalkingHasNumericalAccuracyAndRequiresWarmup() {
        MapSpeedEstimator e=new MapSpeedEstimator(); double error=0; int count=0;
        for(int i=0;i<=60;i++) {
            MapSpeedEstimator.Result r=e.accept(sample(1.5*i,0,i,.1,null,null),WALL+i*1000);
            if(i<5) assertNull(r.valueMps);
            if(i>=5 && r.valueMps!=null) {error+=Math.abs(r.valueMps-1.5);count++;}
        }
        assertTrue("usable samples="+count,count>=50);
        System.out.println("walking mean absolute error m/s="+error/count+" accepted="+count);
        assertTrue(error/count<.05);
    }
    @Test public void noiseDoesNotBecomeStationaryTravel() {
        MapSpeedEstimator e=new MapSpeedEstimator(); Random noise=new Random(42); int positive=0;
        for(int i=0;i<120;i++) {
            MapSpeedEstimator.Result r=e.accept(sample(noise.nextGaussian()*3,noise.nextGaussian()*3,i,5,null,null),WALL+i*1000);
            if(r.valueMps!=null && r.valueMps>.5) positive++;
        }
        assertEquals(0,positive);
    }
    @Test public void jumpAndGapBootAndDeviceResetWarmup() {
        MapSpeedEstimator e=new MapSpeedEstimator();
        for(int i=0;i<10;i++) e.accept(sample(i*2,0,i,.1,null,null),WALL+i*1000);
        assertNull(e.accept(sample(1000,0,10,.1,null,null),WALL+10000).valueMps);
        assertNull(e.accept(sample(22,0,11,.1,null,null),WALL+11000).valueMps);
        assertNull(e.accept(sample(100,0,50,.1,null,null),WALL+50000).valueMps);
        MapSpeedEstimator.Sample boot=new MapSpeedEstimator.Sample("child",0,0,WALL+51000,.1,null,null,0L,"boot-b");
        assertNull(e.accept(boot,boot.capturedAtMs).valueMps);
        MapSpeedEstimator.Sample device=new MapSpeedEstimator.Sample("other",0,0,WALL+52000,.1,null,null,1_000_000_000L,"boot-b");
        assertNull(e.accept(device,device.capturedAtMs).valueMps);
    }
    @Test public void wallClockChangeDoesNotChangeMeasurementIntervals() {
        MapSpeedEstimator e=new MapSpeedEstimator(); MapSpeedEstimator.Result r=null;
        for(int i=0;i<20;i++) {
            MapSpeedEstimator.Sample base=sample(i*2,0,i,.1,null,null);
            long wall=base.capturedAtMs+(i>=10?3600000:0);
            MapSpeedEstimator.Sample changed=new MapSpeedEstimator.Sample("child",base.latitude,base.longitude,wall,.1,null,null,base.elapsedRealtimeNanos,"boot-a");
            r=e.accept(changed,wall);
        }
        assertEquals(2,r.valueMps,.05);
    }
    @Test public void turnDoesNotUseEndToEndDistance() {
        MapSpeedEstimator e=new MapSpeedEstimator(); double error=0; int count=0;
        for(int i=0;i<=40;i++) {
            double x=i<=20?i*2:40, y=i<=20?0:(i-20)*2;
            MapSpeedEstimator.Result r=e.accept(sample(x,y,i,.1,null,null),WALL+i*1000);
            if(i>=5 && r.valueMps!=null) {error+=Math.abs(r.valueMps-2);count++;}
        }
        assertTrue(count>=30);
        System.out.println("right angle mean absolute error m/s="+error/count+" endpoint estimate="+Math.hypot(40,40)/40);
        assertTrue(error/count<.12);
    }
    @Test public void accelerationBrakingAndSparseDataRemainConservative() {
        MapSpeedEstimator e=new MapSpeedEstimator(); double x=0; MapSpeedEstimator.Result r=null;
        for(int i=0;i<=30;i++) {
            double velocity=i<15?i*.3:(30-i)*.3;
            x+=velocity; r=e.accept(sample(x,0,i,.1,null,null),WALL+i*1000);
            if(i>=5 && r.valueMps!=null) assertEquals(velocity,r.valueMps,.6);
        }
        e=new MapSpeedEstimator();
        for(int i=0;i<=90;i+=15) {
            r=e.accept(sample(i*2,0,i,20,null,null),WALL+i*1000);
            assertNull(r.valueMps);
        }
    }
    @Test public void positionOutlierDoesNotInvalidateAccurateSensorSpeed() {
        MapSpeedEstimator e=new MapSpeedEstimator();
        for(int i=0;i<10;i++) e.accept(sample(i*2,0,i,.1,null,null),WALL+i*1000);
        MapSpeedEstimator.Result r=e.accept(sample(1000,0,10,.1,2f,.1f),WALL+10000);
        assertEquals(2,r.valueMps,0); assertEquals(MapSpeedEstimator.Source.MEASURED,r.source);
    }
    @Test public void measuredStopIgnoresHorizontalCoordinateQuality() {
        MapSpeedEstimator e=new MapSpeedEstimator(); MapSpeedEstimator.Result r=null;
        for(int i=0;i<=10;i++) r=e.accept(sample(i*100,0,i,500,0f,.1f),WALL+i*1000);
        assertEquals(0,r.valueMps,0); assertEquals(MapSpeedEstimator.Source.MEASURED,r.source);
    }
    @Test public void slowWalkingNeverBecomesConfirmedZero() {
        for(float velocity:new float[]{.1f,.2f,.5f}) {
            MapSpeedEstimator e=new MapSpeedEstimator(); MapSpeedEstimator.Result r=null;
            for(int i=0;i<=30;i++) r=e.accept(sample(i*velocity,0,i,150,velocity,.05f),WALL+i*1000);
            assertNotNull(r.valueMps); assertEquals(velocity,r.valueMps,0.000001);
        }
    }
    @Test public void uncertainNearZeroIsNotEvidenceOfStopping() {
        MapSpeedEstimator e=new MapSpeedEstimator();
        for(int i=0;i<=30;i++) assertNull(e.accept(sample(0,0,i,150,0f,.3f),WALL+i*1000).valueMps);
    }
    @Test public void realisticGpsWalkingReportsConservativeCoverage() {
        MapSpeedEstimator e=new MapSpeedEstimator(); Random noise=new Random(127); int known=0, unknown=0;
        for(int i=0;i<=120;i+=5) {
            MapSpeedEstimator.Result r=e.accept(sample(1.5*i+noise.nextGaussian()*3,
                    noise.nextGaussian()*3,i,5,null,null),WALL+i*1000);
            if(r.valueMps==null) unknown++;
            else { known++; assertTrue(r.uncertaintyMps<=Math.max(.7,r.valueMps*.25)); }
        }
        // Without Android velocity the agreed acceleration model cannot claim accurate walking
        // from sparse ordinary GPS fixes. Coverage, not just accepted-value error, is acceptance evidence.
        System.out.println("GPS-only walking acc5m interval5s shown="+known+" unknown="+unknown);
        assertEquals(0,known); assertEquals(25,unknown);
    }
    @Test public void realisticMeasuredWalkingRetainsCoverageDespiteWeakGps() {
        MapSpeedEstimator e=new MapSpeedEstimator(); Random noise=new Random(123); double error=0; int known=0;
        for(int i=0;i<=120;i+=5) {
            float measured=(float)(1.5+noise.nextGaussian()*.1);
            MapSpeedEstimator.Result r=e.accept(sample(i*1.5+noise.nextGaussian()*15,
                    noise.nextGaussian()*15,i,30,measured,.2f),WALL+i*1000);
            assertEquals(MapSpeedEstimator.Source.MEASURED,r.source); assertNotNull(r.valueMps);
            error+=Math.abs(r.valueMps-1.5); known++;
        }
        System.out.println("measured walking acc30m interval5s shown="+known+" unknown=0 MAE="+error/known);
        assertEquals(25,known); assertTrue(error/known<.15);
    }
    @Test public void repeatedFixDoesNotAccumulateStopEvidence() {
        MapSpeedEstimator e=new MapSpeedEstimator();
        for(int i=0;i<20;i++) assertNull(e.accept(sample(0,0,1,1,0f,.1f),WALL+20000).valueMps);
    }
}
