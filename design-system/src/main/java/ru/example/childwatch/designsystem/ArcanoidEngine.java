package ru.example.childwatch.designsystem;

/** Deterministic local game. Coordinates are logical pixels, unrelated to the display. */
public final class ArcanoidEngine {
    public static final double WIDTH = 1000, HEIGHT = 1400, RADIUS = 20;
    public static final double PADDLE_Y = 1250, PADDLE_WIDTH = 190, PADDLE_HEIGHT = 22;
    public static final int COLUMNS = 6, ROWS = 5, BRICKS = COLUMNS * ROWS;
    public enum State { READY, PLAYING, PAUSED, WON, GAME_OVER }
    public enum Mode { SOLO, LOCAL_COOP }
    private static final String[] LEVEL_PATTERNS = {
        "111111111111111111111111111111",
        "011110111111110011111111011110",
        "100001110011111111011110001100",
        "101010010101101010010101111111",
        "001100011110111111011110001100",
        "111111100001101101100001111111"
    };
    private int level;
    private Mode mode = Mode.SOLO;
    private double secondPaddle = WIDTH / 2;
    public int level() { return level + 1; }
    public int levelCount() { return LEVEL_PATTERNS.length; }
    public boolean campaignComplete() { return state == State.WON && level == levelCount() - 1; }
    public Mode mode() { return mode; }
    public double secondPaddleX() { return secondPaddle; }
    public double secondPaddleY() { return height - paddleY(); }
    public void moveSecondPaddle(double value) {
        if (Double.isFinite(value)) secondPaddle = clamp(value, PADDLE_WIDTH / 2, WIDTH - PADDLE_WIDTH / 2);
    }
    private static long pattern(int index) {
        long mask = 0;
        for (int i = 0; i < BRICKS; i++) if (LEVEL_PATTERNS[index].charAt(i) == '1') mask |= 1L << i;
        return mask;
    }
    private static int completedScore(int beforeLevel) {
        int value = 0;
        for (int i = 0; i < beforeLevel; i++) value += Long.bitCount(pattern(i)) * 10;
        return value;
    }
    /** Clearing a level never launches the next ball without an explicit action. */
    public boolean nextLevel() {
        if (state != State.WON || campaignComplete()) return false;
        level++; bricks = pattern(level); state = State.READY; accumulator = 0; attachBall();
        return true;
    }
    double x, y, vx, vy, paddle;
    int lives, score;
    long bricks;
    State state;
    private double accumulator;
    private double height = HEIGHT;
    public double height() { return height; }
    public double paddleY() { return height - Math.min(150, height * (150.0 / HEIGHT)); }
    public double rowStep() { return Math.min(72, height * .08); }
    public double blockY(int index) { return height * .30 + (index / COLUMNS) * rowStep(); }
    public double blockHeight() { return rowStep() * (56.0 / 72); }
    private double speedScale() { return clamp(height / HEIGHT, .5, 1.2); }
    public void resize(double nextHeight) {
        if (!Double.isFinite(nextHeight)) return;
        nextHeight = clamp(nextHeight, 340, 4000);
        double oldScale = speedScale();
        y = clamp(y / height * nextHeight, RADIUS, nextHeight + RADIUS);
        height = nextHeight;
        double ratio = speedScale() / oldScale; vx *= ratio; vy *= ratio;
        double speed = Math.hypot(vx, vy), cap = Math.min(700, 700 * speedScale());
        if (speed > cap) { vx *= cap / speed; vy *= cap / speed; }
        if (state == State.READY) attachBall();
    }

