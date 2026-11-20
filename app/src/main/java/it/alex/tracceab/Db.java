package it.alex.tracceab;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Archivio locale: tracce, punti GPS e impostazioni (chiave/valore). */
public class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "tracce.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT, vehicle TEXT, start INTEGER, end INTEGER, live INTEGER DEFAULT 0, demo INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE points(track INTEGER, lat REAL, lon REAL, t INTEGER, acc REAL)");
        db.execSQL("CREATE INDEX ix_points ON points(track, t)");
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    public synchronized long newTrack(String vehicle, long start) {
        ContentValues v = new ContentValues();
        v.put("vehicle", vehicle);
        v.put("start", start);
        v.put("end", start);
        v.put("live", 1);
        return getWritableDatabase().insert("tracks", null, v);
    }

    public synchronized void addPoint(long track, double lat, double lon, long t, float acc) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("track", track);
        v.put("lat", lat);
        v.put("lon", lon);
        v.put("t", t);
        v.put("acc", acc);
        db.insert("points", null, v);
        db.execSQL("UPDATE tracks SET end=? WHERE id=?", new Object[]{t, track});
    }

    private int pointCount(long track) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM points WHERE track=?", new String[]{String.valueOf(track)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** Chiude una traccia; se ha meno di 2 punti la elimina. */
    public synchronized boolean finishTrack(long track) {
        if (pointCount(track) < 2) { deleteTrack(track); return false; }
        getWritableDatabase().execSQL("UPDATE tracks SET live=0 WHERE id=?", new Object[]{track});
        return true;
    }

    /** All'avvio: chiude eventuali registrazioni rimaste aperte (app chiusa dal sistema). */
    public synchronized void recoverLive(long except) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM tracks WHERE live=1 AND id<>?", new String[]{String.valueOf(except)});
        try {
            while (c.moveToNext()) finishTrack(c.getLong(0));
        } finally { c.close(); }
    }

    public synchronized void deleteTrack(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM points WHERE track=?", new Object[]{id});
        db.execSQL("DELETE FROM tracks WHERE id=?", new Object[]{id});
    }

    public synchronized void deleteDemo() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM points WHERE track IN (SELECT id FROM tracks WHERE demo=1)");
        db.execSQL("DELETE FROM tracks WHERE demo=1");
    }

    public synchronized void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM points WHERE track IN (SELECT id FROM tracks WHERE live=0)");
        db.execSQL("DELETE FROM tracks WHERE live=0");
    }

    private static void appendPoint(StringBuilder sb, Cursor p) {
        sb.append('[').append(p.getDouble(0)).append(',').append(p.getDouble(1))
          .append(',').append(p.getLong(2)).append(',').append(Math.round(p.getDouble(3))).append(']');
    }

    /** Tutte le tracce concluse, con i punti, in JSON. */
    public synchronized String allTracksJson() {
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append('[');
        Cursor t = db.rawQuery("SELECT id, vehicle, start, end, demo FROM tracks WHERE live=0 ORDER BY start DESC", null);
        boolean firstT = true;
        try {
            while (t.moveToNext()) {
                long id = t.getLong(0);
                if (!firstT) sb.append(',');
                firstT = false;
                sb.append("{\"id\":").append(id)
                  .append(",\"vehicle\":").append(JSONObject.quote(t.getString(1)))
                  .append(",\"start\":").append(t.getLong(2))
                  .append(",\"end\":").append(t.getLong(3))
                  .append(",\"demo\":").append(t.getInt(4))
                  .append(",\"live\":0,\"points\":[");
                Cursor p = db.rawQuery("SELECT lat, lon, t, acc FROM points WHERE track=? ORDER BY t", new String[]{String.valueOf(id)});
                try {
                    boolean first = true;
                    while (p.moveToNext()) {
                        if (!first) sb.append(',');
                        first = false;
                        appendPoint(sb, p);
                    }
                } finally { p.close(); }
                sb.append("]}");
            }
        } finally { t.close(); }
        sb.append(']');
        return sb.toString();
    }

    /** Punti della traccia in corso a partire dall'indice "from". */
    public synchronized String livePointsJson(long track, int from) {
        StringBuilder sb = new StringBuilder("[");
        Cursor p = getReadableDatabase().rawQuery(
                "SELECT lat, lon, t, acc FROM points WHERE track=? ORDER BY t LIMIT -1 OFFSET ?",
                new String[]{String.valueOf(track), String.valueOf(Math.max(0, from))});
        try {
            boolean first = true;
            while (p.moveToNext()) {
                if (!first) sb.append(',');
                first = false;
                appendPoint(sb, p);
            }
        } finally { p.close(); }
        return sb.append(']').toString();
    }

    /** Importa tracce da JSON: [{vehicle,start,end,demo,points:[[lat,lon,t,acc],...]}] */
    public synchronized int importJson(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray pts = o.getJSONArray("points");
                if (pts.length() < 2) continue;
                ContentValues v = new ContentValues();
                v.put("vehicle", o.optString("vehicle", "auto"));
                v.put("start", o.getLong("start"));
                v.put("end", o.getLong("end"));
                v.put("live", 0);
                v.put("demo", o.optInt("demo", 0));
                long id = db.insert("tracks", null, v);
                for (int k = 0; k < pts.length(); k++) {
                    JSONArray q = pts.getJSONArray(k);
                    ContentValues pv = new ContentValues();
                    pv.put("track", id);
                    pv.put("lat", q.getDouble(0));
                    pv.put("lon", q.getDouble(1));
                    pv.put("t", q.getLong(2));
                    pv.put("acc", q.length() > 3 ? q.optDouble(3, 0) : 0);
                    db.insert("points", null, pv);
                }
                n++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    public synchronized String kvGet(String k) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    public synchronized void kvSet(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
