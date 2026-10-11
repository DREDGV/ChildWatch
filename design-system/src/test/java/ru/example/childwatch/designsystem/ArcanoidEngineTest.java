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
    private ArcanoidEngine playing() { ArcanoidEngine g = new ArcanoidEngine(); g.launchOrResume(); return g; }
}
