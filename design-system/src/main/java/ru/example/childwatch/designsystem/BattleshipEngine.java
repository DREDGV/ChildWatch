package ru.example.childwatch.designsystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Local rules shared by solo and pass-and-play. Network games must enforce rules on the server. */
public final class BattleshipEngine {
    public static final int SIZE = 10;
    private static final int[] LENGTHS = {4, 3, 3, 2, 2, 2, 1, 1, 1, 1};
    public enum Mode { SOLO, HOTSEAT }
    public enum State { PLAYING, FINISHED }
    public enum Cell { UNKNOWN, SHIP, MISS, HIT, SUNK }
    public enum Shot { MISS, HIT, SUNK, WIN, REPEATED, INVALID, NOT_YOUR_TURN, GAME_OVER }
    private final Mode mode;
    private final long seed;
    private final int[][] fleets;
    private final boolean[][] shots = new boolean[2][100];
    private final List<Integer> history = new ArrayList<>();
    private int current, winner = -1;

    public BattleshipEngine(long seed) { this(Mode.SOLO, seed); }
    public BattleshipEngine(Mode mode, long seed) {
        this(mode, seed, randomFleet(new Random(seed)), randomFleet(new Random(seed ^ 0x5DEECE66DL)));
    }
    public BattleshipEngine(Mode mode, long seed, int[] first, int[] second) {
        if (mode == null || !validateFleet(first) || !validateFleet(second))
            throw new IllegalArgumentException("Invalid fleet or mode");
        this.mode = mode; this.seed = seed;
        fleets = new int[][] {first.clone(), second.clone()};
    }
    public Mode mode() { return mode; }
    public State state() { return winner < 0 ? State.PLAYING : State.FINISHED; }
    public int currentPlayer() { return current; }
    public int winner() { return winner; }
    public int[] fleet(int owner) { requirePlayer(owner); return fleets[owner].clone(); }
    public Cell cellOnBoard(int owner, int row, int col, boolean revealShips) { return cell(owner, row, col, revealShips); }
    public int shotsFired(int player) { requirePlayer(player); int n = 0; for (boolean b : shots[player]) if (b) n++; return n; }
    public int remainingShips(int owner) {
        requirePlayer(owner); int n = 0;
        for (int id = 1; id <= 10; id++) if (!sunk(owner, id)) n++;
        return n;
    }
    /** Board owner, not attacker. Hidden boards never expose unhit ship cells. */
    public Cell cell(int owner, int row, int col, boolean revealShips) {
        requirePlayer(owner);
        if (!inBounds(row, col)) throw new IllegalArgumentException("Outside board");
        int index = row * SIZE + col, id = fleets[owner][index];
        if (!shots[1 - owner][index]) return revealShips && id != 0 ? Cell.SHIP : Cell.UNKNOWN;
        return id == 0 ? Cell.MISS : sunk(owner, id) ? Cell.SUNK : Cell.HIT;
    }
    public Shot shoot(int player, int row, int col) {
        if (player < 0 || player > 1 || !inBounds(row, col)) return Shot.INVALID;
        if (winner >= 0) return Shot.GAME_OVER;
        if (player != current) return Shot.NOT_YOUR_TURN;
        int index = row * SIZE + col;
        if (shots[player][index]) return Shot.REPEATED;
        shots[player][index] = true; history.add(player * 100 + index);
        int owner = 1 - player, id = fleets[owner][index];
        if (id == 0) { current = owner; return Shot.MISS; }
        if (remainingShips(owner) == 0) { winner = player; return Shot.WIN; }
        return sunk(owner, id) ? Shot.SUNK : Shot.HIT;
    }
    /** Chooses from observations only. Its parameter cannot contain hidden fleet identities. */
    public static int chooseAiTarget(Cell[] observations, Random random) {
        if (observations == null || observations.length != 100 || random == null)
            throw new IllegalArgumentException("Invalid observations");
        List<Integer> aligned = new ArrayList<>(), targets = new ArrayList<>(), available = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            if (observations[i] == null || observations[i] == Cell.SHIP)
                throw new IllegalArgumentException("Only public observations allowed");
            if (observations[i] != Cell.UNKNOWN) continue;
            boolean nearSunk = false, nearHit = false;
            for (int r = Math.max(0, i / 10 - 1); r <= Math.min(9, i / 10 + 1); r++)
                for (int c = Math.max(0, i % 10 - 1); c <= Math.min(9, i % 10 + 1); c++) {
                    if (observations[r * 10 + c] == Cell.SUNK) nearSunk = true;
                    if (Math.abs(r - i / 10) + Math.abs(c - i % 10) == 1 && observations[r * 10 + c] == Cell.HIT) nearHit = true;
                }
            if (!nearSunk) {
                available.add(i); if (nearHit) targets.add(i);
                int row = i / SIZE, col = i % SIZE;
                // Two contiguous hits reveal the ship axis; do not shoot perpendicular cells.
                if (hit(observations, row, col - 1) && hit(observations, row, col - 2)
                        || hit(observations, row, col + 1) && hit(observations, row, col + 2)
                        || hit(observations, row - 1, col) && hit(observations, row - 2, col)
                        || hit(observations, row + 1, col) && hit(observations, row + 2, col)) aligned.add(i);
            }
        }
        List<Integer> options = !aligned.isEmpty() ? aligned : targets.isEmpty() ? available : targets;
        return options.isEmpty() ? -1 : options.get(random.nextInt(options.size()));
    }
    private static boolean hit(Cell[] observations, int row, int col) {
        return inBounds(row, col) && observations[row * SIZE + col] == Cell.HIT;
    }
    public Shot aiMove() {
        if (mode != Mode.SOLO || current != 1) return Shot.NOT_YOUR_TURN;
        if (winner >= 0) return Shot.GAME_OVER;
        Cell[] observations = new Cell[100];
        for (int i = 0; i < 100; i++) observations[i] = cell(0, i / 10, i % 10, false);
        int target = chooseAiTarget(observations, new Random(seed ^ (history.size() * 0x9E3779B97F4A7C15L)));
        return target < 0 ? Shot.INVALID : shoot(1, target / 10, target % 10);
    }
    private boolean sunk(int owner, int id) {
        for (int i = 0; i < 100; i++) if (fleets[owner][i] == id && !shots[1 - owner][i]) return false;
        return true;
    }
    private static void requirePlayer(int player) { if (player < 0 || player > 1) throw new IllegalArgumentException("Invalid player"); }
    private static boolean inBounds(int row, int col) { return row >= 0 && row < 10 && col >= 0 && col < 10; }
    public static boolean validateFleet(int[] board) {
        if (board == null || board.length != 100) return false;
        for (int id : board) if (id < 0 || id > 10) return false;
        for (int id = 1; id <= 10; id++) {
            int count = 0, minR = 10, maxR = -1, minC = 10, maxC = -1;
            for (int i = 0; i < 100; i++) if (board[i] == id) {
                count++; minR = Math.min(minR, i / 10); maxR = Math.max(maxR, i / 10);
                minC = Math.min(minC, i % 10); maxC = Math.max(maxC, i % 10);
                for (int r = Math.max(0, i / 10 - 1); r <= Math.min(9, i / 10 + 1); r++)
                    for (int c = Math.max(0, i % 10 - 1); c <= Math.min(9, i % 10 + 1); c++)
                        if (board[r * 10 + c] != 0 && board[r * 10 + c] != id) return false;
            }
            if (count != LENGTHS[id - 1] || !(minR == maxR || minC == maxC)
                    || Math.max(maxR - minR, maxC - minC) + 1 != count) return false;
        }
        return true;
    }
    /** Repositions one complete ship; invalid placement leaves the supplied board untouched. */
    public static int[] moveShip(int[] fleet, int shipId, int startCell, boolean vertical) {
        if (!validateFleet(fleet) || shipId < 1 || shipId > 10 || startCell < 0 || startCell >= 100) return null;
        int[] candidate = fleet.clone();
        for (int i = 0; i < 100; i++) if (candidate[i] == shipId) candidate[i] = 0;
        for (int k = 0; k < LENGTHS[shipId - 1]; k++) {
            int row = startCell / 10 + (vertical ? k : 0), col = startCell % 10 + (vertical ? 0 : k);
            if (!inBounds(row, col) || candidate[row * 10 + col] != 0) return null;
            candidate[row * 10 + col] = shipId;
        }
        return validateFleet(candidate) ? candidate : null;
    }
    public static int[] randomFleet(Random random) {
        if (random == null) throw new IllegalArgumentException("Random required");
        int[] board = new int[100];
        if (!place(board, 1, random)) throw new IllegalStateException("Fleet cannot fit");
        return board;
    }
    private static boolean place(int[] board, int id, Random random) {
        if (id > 10) return true;
        int[] candidates = new int[200]; for (int i = 0; i < 200; i++) candidates[i] = i;
        for (int i = 199; i > 0; i--) { int j = random.nextInt(i + 1), t = candidates[i]; candidates[i] = candidates[j]; candidates[j] = t; }
        for (int candidate : candidates) {
            int row = candidate % 100 / 10, col = candidate % 10;
            int dr = candidate >= 100 ? 1 : 0, dc = 1 - dr, length = LENGTHS[id - 1];
            boolean fits = true;
            for (int k = 0; k < length && fits; k++) {
                int r = row + dr * k, c = col + dc * k;
                if (!inBounds(r, c)) { fits = false; break; }
                for (int rr = Math.max(0, r - 1); rr <= Math.min(9, r + 1); rr++)
                    for (int cc = Math.max(0, c - 1); cc <= Math.min(9, c + 1); cc++) if (board[rr * 10 + cc] != 0) fits = false;
            }
            if (!fits) continue;
            for (int k = 0; k < length; k++) board[(row + dr * k) * 10 + col + dc * k] = id;
            if (place(board, id + 1, random)) return true;
            for (int k = 0; k < length; k++) board[(row + dr * k) * 10 + col + dc * k] = 0;
        }
        return false;
    }
    /** Versioned, bounded local save. Replays every shot to reject impossible saved states. */
    public String snapshot() {
        StringBuilder out = new StringBuilder("1|").append(mode.name()).append('|').append(seed);
        for (int[] fleet : fleets) { out.append('|'); for (int id : fleet) out.append((char) ('A' + id)); }
        out.append('|'); for (int move : history) out.append(move).append(',');
        return out.toString();
    }
    public static BattleshipEngine restore(String snapshot) {
        if (snapshot == null || snapshot.length() > 1200) throw new IllegalArgumentException("Invalid save");
        String[] parts = snapshot.split("\\|", -1);
        if (parts.length != 6 || !parts[0].equals("1")) throw new IllegalArgumentException("Invalid save version");
        int[][] boards = new int[2][100];
        for (int owner = 0; owner < 2; owner++) {
            if (parts[3 + owner].length() != 100) throw new IllegalArgumentException("Invalid saved fleet");
            for (int i = 0; i < 100; i++) boards[owner][i] = parts[3 + owner].charAt(i) - 'A';
        }
        BattleshipEngine result = new BattleshipEngine(Mode.valueOf(parts[1]), Long.parseLong(parts[2]), boards[0], boards[1]);
        if (!parts[5].isEmpty()) {
            String[] moves = parts[5].split(",", -1);
            if (moves.length > 201 || !moves[moves.length - 1].isEmpty()) throw new IllegalArgumentException("Invalid saved moves");
            for (int i = 0; i < moves.length - 1; i++) {
                int move = Integer.parseInt(moves[i]);
                if (move < 0 || move > 199) throw new IllegalArgumentException("Invalid saved move");
                Shot shot = result.shoot(move / 100, move % 100 / 10, move % 10);
                if (shot != Shot.MISS && shot != Shot.HIT && shot != Shot.SUNK && shot != Shot.WIN)
                    throw new IllegalArgumentException("Impossible saved history");
            }
        }
        return result;
    }
}
