package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArcanoidEngineTest {
    @Test public void fixedStepsAreFrameRateIndependent() {
        ArcanoidEngine a = new ArcanoidEngine(), b = new ArcanoidEngine();
        a.launchOrResume(); b.launchOrResume();
        for (int i = 0; i < 120; i++) a.update(1.0 / 120);
        for (int i = 0; i < 30; i++) b.update(1.0 / 30);
        assertEquals(a.ballX(), b.ballX(), 3); assertEquals(a.ballY(), b.ballY(), 3);
        assertEquals(a.score(), b.score());
    }
    @Test public void wallAndPaddleReflectWithoutPassingThrough() {
        ArcanoidEngine g = playing(); g.x = 15; g.y = 900; g.vx = -500; g.vy = 0;
        g.update(.02); assertTrue(g.vx > 0); assertTrue(g.x >= ArcanoidEngine.RADIUS);
        g.x = g.paddle; g.y = 1225; g.vx = 0; g.vy = 700;
        g.update(.05); assertTrue(g.vy < 0); assertTrue(g.y < ArcanoidEngine.PADDLE_Y);
    }
    @Test public void centralBrickIsRemovedExactlyOnce() {
        ArcanoidEngine g = playing(); g.x = ArcanoidEngine.brickX(0) + 50;
        g.y = ArcanoidEngine.brickY(0) - 20; g.vx = 0; g.vy = 700;
        g.update(.03); assertFalse(g.hasBrick(0)); assertEquals(10, g.score()); assertTrue(g.vy < 0);
        g.update(.03); assertEquals(10, g.score());
    }
    @Test public void lastBrickWinsAndStopsSimulation() {
        ArcanoidEngine g = playing(); g.bricks = 1; g.score = 290;
        g.x = ArcanoidEngine.brickX(0) + 50; g.y = 400; g.vx = 0; g.vy = 700;
        g.update(.03); assertEquals(ArcanoidEngine.State.WON, g.state()); assertEquals(300, g.score());
        double y = g.y; g.update(.1); assertEquals(y, g.y, 0);
    }
    @Test public void missingBallCostsOneLifeThenRequiresExplicitLaunch() {
        ArcanoidEngine g = playing();
        for (int i = 2; i >= 0; i--) {
            g.x = 20; g.y = 1410; g.vx = 0; g.vy = 700; g.update(.03);
            assertEquals(i, g.lives());
            assertEquals(i == 0 ? ArcanoidEngine.State.GAME_OVER : ArcanoidEngine.State.READY, g.state());
            g.update(.1); assertEquals(i, g.lives()); g.launchOrResume();
        }
        g.restart(); assertEquals(3, g.lives()); assertEquals(0, g.score());
    }
    @Test public void pauseAndRestoreNeverAutoLaunch() {
        ArcanoidEngine g = playing(); g.update(.04); double[] saved = g.snapshot();
        ArcanoidEngine copy = new ArcanoidEngine(); assertTrue(copy.restore(saved));
        assertEquals(ArcanoidEngine.State.PAUSED, copy.state());
        copy.update(10); assertEquals(saved[1], copy.ballX(), 0);
        copy.launchOrResume(); copy.update(.05); assertNotEquals(saved[1], copy.ballX(), 0);
        copy.pause(); double x = copy.ballX(); copy.update(1); assertEquals(x, copy.ballX(), 0);
    }
    @Test public void invalidSnapshotsAndTouchAreIgnored() {
        ArcanoidEngine g = new ArcanoidEngine(); double[] saved = g.snapshot(); saved[8] = Double.NaN;
        assertFalse(g.restore(saved)); assertEquals(3, g.lives());
        saved = g.snapshot(); saved[7] = 100; assertFalse(g.restore(saved));
        g.movePaddle(-1000); assertEquals(95, g.paddleX(), 0);
        g.movePaddle(Double.NaN); assertEquals(95, g.paddleX(), 0);
        g.movePaddle(5000); assertEquals(905, g.paddleX(), 0);
    }
    @Test public void longForegroundGapIsCapped() {
        ArcanoidEngine g = playing(); double before = g.y; g.update(30);
        assertTrue(Math.abs(g.y - before) < 71); assertEquals(3, g.lives());
    }
    @Test public void resizingPreservesBricksAndPausedParty() {
        ArcanoidEngine g = playing(); g.update(.05); g.pause();
        double ratio = g.ballY() / g.height();
        g.resize(360); assertEquals(ratio, g.ballY() / g.height(), .0001);
        assertEquals(ArcanoidEngine.State.PAUSED, g.state()); assertEquals(3, g.lives());
        assertTrue(g.blockY(29) + g.blockHeight() < g.paddleY() - ArcanoidEngine.RADIUS);
        ArcanoidEngine copy = new ArcanoidEngine(); assertTrue(copy.restore(g.snapshot()));
        assertEquals(360, copy.height(), 0); assertEquals(g.ballY(), copy.ballY(), 0);
        g.resize(2200); assertEquals(ratio, g.ballY() / g.height(), .0001);
    }
    @Test public void shallowLandscapeSlowsBallAndSnapshotsRemainValid() {
        ArcanoidEngine g = new ArcanoidEngine(); g.resize(360); g.launchOrResume();
        assertEquals(-240, g.vy, 0);
        g.pause(); g.resize(3000); assertTrue(Math.hypot(g.vx, g.vy) <= 700);
        assertTrue(new ArcanoidEngine().restore(g.snapshot()));
    }
    @Test public void sixAuthoredLevelsKeepScoreLivesAndRequireExplicitNext() {
        ArcanoidEngine g = new ArcanoidEngine();
        int total = 0; java.util.Set<Long> masks = new java.util.HashSet<>();
        for (int level = 1; level <= g.levelCount(); level++) {
            assertEquals(level, g.level()); assertFalse(g.nextLevel());
            masks.add(g.bricks); total += Long.bitCount(g.bricks) * 10;
            g.launchOrResume();
            int last = Long.numberOfTrailingZeros(g.bricks);
            g.bricks = 1L << last; g.score = total - 10;
            g.x = ArcanoidEngine.brickX(last) + 50;
            g.y = g.blockY(last) - ArcanoidEngine.RADIUS; g.vx = 0; g.vy = 700;
            g.update(.02);
            assertEquals(ArcanoidEngine.State.WON, g.state()); assertEquals(total, g.score());
            assertEquals(3, g.lives());
            ArcanoidEngine copy = new ArcanoidEngine(); assertTrue(copy.restore(g.snapshot()));
            assertEquals(level, copy.level()); assertEquals(total, copy.score());
            if (level < g.levelCount()) {
                assertFalse(g.campaignComplete()); assertTrue(g.nextLevel());
                assertEquals(ArcanoidEngine.State.READY, g.state()); assertEquals(0, g.vy, 0);
            } else { assertTrue(g.campaignComplete()); assertFalse(g.nextLevel()); }
        }
        assertEquals(g.levelCount(), masks.size());
        g.restart(); assertEquals(1, g.level()); assertEquals(0, g.score());
    }
    @Test public void legacySnapshotRestoresAndBadLevelMasksDoNotMutateState() {
        ArcanoidEngine g = playing(); g.update(.04);
        double[] old = java.util.Arrays.copyOf(g.snapshot(), 11); old[0] = 2;
        ArcanoidEngine restored = new ArcanoidEngine(); assertTrue(restored.restore(old));
        assertEquals(1, restored.level()); assertEquals(ArcanoidEngine.Mode.SOLO, restored.mode());
        assertEquals(ArcanoidEngine.State.PAUSED, restored.state());
        double[] bad = restored.snapshot(); bad[11] = 1.5; assertFalse(restored.restore(bad));
        bad = restored.snapshot(); bad[11] = 1; assertFalse(restored.restore(bad));
        assertEquals(1, restored.level());
    }
    @Test public void secondPaddleReflectsAndTopMissUsesSharedLives() {
        ArcanoidEngine g = new ArcanoidEngine(); g.restart(ArcanoidEngine.Mode.LOCAL_COOP);
        g.moveSecondPaddle(400); g.launchOrResume();
        g.x = 400; g.y = g.secondPaddleY() + ArcanoidEngine.PADDLE_HEIGHT + 21;
        g.vx = 0; g.vy = -600; g.update(.02);
        assertTrue(g.vy > 0); assertEquals(3, g.lives());
        assertTrue(new ArcanoidEngine().restore(g.snapshot()));
        g.x = 20; g.y = -19; g.vx = 0; g.vy = -600; g.update(.02);
        assertEquals(2, g.lives()); assertEquals(ArcanoidEngine.State.READY, g.state());
        g.moveSecondPaddle(Double.POSITIVE_INFINITY); assertEquals(400, g.secondPaddleX(), 0);
    }
    @Test public void newLevelLaunchSpeedIsCappedAfterResize() {
        ArcanoidEngine g = new ArcanoidEngine();
        // Build a genuine completed campaign snapshot using explicit transitions.
        for (int i = 1; i < g.levelCount(); i++) { g.bricks = 0; g.state = ArcanoidEngine.State.WON; g.nextLevel(); }
        g.resize(3000); g.launchOrResume(); assertTrue(Math.hypot(g.vx, g.vy) <= 700);
        assertFalse(g.hasBrick(-1)); assertFalse(g.hasBrick(64));
    }
    private ArcanoidEngine playing() { ArcanoidEngine g = new ArcanoidEngine(); g.launchOrResume(); return g; }
}
