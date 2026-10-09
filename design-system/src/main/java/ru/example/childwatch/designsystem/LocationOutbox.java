package ru.example.childwatch.designsystem;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable measured fixes. Independent of Room and partitioned by server/family/own phone. Call on IO. */
public final class LocationOutbox extends SQLiteOpenHelper {
    private final Context context;
    private static final long MAX_AGE = 48L * 60 * 60 * 1000;
    private static final int MAX_POINTS = 10000;
    public LocationOutbox(Context context) { super(context.getApplicationContext(), "location_outbox.db", null, 3); this.context = context.getApplicationContext(); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE fixes(scope TEXT NOT NULL, time INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL, speed REAL, speed_accuracy REAL, elapsed_nanos TEXT, boot_session TEXT, PRIMARY KEY(scope,time))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) { db.execSQL("ALTER TABLE fixes ADD COLUMN speed REAL"); db.execSQL("ALTER TABLE fixes ADD COLUMN speed_accuracy REAL"); }
        if (oldVersion < 3) { db.execSQL("ALTER TABLE fixes ADD COLUMN elapsed_nanos TEXT"); db.execSQL("ALTER TABLE fixes ADD COLUMN boot_session TEXT"); }
    }

    public static String scope(String server, String family, String own) {
        if (server == null || family == null || own == null || server.trim().isEmpty() || family.trim().isEmpty() || own.trim().isEmpty()) return null;
        return new JSONArray().put(server.trim()).put(family.trim()).put(own.trim()).toString();
    }
    public synchronized void enqueue(String scope, Location fix, boolean moving) {
        if (scope == null || !LocationQuality.usable(fix)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("fixes", "time<?", new String[]{Long.toString(System.currentTimeMillis() - MAX_AGE)});
            try (Cursor last = db.rawQuery("SELECT time,elapsed_nanos,boot_session FROM fixes WHERE scope=? ORDER BY rowid DESC LIMIT 1", new String[]{scope})) {
                if (last.moveToFirst()) {
                    long interval = fix.getTime() - last.getLong(0);
                    Long nanos = LocationMotion.measurementElapsedRealtimeNanos(fix);
                    String boot = LocationMotion.bootSessionId(context);
                    if (nanos != null && !last.isNull(1) && boot.equals(last.getString(2))) {
                        interval = (nanos - Long.parseLong(last.getString(1))) / 1000000;
                    } else if (!last.isNull(2) && !boot.equals(last.getString(2))) {
                        interval = Long.MAX_VALUE; // never throttle the first fix of a new boot
                    }
                    if (interval >= 0 && interval < (moving ? 5000 : 60000)) { db.setTransactionSuccessful(); return; }
                }
            }
            db.execSQL("INSERT OR IGNORE INTO fixes(scope,time,lat,lon,accuracy,speed,speed_accuracy,elapsed_nanos,boot_session) VALUES(?,?,?,?,?,?,?,?,?)",
                new Object[]{scope, fix.getTime(), fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), LocationMotion.speed(fix), LocationMotion.accuracy(fix), LocationMotion.measurementElapsedRealtimeNanos(fix) == null ? null : Long.toString(fix.getElapsedRealtimeNanos()), LocationMotion.measurementElapsedRealtimeNanos(fix) == null ? null : LocationMotion.bootSessionId(context)});
            db.execSQL("DELETE FROM fixes WHERE scope=? AND time NOT IN (SELECT time FROM fixes WHERE scope=? ORDER BY time DESC LIMIT ?)", new Object[]{scope, scope, MAX_POINTS});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized JSONArray batch(String scope, long before) {
        JSONArray result = new JSONArray();
        if (scope == null) return result;
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT time,lat,lon,accuracy,speed,speed_accuracy,elapsed_nanos,boot_session FROM fixes WHERE scope=? AND time<? AND time>=? ORDER BY time LIMIT 100",
            new String[]{scope, Long.toString(before), Long.toString(System.currentTimeMillis() - MAX_AGE)})) {
            while (rows.moveToNext()) {
                try { result.put(new JSONObject().put("timestamp", rows.getLong(0)).put("latitude", rows.getDouble(1))
                    .put("longitude", rows.getDouble(2)).put("accuracy", rows.getDouble(3))
                    .put("speedMps", rows.isNull(4) ? JSONObject.NULL : rows.getDouble(4))
                    .put("speedAccuracyMps", rows.isNull(5) ? JSONObject.NULL : rows.getDouble(5))
                    .put("measurementElapsedRealtimeNanos", rows.isNull(6) ? JSONObject.NULL : rows.getString(6))
                    .put("bootSessionId", rows.isNull(7) ? JSONObject.NULL : rows.getString(7))); }
                catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
        }
        return result;
    }
    public synchronized void acknowledge(String scope, long timestamp) {
        if (scope != null) getWritableDatabase().delete("fixes", "scope=? AND time=?", new String[]{scope, Long.toString(timestamp)});
    }
    public synchronized void acknowledge(String scope, JSONArray batch) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { for (int i=0; i<batch.length(); i++) acknowledge(scope, batch.optJSONObject(i).optLong("timestamp")); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
}
