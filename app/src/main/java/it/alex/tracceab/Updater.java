package it.alex.tracceab;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * Aggiornamenti automatici.
 * - Interfaccia (index.html, leaflet): scaricata all'avvio, verificata con SHA-256, attiva subito.
 * - App nativa (APK): se c'è una versione più nuova chiede conferma e la installa (i dati restano).
 * Sorgente: BuildConfig.UPDATE_BASE (branch "dist" del repository GitHub).
 */
public class Updater {

    private static final String PREFS = "updater";
    private final MainActivity act;
    private volatile boolean busy = false;

    Updater(MainActivity a) { act = a; }

    static String base() { return BuildConfig.UPDATE_BASE; }

    static boolean enabled() { return base() != null && base().startsWith("https://"); }

    static File webDir(Context c) { return new File(c.getFilesDir(), "web"); }

    static int bundledWeb(Context c) {
        try (InputStream in = c.getAssets().open("webversion.txt")) {
            return Integer.parseInt(new String(readAll(in), StandardCharsets.UTF_8).trim());
        } catch (Exception e) { return 0; }
    }

    static int downloadedWeb(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("web", 0);
    }

    /** True se l'interfaccia scaricata è più nuova di quella contenuta nell'APK. */
    static boolean useDownloaded(Context c) {
        return downloadedWeb(c) > bundledWeb(c) && new File(webDir(c), "index.html").exists();
    }

    static int currentWeb(Context c) { return Math.max(bundledWeb(c), useDownloaded(c) ? downloadedWeb(c) : 0); }

    /** Controlla gli aggiornamenti in un thread separato. manual = richiesto dall'utente. */
    void check(final boolean manual) {
        if (!enabled()) { if (manual) act.jsToast("Aggiornamenti non configurati in questa versione"); return; }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                JSONObject v = new JSONObject(new String(get(base() + "version.json?t=" + System.currentTimeMillis()), StandardCharsets.UTF_8));
                int web = v.getInt("web");
                int minApk = v.optInt("minApk", 0);
                int apk = v.optInt("apk", 0);
                String notes = v.optString("notes", "");
                int myApk = BuildConfig.VERSION_CODE;
                boolean did = false;

                if (web > currentWeb(act) && myApk >= minApk) {
                    downloadWeb(v.getJSONObject("files"), web);
                    did = true;
                    act.runOnUiThread(() -> act.reloadWeb("App aggiornata (interfaccia v" + web + ")" + (notes.isEmpty() ? "" : ": " + notes)));
                }
                if (apk > myApk) {
                    final String sha = v.optString("apkSha256", "");
                    act.runOnUiThread(() -> askApk(apk, notes, sha));
                    did = true;
                }
                if (!did && manual) act.jsToast("L'app è già aggiornata (v" + myApk + "." + currentWeb(act) + ")");
            } catch (Exception e) {
                if (manual) act.jsToast("Controllo aggiornamenti non riuscito: " + e.getMessage());
            } finally {
                busy = false;
            }
        }).start();
    }

    private void downloadWeb(JSONObject files, int version) throws Exception {
        File tmp = new File(act.getFilesDir(), "web_tmp");
        deleteDir(tmp);
        tmp.mkdirs();
        Iterator<String> it = files.keys();
        while (it.hasNext()) {
            String name = it.next();
            if (name.contains("/") || name.contains("..")) continue;
            byte[] data = get(base() + "web/" + name + "?v=" + version);
            if (!sha256(data).equalsIgnoreCase(files.getString(name)))
                throw new Exception("verifica non riuscita per " + name);
            try (FileOutputStream fo = new FileOutputStream(new File(tmp, name))) { fo.write(data); }
        }
        File dir = webDir(act);
        deleteDir(dir);
        if (!tmp.renameTo(dir)) throw new Exception("rename");
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("web", version).apply();
    }

    // ---------------- aggiornamento APK ----------------

    private void askApk(int apk, String notes, String sha) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act)
                .setTitle("Nuova versione dell'app (v" + apk + ")")
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "Installo l'aggiornamento? I tuoi dati restano.")
                .setPositiveButton("Aggiorna", (d, w) -> installApk(sha))
                .setNegativeButton("Più tardi", null)
                .show();
    }

    void installApk(String sha) {
        if (LocationService.trackId >= 0) {
            act.jsToast("Ferma prima la registrazione, poi aggiorna");
            return;
        }
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            act.jsToast("Consenti a Tracce A→B di installare app, poi torna indietro: l'aggiornamento riparte da solo", 7000);
            act.pendingApkSha = sha;
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        act.jsToast("Scarico l'aggiornamento…");
        new Thread(() -> {
            try {
                byte[] apk = get(base() + "tracce-ab.apk?t=" + System.currentTimeMillis());
                if (!sha.isEmpty() && !sha256(apk).equalsIgnoreCase(sha))
                    throw new Exception("file non integro, riprova tra qualche minuto");
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(act.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (OutputStream o = s.openWrite("app", 0, apk.length)) {
                        o.write(apk);
                        s.fsync(o);
                    }
                    Intent i = new Intent(act, InstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent cb = PendingIntent.getBroadcast(act, id, i, flags);
                    s.commit(cb.getIntentSender());
                }
            } catch (Exception e) {
                act.jsToast("Aggiornamento non riuscito: " + e.getMessage(), 6000);
            }
        }).start();
    }

    // ---------------- utilità ----------------

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "TracceAB/" + BuildConfig.VERSION_CODE);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) { return readAll(in); }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static String sha256(byte[] data) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static void deleteDir(File d) {
        if (d == null || !d.exists()) return;
        File[] list = d.listFiles();
        if (list != null) for (File f : list) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }

    static InputStream openWeb(Context c, String name) throws Exception {
        if (useDownloaded(c)) {
            File f = new File(webDir(c), name);
            if (f.exists()) return new FileInputStream(f);
        }
        return c.getAssets().open(name);
    }
}
