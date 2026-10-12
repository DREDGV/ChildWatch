package ru.example.childwatch.designsystem;

import org.junit.Test;
import static org.junit.Assert.*;

public class SnakeSwipeTest {
    @Test public void cornersDoNotRequireLiftingAndStraightDragIsNotRepeated() {
        SnakeSwipe swipe = new SnakeSwipe(16); swipe.begin(100, 100);
        assertEquals(SnakeEngine.Direction.RIGHT, swipe.move(130, 100));
        assertNull(swipe.move(160, 100));
        assertEquals(SnakeEngine.Direction.DOWN, swipe.move(160, 135));
        assertEquals(SnakeEngine.Direction.LEFT, swipe.move(120, 135));
        assertEquals(SnakeEngine.Direction.UP, swipe.move(120, 100));
    }
    @Test public void JitterDiagonalAndCancelledStrokeDoNotTurn() {
        SnakeSwipe swipe = new SnakeSwipe(16); swipe.begin(100, 100);
        assertNull(swipe.move(108, 103)); assertNull(swipe.move(121, 120));
        assertEquals(SnakeEngine.Direction.DOWN, swipe.move(120, 145));
        swipe.cancel(); assertNull(swipe.move(200, 145));
        swipe.begin(200, 145); assertEquals(SnakeEngine.Direction.DOWN, swipe.move(200, 175));
    }
}
