package ru.example.childwatch.designsystem;

import android.view.MotionEvent;
import android.view.View;

/** Shared pointer ownership and cancellation for solo and server-authoritative steering. */
final class SnakeTouch {
    interface Listener { void turn(SnakeEngine.Direction direction); }
    private final SnakeSwipe swipe;
    private final Listener listener;
    private int pointer = -1;
    SnakeTouch(float density, Listener listener) { swipe = new SnakeSwipe(16 * density); this.listener = listener; }
    void cancel() { pointer = -1; swipe.cancel(); }
    boolean handle(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0); swipe.begin(event.getX(), event.getY());
                view.getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index >= 0) move(event.getX(index), event.getY(index));
                return true;
            case MotionEvent.ACTION_UP:
                if (event.getPointerId(0) == pointer) move(event.getX(), event.getY());
                cancel(); view.getParent().requestDisallowInterceptTouchEvent(false); view.performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                cancel(); view.getParent().requestDisallowInterceptTouchEvent(false); return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) cancel();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: return true;
            default: return false;
        }
    }
    private void move(float x, float y) {
        SnakeEngine.Direction direction = swipe.move(x, y);
        if (direction != null) listener.turn(direction);
    }
}
