package ru.example.childwatch.designsystem;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Grid rules shared by solo and cooperative play; rendering never changes simulation time. */
public final class SnakeEngine {
    public static final int COLUMNS = 20, ROWS = 36;
    private static final int CELLS = COLUMNS * ROWS, VERSION = 1;
    public enum Mode { SOLO, COOP }
    public enum Difficulty { CALM, NORMAL, FAST }
    public enum State { READY, PLAYING, PAUSED, WON, GAME_OVER }
    public enum Direction {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);
        public final int dx, dy;
        Direction(int dx, int dy) { this.dx = dx; this.dy = dy; }
        boolean opposite(Direction other) { return dx == -other.dx && dy == -other.dy; }
    }
    private Mode mode;
    private Difficulty difficulty;
    private State state;
    private final int[][] bodies = new int[2][CELLS];
    private final int[] lengths = new int[2];
    private final Direction[] directions = new Direction[2];
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Direction>[] turns = new ArrayDeque[]{new ArrayDeque<>(), new ArrayDeque<>()};
    private int food, score;
    private long ticks, random, initialSeed;
    private double accumulator;

    public SnakeEngine() { this(Mode.SOLO, Difficulty.NORMAL, 1); }
    public SnakeEngine(Mode mode, Difficulty difficulty, long seed) {
        if (mode == null || difficulty == null) throw new IllegalArgumentException("mode/difficulty");
        this.mode = mode; this.difficulty = difficulty; initialSeed = seed; restart();
    }
    public void restart() {
        random = initialSeed; ticks = 0; score = 0; accumulator = 0; state = State.READY;
        lengths[0] = 3; lengths[1] = mode == Mode.COOP ? 3 : 0;
        directions[0] = Direction.RIGHT; directions[1] = Direction.LEFT;
        for (int p = 0; p < 2; p++) {
            turns[p].clear(); Arrays.fill(bodies[p], 0);
            for (int i = 0; i < lengths[p]; i++) bodies[p][i] = p == 0 ? cell(5 - i, 8) : cell(14 + i, 17);
        }
        spawnFood();
    }
    public Mode mode() { return mode; }
    public Difficulty difficulty() { return difficulty; }
    public State state() { return state; }
    public int players() { return mode == Mode.COOP ? 2 : 1; }
    public int score() { return score; }
    public long ticks() { return ticks; }
    public int length(int player) { checkPlayer(player); return lengths[player]; }
    public int segmentX(int player, int index) { checkSegment(player, index); return bodies[player][index] % COLUMNS; }
    public int segmentY(int player, int index) { checkSegment(player, index); return bodies[player][index] / COLUMNS; }
    public Direction direction(int player) { checkPlayer(player); return directions[player]; }
    public int foodX() { return food < 0 ? -1 : food % COLUMNS; }
    public int foodY() { return food < 0 ? -1 : food / COLUMNS; }
    public double tickSeconds() {
        double start = difficulty == Difficulty.CALM ? .24 : difficulty == Difficulty.NORMAL ? .18 : .13;
        return Math.max(.075, start - Math.min(score / 10, 40) * .003);
    }
    public double interpolation() { return Math.min(1, accumulator / tickSeconds()); }
    public void launchOrResume() { if (state == State.READY || state == State.PAUSED) state = State.PLAYING; }
    public void pause() { if (state == State.PLAYING) state = State.PAUSED; accumulator = 0; }
    /** Two turns can buffer a quick corner, but never a direct reversal or unbounded input. */
    public boolean turn(int player, Direction next) {
        if (player < 0 || player >= players() || next == null || state == State.GAME_OVER || state == State.WON) return false;
        Direction last = turns[player].isEmpty() ? directions[player] : turns[player].peekLast();
        if (next == last || next.opposite(last) || turns[player].size() >= 2) return false;
        turns[player].addLast(next); return true;
    }
    public void update(double seconds) {
        if (state != State.PLAYING || !Double.isFinite(seconds) || seconds <= 0) return;
        // Foreground stalls are bounded; background time must be discarded via pause().
        accumulator += Math.min(.25, seconds);
        while (state == State.PLAYING && accumulator + 1e-12 >= tickSeconds()) {
            double interval = tickSeconds(); accumulator = Math.max(0, accumulator - interval); tick();
        }
    }
    /** Advances exactly one authoritative move. Both players are judged before either is moved. */
    public void tick() {
        if (state != State.PLAYING) return;
        int[] heads = new int[players()]; boolean[] grows = new boolean[players()];
        boolean collision = false; Direction[] nextDirections = directions.clone();
        for (int p = 0; p < players(); p++) {
            if (!turns[p].isEmpty()) nextDirections[p] = turns[p].removeFirst();
            int x = bodies[p][0] % COLUMNS + nextDirections[p].dx;
            int y = bodies[p][0] / COLUMNS + nextDirections[p].dy;
            heads[p] = cell(x, y); grows[p] = heads[p] == food;
            if (x < 0 || x >= COLUMNS || y < 0 || y >= ROWS) collision = true;
        }
        if (players() == 2 && (heads[0] == heads[1]
                || (heads[0] == bodies[1][0] && heads[1] == bodies[0][0]))) collision = true;
        for (int p = 0; p < players(); p++) {
            for (int other = 0; other < players(); other++) {
                int occupied = lengths[other] - (grows[other] ? 0 : 1);
                for (int i = 0; i < occupied; i++) if (heads[p] == bodies[other][i]) collision = true;
            }
        }
        ticks++;
        if (collision) { state = State.GAME_OVER; accumulator = 0; turns[0].clear(); turns[1].clear(); return; }
        boolean ate = false;
        for (int p = 0; p < players(); p++) {
            directions[p] = nextDirections[p];
            int nextLength = lengths[p] + (grows[p] ? 1 : 0);
            System.arraycopy(bodies[p], 0, bodies[p], 1, nextLength - 1);
            bodies[p][0] = heads[p]; lengths[p] = nextLength;
            if (grows[p]) { score += 10; ate = true; }
        }
        if (ate) spawnFood();
    }
    private void spawnFood() {
        boolean[] occupied = new boolean[CELLS]; int free = CELLS;
        for (int p = 0; p < players(); p++) for (int i = 0; i < lengths[p]; i++) {
            occupied[bodies[p][i]] = true; free--;
        }
        if (free == 0) { food = -1; state = State.WON; accumulator = 0; return; }
        // Explicit PRNG state makes saved parties and network replicas reproducible.
        random += 0x9E3779B97F4A7C15L;
        long z = random; z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        int choice = (int) Long.remainderUnsigned(z, free);
        for (int i = 0; i < CELLS; i++) if (!occupied[i] && choice-- == 0) { food = i; return; }
    }
    /** Integer-only versioned state. Active restores paused, never simulating elapsed wall time. */
    public long[] snapshot() {
        long[] data = new long[15 + lengths[0] + lengths[1] + turns[0].size() + turns[1].size()];
        int i = 0; data[i++] = VERSION; data[i++] = mode.ordinal(); data[i++] = difficulty.ordinal();
        data[i++] = state.ordinal(); data[i++] = score; data[i++] = ticks; data[i++] = random;
        data[i++] = initialSeed; data[i++] = food;
        for (int p = 0; p < 2; p++) {
            data[i++] = lengths[p]; data[i++] = directions[p].ordinal(); data[i++] = turns[p].size();
            for (int n = 0; n < lengths[p]; n++) data[i++] = bodies[p][n];
            for (Direction direction : turns[p]) data[i++] = direction.ordinal();
        }
        return Arrays.copyOf(data, i);
    }
    public boolean restore(long[] data) {
        if (data == null || data.length < 15 || data.length > 15 + CELLS + 4) return false;
        try {
            int i = 0;
            if (data[i++] != VERSION) return false;
            Mode nextMode = Mode.values()[ordinal(data[i++], Mode.values().length)];
            Difficulty nextDifficulty = Difficulty.values()[ordinal(data[i++], Difficulty.values().length)];
            State nextState = State.values()[ordinal(data[i++], State.values().length)];
            long nextScore = data[i++], nextTicks = data[i++], nextRandom = data[i++], nextSeed = data[i++], nextFood = data[i++];
            if (nextScore < 0 || nextScore > (CELLS - (nextMode == Mode.COOP ? 6 : 3)) * 10 || nextScore % 10 != 0
                    || nextTicks < 0 || nextFood < -1 || nextFood >= CELLS) return false;
            int[][] nextBodies = new int[2][CELLS]; int[] nextLengths = new int[2];
            Direction[] nextDirections = new Direction[2];
            @SuppressWarnings("unchecked") ArrayDeque<Direction>[] nextTurns = new ArrayDeque[]{new ArrayDeque<>(), new ArrayDeque<>()};
            Set<Integer> occupied = new HashSet<>();
            for (int p = 0; p < 2; p++) {
                int length = (int) data[i++];
                if (length != data[i - 1] || length < 0 || length > CELLS
                        || (p == 0 || nextMode == Mode.COOP ? length < 3 : length != 0)) return false;
                nextLengths[p] = length;
                nextDirections[p] = Direction.values()[ordinal(data[i++], 4)];
                int count = ordinal(data[i++], 3);
                for (int n = 0; n < length; n++) {
                    long position = data[i++];
                    if (position < 0 || position >= CELLS || !occupied.add((int) position)) return false;
                    nextBodies[p][n] = (int) position;
                    if (n > 0 && distance(nextBodies[p][n - 1], (int) position) != 1) return false;
                }
                if (length > 1) {
                    int head = nextBodies[p][0], neck = nextBodies[p][1];
                    if (head % COLUMNS - neck % COLUMNS != nextDirections[p].dx
                            || head / COLUMNS - neck / COLUMNS != nextDirections[p].dy) return false;
                }
                Direction last = nextDirections[p];
                for (int n = 0; n < count; n++) {
                    Direction next = Direction.values()[ordinal(data[i++], 4)];
                    if (next == last || next.opposite(last)) return false;
                    nextTurns[p].add(next); last = next;
                }
                if (length == 0 && count != 0) return false;
            }
            if (i != data.length || occupied.size() != (nextMode == Mode.COOP ? 6 : 3) + nextScore / 10
                    || (nextFood >= 0 && occupied.contains((int) nextFood))
                    || (nextState == State.WON ? occupied.size() != CELLS || nextFood != -1 : nextFood < 0)) return false;
            mode = nextMode; difficulty = nextDifficulty; score = (int) nextScore; ticks = nextTicks;
            random = nextRandom; initialSeed = nextSeed; food = (int) nextFood;
            state = nextState == State.PLAYING ? State.PAUSED : nextState; accumulator = 0;
            for (int p = 0; p < 2; p++) {
                lengths[p] = nextLengths[p]; directions[p] = nextDirections[p];
                System.arraycopy(nextBodies[p], 0, bodies[p], 0, CELLS);
                turns[p].clear(); turns[p].addAll(nextTurns[p]);
            }
            return true;
        } catch (IndexOutOfBoundsException | IllegalArgumentException error) { return false; }
    }
    private static int ordinal(long value, int limit) {
        if (value < 0 || value >= limit) throw new IllegalArgumentException("ordinal"); return (int) value;
    }
    private static int distance(int a, int b) { return Math.abs(a % COLUMNS - b % COLUMNS) + Math.abs(a / COLUMNS - b / COLUMNS); }
    private static int cell(int x, int y) { return y * COLUMNS + x; }
    private void checkPlayer(int p) { if (p < 0 || p >= players()) throw new IllegalArgumentException("player"); }
    private void checkSegment(int p, int n) { checkPlayer(p); if (n < 0 || n >= lengths[p]) throw new IllegalArgumentException("segment"); }
}
