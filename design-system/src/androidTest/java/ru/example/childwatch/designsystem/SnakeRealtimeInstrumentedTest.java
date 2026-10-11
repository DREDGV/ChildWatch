package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

/** Actual Android Socket.IO client against the loopback-only in-memory Node fixture. */
@RunWith(AndroidJUnit4.class)
public class SnakeRealtimeInstrumentedTest {
    private interface Condition { boolean ready() throws Exception; }
    private static final class Watch implements SnakeRealtimeBridge.Listener {
        final CopyOnWriteArrayList<JSONObject> states = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> statuses = new CopyOnWriteArrayList<>();
        volatile JSONObject latest; volatile int player = -1;
        public void onState(JSONObject state) { states.add(state); latest = state; }
        public void onStatus(String code) { statuses.add(code); }
        public void onPlayer(int value) { player = value; }
        boolean phase(String phase) { return latest != null && phase.equals(latest.optString("phase")); }
        int count() { return states.size() + statuses.size(); }
    }
    private void until(long deadline, String description, Condition condition) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.ready()) return; Thread.sleep(20); }
        fail("Timed out: " + description);
    }
    private JSONObject fixture() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL("http://127.0.0.1:8879/fixture").openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try {
            assertEquals(200, connection.getResponseCode());
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private GameOnlineBridge.Host host(JSONObject fixture, String role) throws Exception {
        JSONObject actor = fixture.getJSONObject(role);
        SnakeRealtimeBridge.Credentials credentials = new SnakeRealtimeBridge.Credentials(fixture.getString("url"),
                fixture.getString("familyId"), actor.getString("memberId"), actor.getString("token"));
        return new GameOnlineBridge.Host() {
            public String scopeKey() { return "snake-native-" + role; }
            public JSONObject request(String method, String path, JSONObject body) { throw new AssertionError("Game HTTP outbox must not be used"); }
            public SnakeRealtimeBridge.Credentials snakeCredentials() { return credentials; }
        };
    }
    private boolean sameMovement(Watch first, Watch second) throws Exception {
        for (JSONObject a : first.states) {
            if (!"PLAYING".equals(a.optString("phase"))) continue;
            JSONObject snake = a.getJSONObject("snake");
            if (Long.parseLong(snake.getString("tick")) < 2
                    || !"UP".equals(snake.getJSONArray("players").getJSONObject(0).getString("direction"))
                    || !"DOWN".equals(snake.getJSONArray("players").getJSONObject(1).getString("direction"))) continue;
            for (JSONObject b : second.states) if (a.getString("epoch").equals(b.optString("epoch"))
                    && a.getLong("revision") == b.optLong("revision")) {
                assertEquals(a.getJSONObject("snake").toString(), b.getJSONObject("snake").toString());
                int head = snake.getJSONArray("players").getJSONObject(0).getJSONArray("body").getInt(0);
                assertTrue("An UP turn changes the row", head / SnakeEngine.COLUMNS < 8);
                int guestHead = snake.getJSONArray("players").getJSONObject(1).getJSONArray("body").getInt(0);
                assertTrue("A DOWN turn changes the row", guestHead / SnakeEngine.COLUMNS > 17);
                return true;
            }
        }
        return false;
    }
    @Test public void twoRealClientsShareTurnsPauseAndResumeAfterReconnection() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 25000;
        Instrumentation runner = InstrumentationRegistry.getInstrumentation(); Context context = runner.getTargetContext();
        JSONObject fixture = fixture(); String gameId = fixture.getString("gameId");
        Map<String, ?> outboxBefore = context.getSharedPreferences("family-games-outbox",0).getAll();
        GameOnlineBridge.Host previous = GameOnlineBridge.host;
        GameOnlineBridge.Host host = host(fixture,"host"), guest = host(fixture,"guest");
        Watch h = new Watch(), g = new Watch(), replacement = new Watch();
        SnakeRealtimeBridge.Connection[] connections = new SnakeRealtimeBridge.Connection[3];
        try {
            runner.runOnMainSync(() -> {
                GameOnlineBridge.host = host; connections[0] = new SnakeRealtimeBridge.Connection(gameId,h);
                GameOnlineBridge.host = guest; connections[1] = new SnakeRealtimeBridge.Connection(gameId,g);
                connections[0].start(); connections[1].start();
            });
            until(deadline,"both authenticated players",() -> h.player == 0 && g.player == 1
                    && h.latest != null && h.latest.getJSONArray("connected").getBoolean(0) && h.latest.getJSONArray("connected").getBoolean(1));
            connections[0].command("READY"); connections[1].command("READY");
            until(deadline,"authoritative play",() -> h.phase("PLAYING") && g.phase("PLAYING"));
            connections[0].turn(SnakeEngine.Direction.UP); connections[1].turn(SnakeEngine.Direction.DOWN);
            // Repeated touch while one command is pending must not persist a stale movement.
            connections[0].turn(SnakeEngine.Direction.UP);
            until(deadline,"matching real movement on both clients",() -> sameMovement(h,g));
            assertFalse(h.statuses.toString(),h.statuses.contains("SNAKE_INVALID_STATE"));
            assertFalse(g.statuses.toString(),g.statuses.contains("SNAKE_INVALID_STATE"));
            String epoch = h.latest.getString("epoch"); connections[1].close();
            until(deadline,"remaining player paused",() -> h.phase("PAUSED") && !epoch.equals(h.latest.getString("epoch")));
            String pausedTick = h.latest.getJSONObject("snake").getString("tick");
            Thread.sleep(300); assertEquals(pausedTick,h.latest.getJSONObject("snake").getString("tick"));
            int closedCount = g.count(); Thread.sleep(100); assertEquals(closedCount,g.count());
            runner.runOnMainSync(() -> { GameOnlineBridge.host = guest;
                connections[2] = new SnakeRealtimeBridge.Connection(gameId,replacement); connections[2].start(); });
            until(deadline,"replacement joins new epoch",() -> replacement.player == 1 && h.latest.getJSONArray("connected").getBoolean(1));
            String freshEpoch = h.latest.getString("epoch"); assertNotEquals(epoch,freshEpoch);
            connections[0].command("READY"); connections[2].command("READY");
            until(deadline,"resume shared simulation",() -> h.phase("PLAYING") && replacement.phase("PLAYING")
                    && Long.parseLong(h.latest.getJSONObject("snake").getString("tick")) > Long.parseLong(pausedTick));
            connections[0].command("PAUSE");
            until(deadline,"explicit foreground pause",() -> h.phase("PAUSED") && replacement.phase("PAUSED"));
            runner.runOnMainSync(() -> { connections[0].close(); connections[2].close(); });
            runner.waitForIdleSync(); int hostCount = h.count(), guestCount = replacement.count();
            Thread.sleep(400); assertEquals(hostCount,h.count()); assertEquals(guestCount,replacement.count());
            assertEquals(outboxBefore,context.getSharedPreferences("family-games-outbox",0).getAll());
        } finally {
            runner.runOnMainSync(() -> { for (SnakeRealtimeBridge.Connection connection : connections) if (connection != null) connection.close();
                GameOnlineBridge.host = previous; });
        }
    }
}
