package it.alex.tracceab;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Funzioni native chiamabili dalla pagina come window.Android.xxx(). */
public class Bridge {

    private final MainActivity act;

    Bridge(MainActivity a) { act = a; }

    @JavascriptInterface
    public String state() {
        try {
            JSONObject o = new JSONObject();
            o.put("rec", LocationService.trackId >= 0);
            o.put("track", LocationService.trackId);
            o.put("vehicle", LocationService.vehicle);
            o.put("start", LocationService.startTime);
            o.put("count", LocationService.count);
            o.put("acc", LocationService.lastAcc);
            o.put("lastFinished", LocationService.lastFinished);
            return o.toString();
        } catch (Exception e) {
            return "{\"rec\":false,\"track\":-1}";
        }
    }

    @JavascriptInterface
    public String startRec(String vehicle) {
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread(act::askPermissions);
            return "noperm";
        }
        if (LocationService.trackId >= 0) return "ok";
        try {
            Intent i = new Intent(act, LocationService.class);
            i.putExtra(LocationService.EXTRA_VEHICLE, vehicle);
            act.startForegroundService(i);
            return "ok";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @JavascriptInterface
    public void stopRec() {
        if (LocationService.trackId < 0) return;
        Intent i = new Intent(act, LocationService.class).setAction(LocationService.ACTION_STOP);
        try { act.startService(i); } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public String livePoints(long track, int from) {
        return Db.get(act).livePointsJson(track, from);
    }

    @JavascriptInterface
    public String lastFix() {
        double[] f = LocationService.lastFix;
        if (f == null && act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // nessuna registrazione in corso: uso l'ultima posizione nota del telefono
            android.location.LocationManager lm =
                    (android.location.LocationManager) act.getSystemService(android.content.Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : lm.getProviders(true)) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ignored) { }
            }
            if (best != null) f = new double[]{best.getLatitude(), best.getLongitude()};
        }
        return f == null ? "null" : "[" + f[0] + "," + f[1] + "]";
    }

    @JavascriptInterface
    public String appInfo() {
        return "{\"apk\":" + BuildConfig.VERSION_CODE + ",\"web\":" + Updater.currentWeb(act)
                + ",\"updates\":" + Updater.enabled() + "}";
    }

    @JavascriptInterface
    public void checkUpdate() { act.updater.check(true); }

    @JavascriptInterface
    public String allTracks() { return Db.get(act).allTracksJson(); }

    @JavascriptInterface
    public void deleteTrack(long id) { Db.get(act).deleteTrack(id); }

    @JavascriptInterface
    public void deleteDemo() { Db.get(act).deleteDemo(); }

    @JavascriptInterface
    public void deleteAll() { Db.get(act).deleteAll(); }

    @JavascriptInterface
    public int importTracks(String json) {
        try { return Db.get(act).importJson(json); } catch (Exception e) { return 0; }
    }

    @JavascriptInterface
    public String kvGet(String k) { return Db.get(act).kvGet(k); }

    @JavascriptInterface
    public void kvSet(String k, String v) { Db.get(act).kvSet(k, v); }

    @JavascriptInterface
    public boolean batteryOk() {
        PowerManager pm = (PowerManager) act.getSystemService(android.content.Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(act.getPackageName());
    }

    @JavascriptInterface
    public void openBatterySettings() {
        act.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
            } catch (Exception e) {
                try { act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception ignored) { }
            }
        });
    }

    /** Salva un file nella cartella Download del telefono. Restituisce il percorso o un errore. */
    @JavascriptInterface
    public String saveFile(String name, String mime, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TracceAB");
                Uri uri = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("MediaStore");
                try (OutputStream os = act.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("stream");
                    os.write(data);
                }
                return "Download/TracceAB/" + name;
            } else {
                File dir = act.getExternalFilesDir(null);
                File f = new File(dir, name);
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "ERRORE: " + e.getMessage();
        }
    }
}
