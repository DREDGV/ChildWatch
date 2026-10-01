package ru.example.childwatch.designsystem;

import android.graphics.Point;

import java.util.ArrayList;
import java.util.List;

/** Separates nearby circular avatars by the smallest needed screen-space movement. */
public final class MapAvatarPlacement {
    private MapAvatarPlacement() { }

    public static final class Item {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public Item(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }
    }

    public static List<Point> place(List<Item> items, int viewportWidth, int viewportHeight,
                                    int topInset, int gap) {
        int count = items.size();
        double[] x = new double[count];
        double[] y = new double[count];
        boolean[] visible = new boolean[count];
        for (int i = 0; i < count; i++) {
            Item item = items.get(i);
            x[i] = item.x;
            y[i] = item.y;
            // Never drag an off-screen GPS location into the visible map.
            visible[i] = viewportWidth <= 0 || viewportHeight <= 0 ||
                    (item.x >= 0 && item.x < viewportWidth &&
                     item.y >= topInset && item.y < viewportHeight);
        }

        // Symmetric pushes preserve the group's centre. The separation changes
        // gradually when the map is zoomed or panned, unlike a grid layout.
        for (int pass = 0; pass < 96; pass++) {
            boolean moved = false;
            for (int i = 0; i < count; i++) {
                if (!visible[i]) continue;
                for (int j = i + 1; j < count; j++) {
                    if (!visible[j]) continue;
                    double dx = x[j] - x[i];
                    double dy = y[j] - y[i];
                    double distance = Math.hypot(dx, dy);
                    double minDistance =
                            (Math.max(items.get(i).width, items.get(i).height) +
                             Math.max(items.get(j).width, items.get(j).height)) / 2.0 + gap + 1;
                    if (distance >= minDistance) continue;
                    if (distance < 0.001) {
                        double angle = (i * 7 + j * 11) * 2.399963229728653;
                        dx = Math.cos(angle);
                        dy = Math.sin(angle);
                        distance = 1;
                    }
                    double push = (minDistance - distance) / 2.0;
                    double shiftX = dx / distance * push;
                    double shiftY = dy / distance * push;
                    x[i] -= shiftX;
                    y[i] -= shiftY;
                    x[j] += shiftX;
                    y[j] += shiftY;
                    moved = true;
                }
            }
            // An avatar may start on the visible edge and be pushed beyond it.
            // Keep the whole icon on screen while resolving the next pass.
            if (viewportWidth > 0 && viewportHeight > 0) {
                for (int i = 0; i < count; i++) {
                    if (!visible[i]) continue;
                    double halfWidth = items.get(i).width / 2.0;
                    double halfHeight = items.get(i).height / 2.0;
                    double minX = halfWidth;
                    double maxX = Math.max(minX, viewportWidth - halfWidth);
                    double minY = topInset + halfHeight;
                    double maxY = Math.max(minY, viewportHeight - halfHeight);
                    double boundedX = Math.max(minX, Math.min(maxX, x[i]));
                    double boundedY = Math.max(minY, Math.min(maxY, y[i]));
                    if (boundedX != x[i] || boundedY != y[i]) moved = true;
                    x[i] = boundedX;
                    y[i] = boundedY;
                }
            }
            if (!moved) break;
        }

        List<Point> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new Point((int) Math.round(x[i]), (int) Math.round(y[i])));
        }
        return result;
    }
}
