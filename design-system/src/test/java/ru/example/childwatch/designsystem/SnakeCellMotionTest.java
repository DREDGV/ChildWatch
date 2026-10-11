package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;

public class SnakeCellMotionTest {
    @Test public void rapidTurnVisitsCornerBeforeChangingAxis(){
        SnakeCellMotion motion=new SnakeCellMotion(105);
        motion.append(106,0,180);motion.append(126,90,180);
        assertEquals(5.5f,motion.x(),.0001f);assertEquals(5f,motion.y(),.0001f);
        motion.advance(135);assertEquals(5.75f,motion.x(),.0001f);assertEquals(5f,motion.y(),.0001f);
        motion.advance(180);assertEquals(6f,motion.x(),.0001f);assertEquals(5f,motion.y(),.0001f);
        motion.advance(270);assertEquals(6f,motion.x(),.0001f);assertEquals(5.5f,motion.y(),.0001f);
        motion.advance(360);assertEquals(6f,motion.y(),.0001f);assertFalse(motion.moving());
    }
    @Test public void fourthBufferedCellSnapsInsteadOfGrowingLatency(){
        SnakeCellMotion motion=new SnakeCellMotion(105);
        for(int cell=106;cell<=109;cell++)motion.append(cell,0,180);
        assertEquals(9f,motion.x(),0);assertFalse(motion.moving());
    }
    @Test public void gapAndFreezeDiscardAllPendingSegments(){
        SnakeCellMotion motion=new SnakeCellMotion(105);motion.append(106,0,180);
        motion.append(148,30,180);assertEquals(8f,motion.x(),0);assertEquals(7f,motion.y(),0);
        motion.append(149,40,180);motion.snap(149);motion.advance(300);
        assertEquals(9f,motion.x(),0);assertFalse(motion.moving());
    }
    @Test public void finishedSegmentStaysAtConfirmedCellUntilNextMeasurement(){
        SnakeCellMotion motion=new SnakeCellMotion(105);motion.append(106,0,90);motion.advance(10000);
        assertEquals(6f,motion.x(),0);assertEquals(5f,motion.y(),0);assertFalse(motion.moving());
    }
    @Test public void growingTailCopiesPendingRouteBeforePredecessorMovesOn(){
        SnakeCellMotion tail=new SnakeCellMotion(105);tail.append(106,0,180);tail.append(126,90,180);
        SnakeCellMotion grown=tail.copy();tail.append(127,100,180);grown.append(126,100,180);
        tail.advance(135);grown.advance(135);assertEquals(tail.x(),grown.x(),0);assertEquals(tail.y(),grown.y(),0);
        tail.advance(450);grown.advance(450);
        assertEquals(6.5f,tail.x(),.0001f);assertEquals(6f,grown.x(),0);assertEquals(6f,grown.y(),0);
        assertFalse(grown.moving());assertTrue(tail.moving());
    }
    @Test public void duplicateTargetDoesNotFlushThreeBufferedLegs(){
        SnakeCellMotion motion=new SnakeCellMotion(105);
        motion.append(106,0,180);motion.append(126,0,180);motion.append(127,0,180);motion.append(127,0,180);
        assertTrue(motion.moving());motion.advance(270);
        assertEquals(6f,motion.x(),0);assertEquals(5.5f,motion.y(),.0001f);
    }
    @Test public void bodyJoinsAtAcceptedCornerAndEyesFollowCurrentLeg(){
        SnakeCellMotion head=new SnakeCellMotion(106),neck=new SnakeCellMotion(105);
        head.append(126,0,180);neck.append(106,0,180);head.advance(90);neck.advance(90);
        assertTrue(head.joinsViaBend(neck.x(),neck.y()));
        assertEquals(6f,head.bendX(),0);assertEquals(5f,head.bendY(),0);
        assertEquals(0f,head.axisX(),0);assertEquals(1f,head.axisY(),0);
        head.append(127,90,180);assertEquals(0f,head.axisX(),0);
        head.advance(180);assertEquals(1f,head.axisX(),0);assertEquals(0f,head.axisY(),0);
    }
}
