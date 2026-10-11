package ru.example.childwatch.designsystem;

import org.junit.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;
import static ru.example.childwatch.designsystem.BattleshipEngine.*;

public class BattleshipEngineTest {
    @Test public void generatedFleetsHaveClassicalShipsWithoutTouching() {
        for (int seed = 0; seed < 500; seed++) {
            int[] fleet = randomFleet(new Random(seed));
            assertTrue("seed " + seed, validateFleet(fleet));
            assertArrayEquals(fleet, randomFleet(new Random(seed)));
            assertEquals(20, Arrays.stream(fleet).filter(v -> v > 0).count());
        }
    }
    @Test public void malformedAndTouchingFleetsAreRejected() {
        int[] fleet = fixture(); assertTrue(validateFleet(fleet));
        assertFalse(validateFleet(null)); assertFalse(validateFleet(new int[99]));
        int[] diagonal = fleet.clone(); diagonal[66] = 0; diagonal[51] = 10;
        assertFalse(validateFleet(diagonal));
        int[] bent = fleet.clone(); bent[3] = 0; bent[11] = 1;
        assertFalse(validateFleet(bent));
        int[] missing = fleet.clone(); missing[0] = 0; assertFalse(validateFleet(missing));
    }
    @Test public void manualMoveIsAtomicAndCannotWrapOrOverlap() {
        int[] fleet = fixture(), original = fleet.clone();
        assertNull(moveShip(fleet, 1, 8, false));
        assertNull(moveShip(fleet, 1, 20, false));
        assertNull(moveShip(fleet, 1, 11, false));
        assertArrayEquals(original, fleet);
        int[] moved = moveShip(fleet, 1, 90, false);
        assertNotNull(moved); assertTrue(validateFleet(moved));
        assertEquals(0, moved[0]); assertEquals(1, moved[90]);
        assertArrayEquals(original, fleet);
    }
    @Test public void hiddenProjectionCannotLeakAndFleetCopiesAreDefensive() {
        int[] first = fixture(), second = fixture();
        BattleshipEngine game = new BattleshipEngine(Mode.HOTSEAT, 1, first, second);
        first[0] = 0; game.fleet(1)[0] = 0;
        for (int r = 0; r < 10; r++) for (int c = 0; c < 10; c++) assertEquals(Cell.UNKNOWN, game.cell(1, r, c, false));
        assertEquals(Cell.SHIP, game.cell(0, 0, 0, true));
        assertEquals(Cell.SHIP, game.cell(1, 0, 0, true));
    }
    @Test public void hitRetainsTurnMissPassesAndRepeatedShotsDoNotMutate() {
        BattleshipEngine game = game();
        assertEquals(Shot.HIT, game.shoot(0, 0, 0)); assertEquals(0, game.currentPlayer());
        String saved = game.snapshot(); assertEquals(Shot.REPEATED, game.shoot(0, 0, 0)); assertEquals(saved, game.snapshot());
        assertEquals(Shot.NOT_YOUR_TURN, game.shoot(1, 0, 0));
        assertEquals(Shot.INVALID, game.shoot(0, -1, 0));
        assertEquals(Shot.MISS, game.shoot(0, 9, 9)); assertEquals(1, game.currentPlayer());
        assertEquals(Cell.MISS, game.cell(1, 9, 9, false)); assertEquals(2, game.shotsFired(0));
    }
    @Test public void sunkMarksEntireShipAndFinalHitWins() {
        BattleshipEngine game = game();
        for (int c = 0; c < 3; c++) assertEquals(Shot.HIT, game.shoot(0, 0, c));
        assertEquals(Shot.SUNK, game.shoot(0, 0, 3)); assertEquals(9, game.remainingShips(1));
        for (int c = 0; c < 4; c++) assertEquals(Cell.SUNK, game.cell(1, 0, c, false));
        int[] fleet = fixture(); Shot last = null;
        for (int i = 4; i < 100; i++) if (fleet[i] > 0) last = game.shoot(0, i / 10, i % 10);
        assertEquals(Shot.WIN, last); assertEquals(0, game.winner()); assertEquals(State.FINISHED, game.state());
        assertEquals(0, game.remainingShips(1)); assertEquals(Shot.GAME_OVER, game.shoot(0, 9, 9));
        assertEquals(game.snapshot(), restore(game.snapshot()).snapshot());
    }
    @Test public void observationOnlyAiTargetsHitsAndAvoidsSunkNeighbourhood() {
        Cell[] view = new Cell[100]; Arrays.fill(view, Cell.UNKNOWN); view[44] = Cell.HIT;
        for (int seed = 0; seed < 100; seed++) assertTrue(Arrays.asList(34, 43, 45, 54).contains(chooseAiTarget(view, new Random(seed))));
        view[44] = Cell.SUNK;
        for (int seed = 0; seed < 100; seed++) {
            int target = chooseAiTarget(view, new Random(seed));
            assertTrue(Math.abs(target / 10 - 4) > 1 || Math.abs(target % 10 - 4) > 1);
        }
        view[0] = Cell.SHIP;
        try { chooseAiTarget(view, new Random(1)); fail("Hidden ship input must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void botContinuesProvenAxisAndHandlesBoardEdges() {
        Cell[] view = new Cell[100]; Arrays.fill(view, Cell.UNKNOWN); view[44] = view[45] = Cell.HIT;
        for (int seed = 0; seed < 100; seed++) assertTrue(Arrays.asList(43, 46).contains(chooseAiTarget(view, new Random(seed))));
        view[43] = Cell.MISS;
        for (int seed = 0; seed < 100; seed++) assertEquals(46, chooseAiTarget(view, new Random(seed)));
        Arrays.fill(view, Cell.UNKNOWN); view[9] = view[19] = Cell.HIT;
        for (int seed = 0; seed < 100; seed++) assertEquals(29, chooseAiTarget(view, new Random(seed)));
        view[39] = Cell.SUNK; // Excluded even if malformed observations suggest an extension.
        for (int seed = 0; seed < 100; seed++) assertNotEquals(29, chooseAiTarget(view, new Random(seed)));
        Arrays.fill(view, Cell.UNKNOWN); view[9] = view[10] = Cell.HIT;
        for (int seed = 0; seed < 100; seed++) assertTrue(Arrays.asList(8, 19, 0, 11, 20).contains(chooseAiTarget(view, new Random(seed))));
    }
    @Test public void overlappingOldFootprintIsAValidAtomicShipShift() {
        int[] original = fixture(), shifted = moveShip(original, 1, 1, false);
        assertNotNull(shifted); assertEquals(0, shifted[0]);
        for (int i = 1; i <= 4; i++) assertEquals(1, shifted[i]);
        assertTrue(validateFleet(shifted)); assertEquals(1, original[0]);
    }
    @Test public void restoredSoloGameMakesIdenticalAiMove() {
        BattleshipEngine original = new BattleshipEngine(Mode.SOLO, 17, fixture(), fixture());
        assertEquals(Shot.MISS, original.shoot(0, 9, 9));
        BattleshipEngine restored = restore(original.snapshot());
        assertEquals(original.aiMove(), restored.aiMove()); assertEquals(original.snapshot(), restored.snapshot());
    }
    @Test public void snapshotReplayRejectsWrongTurnDuplicateAndAfterFinish() {
        String saved = game().snapshot();
        reject(saved + "100,"); reject(saved + "0,0,"); reject(saved + "-1,"); reject(saved + "999,");
        reject(saved.replace("1|HOTSEAT", "2|HOTSEAT")); reject(saved.substring(0, 20));
        BattleshipEngine finished = game(); int[] fleet = fixture();
        for (int i = 0; i < 100; i++) if (fleet[i] > 0) finished.shoot(0, i / 10, i % 10);
        reject(finished.snapshot() + "99,");
    }
    @Test public void completeObservationDrivenGamesTerminateWithoutRepeats() {
        for (int seed = 0; seed < 20; seed++) {
            BattleshipEngine game = new BattleshipEngine(Mode.HOTSEAT, seed);
            int turns = 0;
            while (game.state() == State.PLAYING && turns++ < 201) {
                int player = game.currentPlayer(); Cell[] observations = new Cell[100];
                for (int i = 0; i < 100; i++) observations[i] = game.cell(1 - player, i / 10, i % 10, false);
                int target = chooseAiTarget(observations, new Random(seed * 1000L + turns));
                assertTrue(target >= 0); int before = game.shotsFired(player);
                game.shoot(player, target / 10, target % 10); assertEquals(before + 1, game.shotsFired(player));
            }
            assertEquals(State.FINISHED, game.state()); assertTrue(turns <= 200);
            assertEquals(game.snapshot(), restore(game.snapshot()).snapshot());
        }
    }
    private static BattleshipEngine game() { return new BattleshipEngine(Mode.HOTSEAT, 42, fixture(), fixture()); }
    private static void reject(String saved) { try { restore(saved); fail("Invalid save accepted"); } catch (IllegalArgumentException expected) { } }
    private static int[] fixture() {
        int[] board = new int[100]; int[][] cells = {{0,1,2,3},{20,21,22},{25,26,27},{40,41},{44,45},{48,49},{60},{62},{64},{66}};
        for (int id = 0; id < cells.length; id++) for (int cell : cells[id]) board[cell] = id + 1;
        return board;
    }
}