    public ArcanoidEngine() { restart(); }
    public void restart() { restart(mode); }
    public void restart(Mode nextMode) {
        mode = nextMode == null ? Mode.SOLO : nextMode;
        level = 0; secondPaddle = WIDTH / 2;
        lives = 3; score = 0; bricks = pattern(level);
        paddle = WIDTH / 2; state = State.READY; accumulator = 0; attachBall();
    }
    public State state() { return state; }
    public int lives() { return lives; }
    public int score() { return score; }
    public double ballX() { return x; }
    public double ballY() { return y; }
    public double paddleX() { return paddle; }
    public boolean hasBrick(int index) { return index >= 0 && index < BRICKS && (bricks & (1L << index)) != 0; }
    public static double brickX(int index) { return 66 + (index % COLUMNS) * 146; }
    public static double brickY(int index) { return 420 + (index / COLUMNS) * 72; }
    public static final double BRICK_WIDTH = 136, BRICK_HEIGHT = 56;
    public void movePaddle(double value) {
        if (!Double.isFinite(value)) return;
        paddle = clamp(value, PADDLE_WIDTH / 2, WIDTH - PADDLE_WIDTH / 2);
        if (state == State.READY) attachBall();
    }
    public void launchOrResume() {
        if (state == State.READY) { vx = 170 * speedScale(); vy = -(480 + level * 25) * speedScale(); double speed = Math.hypot(vx, vy), cap = Math.min(700, 700 * speedScale());
            if (speed > cap) { vx *= cap / speed; vy *= cap / speed; }
            state = State.PLAYING; }
        else if (state == State.PAUSED) state = State.PLAYING;
    }
    public void pause() { if (state == State.PLAYING) state = State.PAUSED; accumulator = 0; }
    private void attachBall() { x = paddle; y = paddleY() - RADIUS - 2; vx = vy = 0; }
    public void update(double seconds) {
        if (state != State.PLAYING || !Double.isFinite(seconds) || seconds <= 0) return;
        // Fixed substeps prevent tunnelling. Lost foreground time is never simulated.
        accumulator += Math.min(seconds, .1);
        while (accumulator >= 1.0 / 240 && state == State.PLAYING) {
            accumulator -= 1.0 / 240; step(1.0 / 240);
        }
    }
    private void step(double dt) {
        double previousY = y;
        x += vx * dt; y += vy * dt;
        if (x < RADIUS) { x = RADIUS; vx = Math.abs(vx); }
        if (x > WIDTH - RADIUS) { x = WIDTH - RADIUS; vx = -Math.abs(vx); }
        if (mode == Mode.SOLO && y < RADIUS) { y = RADIUS; vy = Math.abs(vy); }
        if (mode == Mode.LOCAL_COOP && vy < 0
                && previousY - RADIUS >= secondPaddleY() + PADDLE_HEIGHT
                && y - RADIUS <= secondPaddleY() + PADDLE_HEIGHT
                && x >= secondPaddle - PADDLE_WIDTH / 2 - RADIUS
                && x <= secondPaddle + PADDLE_WIDTH / 2 + RADIUS) {
            double angle = clamp((x - secondPaddle) / (PADDLE_WIDTH / 2), -1, 1) * Math.PI / 3;
            double speed = Math.min(Math.min(700, 700 * speedScale()), Math.hypot(vx, vy) + 4);
            vx = Math.sin(angle) * speed; vy = Math.cos(angle) * speed;
            y = secondPaddleY() + PADDLE_HEIGHT + RADIUS;
        }
        if (vy > 0 && previousY + RADIUS <= paddleY() && y + RADIUS >= paddleY()
                && x >= paddle - PADDLE_WIDTH / 2 - RADIUS
                && x <= paddle + PADDLE_WIDTH / 2 + RADIUS) {
            double angle = clamp((x - paddle) / (PADDLE_WIDTH / 2), -1, 1) * Math.PI / 3;
            double speed = Math.min(Math.min(700, 700 * speedScale()), Math.hypot(vx, vy) + 4);
            vx = Math.sin(angle) * speed; vy = -Math.cos(angle) * speed;
            y = paddleY() - RADIUS;
        }
        for (int i = 0; i < BRICKS; i++) {
            if (hasBrick(i) && collide(brickX(i), blockY(i), BRICK_WIDTH, blockHeight())) {
                bricks &= ~(1L << i); score += 10;
                if (bricks == 0) state = State.WON;
                break;
            }
        }
        if (y - RADIUS > height || (mode == Mode.LOCAL_COOP && y + RADIUS < 0)) {
            lives--; state = lives == 0 ? State.GAME_OVER : State.READY;
            attachBall(); accumulator = 0;
        }
    }
    private boolean collide(double left, double top, double width, double height) {
        double dx = x - clamp(x, left, left + width);
        double dy = y - clamp(y, top, top + height);
        double d2 = dx * dx + dy * dy;
        if (d2 > RADIUS * RADIUS) return false;
        if (d2 < 1e-9) {
            // A restored or penetrated ball uses the nearest face, never divides by zero.
            double[] distances = {x - left, left + width - x, y - top, top + height - y};
            int side = 0;
            for (int i = 1; i < 4; i++) if (distances[i] < distances[side]) side = i;
            dx = side == 0 ? -1 : side == 1 ? 1 : 0;
            dy = side == 2 ? -1 : side == 3 ? 1 : 0;
            if (side == 0) x = left - RADIUS;
            if (side == 1) x = left + width + RADIUS;
            if (side == 2) y = top - RADIUS;
            if (side == 3) y = top + height + RADIUS;
        } else {
            double distance = Math.sqrt(d2); dx /= distance; dy /= distance;
            x += dx * (RADIUS - distance); y += dy * (RADIUS - distance);
        }
        double dot = vx * dx + vy * dy;
        if (dot < 0) { vx -= 2 * dot * dx; vy -= 2 * dot * dy; }
        return true;
    }
    static double clamp(double n, double min, double max) { return Math.max(min, Math.min(max, n)); }
    /** Small validated snapshot; active games always restore paused. No family data is involved. */
    public double[] snapshot() {
        return new double[]{3, x, y, vx, vy, paddle, lives, score, bricks, state.ordinal(), height, level, mode.ordinal(), secondPaddle};
    }
    public boolean restore(double[] saved) {
        if (saved == null || !((saved.length == 11 && saved[0] == 2)
                || (saved.length == 14 && saved[0] == 3))) return false;
        for (double n : saved) if (!Double.isFinite(n)) return false;
        int restoredLevel = 0;
        Mode restoredMode = Mode.SOLO;
        double restoredSecondPaddle = WIDTH / 2;
        if (saved[0] == 3) {
            if (saved[11] < 0 || saved[11] >= levelCount() || saved[11] != (int)saved[11]
                    || saved[12] < 0 || saved[12] >= Mode.values().length || saved[12] != (int)saved[12]
                    || saved[13] < PADDLE_WIDTH / 2 || saved[13] > WIDTH - PADDLE_WIDTH / 2) return false;
            restoredLevel = (int)saved[11]; restoredMode = Mode.values()[(int)saved[12]];
            restoredSecondPaddle = saved[13];
        }
        long originalBricks = pattern(restoredLevel);
        if (saved[10] < 340 || saved[10] > 4000 || saved[1] < 0 || saved[1] > WIDTH || saved[2] < (restoredMode == Mode.LOCAL_COOP ? -RADIUS : 0) || saved[2] > saved[10] + RADIUS
                || Math.hypot(saved[3], saved[4]) > 710
                || saved[5] < PADDLE_WIDTH / 2 || saved[5] > WIDTH - PADDLE_WIDTH / 2
                || saved[6] < 0 || saved[6] > 3 || saved[6] != (int)saved[6]
                || saved[8] < 0 || saved[8] > (1L << BRICKS) - 1 || saved[8] != (long)saved[8]
                || (((long)saved[8]) & ~originalBricks) != 0
                || saved[7] != completedScore(restoredLevel) + (Long.bitCount(originalBricks) - Long.bitCount((long)saved[8])) * 10
                || saved[9] < 0 || saved[9] >= State.values().length || saved[9] != (int)saved[9]) return false;
        State restored = State.values()[(int)saved[9]];
        if ((restored == State.WON) != (saved[8] == 0)
                || (restored == State.GAME_OVER) != (saved[6] == 0)) return false;
        level = restoredLevel; mode = restoredMode; secondPaddle = restoredSecondPaddle;
        x = saved[1]; y = saved[2]; vx = saved[3]; vy = saved[4]; paddle = saved[5];
        height = saved[10]; lives = (int)saved[6]; score = (int)saved[7]; bricks = (long)saved[8];
        state = restored == State.PLAYING ? State.PAUSED : restored; accumulator = 0;
        if (state == State.READY) attachBall();
        return true;
    }
}
