package ru.example.childwatch.designsystem;

/** Relative steering: one finger can trace several corners without lifting. */
final class SnakeSwipe {
    private final float threshold;
    private float x, y;
    private boolean active;
    private SnakeEngine.Direction last;
    SnakeSwipe(float threshold) { this.threshold = threshold; }
    void begin(float x, float y) { this.x = x; this.y = y; active = true; last = null; }
    void cancel() { active = false; last = null; }
    SnakeEngine.Direction move(float nextX, float nextY) {
        if (!active) return null;
        float dx = nextX - x, dy = nextY - y;
        float ax = Math.abs(dx), ay = Math.abs(dy);
        if (Math.max(ax, ay) < threshold || Math.max(ax, ay) < Math.min(ax, ay) * 1.2f) return null;
        SnakeEngine.Direction direction = ax > ay
            ? dx > 0 ? SnakeEngine.Direction.RIGHT : SnakeEngine.Direction.LEFT
            : dy > 0 ? SnakeEngine.Direction.DOWN : SnakeEngine.Direction.UP;
        x = nextX; y = nextY;
        if (direction == last) return null;
        last = direction;
        return direction;
    }
}
