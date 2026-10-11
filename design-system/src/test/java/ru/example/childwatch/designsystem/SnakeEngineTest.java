package ru.example.childwatch.designsystem;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SnakeEngineTest {
    @Test public void simulationDoesNotDependOnRenderFrameRate() {
        SnakeEngine a = new SnakeEngine(), b = new SnakeEngine();
        a.launchOrResume(); b.launchOrResume();
        for (int i = 0; i < 120; i++) a.update(1.0 / 120);
        for (int i = 0; i < 30; i++) b.update(1.0 / 30);
        assertArrayEquals(a.snapshot(), b.snapshot()); assertEquals(5, a.ticks());
    }
    @Test public void turnsAreBoundedAndAppliedOnePerMoveWithoutReversals() {
        SnakeEngine g = new SnakeEngine();
        assertFalse(g.turn(0, SnakeEngine.Direction.LEFT));
        assertTrue(g.turn(0, SnakeEngine.Direction.UP));
        assertFalse(g.turn(0, SnakeEngine.Direction.DOWN));
        assertTrue(g.turn(0, SnakeEngine.Direction.LEFT));
        assertFalse(g.turn(0, SnakeEngine.Direction.DOWN));
        g.launchOrResume(); g.tick(); assertEquals(SnakeEngine.Direction.UP, g.direction(0));
        g.tick(); assertEquals(SnakeEngine.Direction.LEFT, g.direction(0));
        assertEquals(4, g.segmentX(0, 0)); assertEquals(7, g.segmentY(0, 0));
    }
    @Test public void foodGrowsExactlyOnceAndDoesNotSpawnOnAnyBody() {
        SnakeEngine g = fixture(new int[]{165,164,163}, SnakeEngine.Direction.RIGHT, null, null, 166);
        g.launchOrResume(); g.tick(); assertEquals(10, g.score()); assertEquals(4, g.length(0));
        for (int i = 0; i < g.length(0); i++) {
            assertFalse(g.segmentX(0,i) == g.foodX() && g.segmentY(0,i) == g.foodY());
        }
        assertTrue(g.tickSeconds() < .18);
        SnakeEngine copy = new SnakeEngine(); assertTrue(copy.restore(g.snapshot()));
        copy.launchOrResume(); g.tick(); copy.tick(); assertArrayEquals(g.snapshot(), copy.snapshot());
    }
    @Test public void movingIntoVacatedTailIsLegal() {
        SnakeEngine g = fixture(new int[]{165,164,184,185}, SnakeEngine.Direction.RIGHT, null, null, 10);
        assertTrue(g.turn(0, SnakeEngine.Direction.DOWN)); g.launchOrResume(); g.tick();
        assertEquals(SnakeEngine.State.PLAYING, g.state()); assertEquals(185, g.segmentY(0,0)*20+g.segmentX(0,0));
    }
    @Test public void wallCollisionIsAtomicAndTerminalStateCanBeRestored() {
        SnakeEngine g = fixture(new int[]{19,18,17}, SnakeEngine.Direction.RIGHT, null, null, 100);
        long[] before = g.snapshot(); g.launchOrResume(); g.tick();
        assertEquals(SnakeEngine.State.GAME_OVER, g.state()); assertEquals(19, g.segmentX(0,0));
        assertEquals(0, g.score()); assertEquals(1, g.ticks());
        SnakeEngine copy = new SnakeEngine(); assertTrue(copy.restore(g.snapshot()));
        assertEquals(SnakeEngine.State.GAME_OVER, copy.state()); copy.launchOrResume(); copy.tick();
        assertEquals(1, copy.ticks()); assertNotEquals(before[3], copy.snapshot()[3]);
    }
    @Test public void cooperativeHeadOnAndHeadSwapHaveNoPlayerOrderWinner() {
        SnakeEngine same = fixture(new int[]{165,164,163}, SnakeEngine.Direction.RIGHT,
                new int[]{167,168,169}, SnakeEngine.Direction.LEFT, 300);
        same.launchOrResume(); same.tick(); assertEquals(SnakeEngine.State.GAME_OVER, same.state());
        assertEquals(5,same.segmentX(0,0)); assertEquals(7,same.segmentX(1,0));
        SnakeEngine swap = fixture(new int[]{165,164,163}, SnakeEngine.Direction.RIGHT,
                new int[]{166,167,168}, SnakeEngine.Direction.LEFT, 300);
        swap.launchOrResume(); swap.tick(); assertEquals(SnakeEngine.State.GAME_OVER, swap.state());
    }
    @Test public void cooperativeVacatedTailAllowedUnlessOtherSnakeGrows() {
        int[] one = {165,164,163}, two = {167,166,186,185};
        SnakeEngine free = fixture(one,SnakeEngine.Direction.RIGHT,two,SnakeEngine.Direction.RIGHT,300);
        assertTrue(free.turn(0,SnakeEngine.Direction.DOWN)); free.launchOrResume(); free.tick();
        assertEquals(SnakeEngine.State.PLAYING, free.state());
        SnakeEngine grows = fixture(one,SnakeEngine.Direction.RIGHT,two,SnakeEngine.Direction.RIGHT,168);
        assertTrue(grows.turn(0,SnakeEngine.Direction.DOWN)); grows.launchOrResume(); grows.tick();
        assertEquals(SnakeEngine.State.GAME_OVER, grows.state()); assertEquals(10,grows.score());
    }
    @Test public void pauseAndRestoreDiscardWallTimeButKeepPendingInput() {
        SnakeEngine g = new SnakeEngine(SnakeEngine.Mode.COOP,SnakeEngine.Difficulty.CALM,42);
        assertTrue(g.turn(1,SnakeEngine.Direction.UP)); g.launchOrResume(); g.update(.1);
        SnakeEngine restored = new SnakeEngine(); assertTrue(restored.restore(g.snapshot()));
        assertEquals(SnakeEngine.State.PAUSED,restored.state()); long[] saved = restored.snapshot();
        restored.update(300); assertArrayEquals(saved,restored.snapshot());
        restored.launchOrResume(); restored.tick(); assertEquals(SnakeEngine.Direction.UP,restored.direction(1));
        restored.pause(); saved=restored.snapshot(); restored.update(1); assertArrayEquals(saved,restored.snapshot());
    }
    @Test public void invalidSnapshotNeverPartiallyChangesLiveParty() {
        SnakeEngine g=new SnakeEngine(); long[] original=g.snapshot();
        long[][] invalid={null,new long[0],Arrays.copyOf(original,original.length-1),Arrays.copyOf(original,original.length+1)};
        for(long[] data:invalid){assertFalse(g.restore(data));assertArrayEquals(original,g.snapshot());}
        for(int index:new int[]{0,1,2,3,4,8,9,10,11,12}){
            long[] bad=original.clone();bad[index]=Long.MAX_VALUE;
            assertFalse("index "+index,g.restore(bad));assertArrayEquals(original,g.snapshot());
        }
        long[] negativeTime=original.clone(); negativeTime[5]=-1; assertFalse(g.restore(negativeTime));
        assertArrayEquals(original,g.snapshot());
        long[] overlap=original.clone();overlap[13]=overlap[12];assertFalse(g.restore(overlap));
    }
    @Test public void foodAndRestartAreSeededAndLongFrameIsBounded() {
        for(long seed=0;seed<100;seed++){
            SnakeEngine a=new SnakeEngine(SnakeEngine.Mode.COOP,SnakeEngine.Difficulty.NORMAL,seed);
            SnakeEngine b=new SnakeEngine(SnakeEngine.Mode.COOP,SnakeEngine.Difficulty.NORMAL,seed);
            assertArrayEquals(a.snapshot(),b.snapshot());a.launchOrResume();a.update(100);
            assertEquals(1,a.ticks());a.restart();assertArrayEquals(a.snapshot(),b.snapshot());
        }
    }
    private static SnakeEngine fixture(int[] one,SnakeEngine.Direction d1,int[] two,SnakeEngine.Direction d2,int food){
        boolean coop=two!=null; if(two==null)two=new int[0];
        long[] data=new long[15+one.length+two.length]; int i=0;
        data[i++]=1;data[i++]=coop?1:0;data[i++]=1;data[i++]=0;
        data[i++]=(one.length+two.length-(coop?6:3))*10;data[i++]=0;data[i++]=42;data[i++]=42;data[i++]=food;
        data[i++]=one.length;data[i++]=d1.ordinal();data[i++]=0;for(int n:one)data[i++]=n;
        data[i++]=two.length;data[i++]=d2==null?3:d2.ordinal();data[i++]=0;for(int n:two)data[i++]=n;
        SnakeEngine g=new SnakeEngine();assertTrue("valid fixture",g.restore(data));return g;
    }
}
