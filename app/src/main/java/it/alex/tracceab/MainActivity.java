package it.alex.tracceab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Schermata principale: l'interfaccia (mappa, luoghi, tratte, confronto) è in assets/index.html. */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 42;
    private static final int REQ_PERM = 7;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    Updater updater;
    volatile String pendingApkSha = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#14181F"));
        getWindow().setNavigationBarColor(Color.parseColor("#1C222C"));

        Db.get(this).recoverLive(LocationService.trackId);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " TracceAB/1.0");
        web.addJavascriptInterface(new Bridge(this), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (HOST.equals(host)) return asset(u.getPath());
                if (host.endsWith("tile.openstreetmap.org")) return tile(u.getPath());
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Scegli file GPX o backup"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
        askPermissions();
        updater = new Updater(this);
        updater.check(false);
    }

    /** Chiede posizione (e notifiche su Android 13+). */
    void askPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (web != null) web.evaluateJavascript("window.poll&&poll()", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) res = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(res);
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.poll&&poll()", null);
        if (pendingApkSha != null && getPackageManager().canRequestPackageInstalls()) {
            String sha = pendingApkSha;
            pendingApkSha = null;
            updater.installApk(sha);
        }
    }

    /** Mostra un messaggio nella pagina (chiamabile da qualsiasi thread). */
    void jsToast(String msg) { jsToast(msg, 3500); }

    void jsToast(String msg, int ms) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.toast&&toast(" + org.json.JSONObject.quote(msg) + "," + ms + ")", null);
        });
    }

    /** Ricarica l'interfaccia dopo un aggiornamento e mostra un messaggio. */
    void reloadWeb(String msg) {
        if (web == null) return;
        web.reload();
        web.postDelayed(() -> jsToast(msg, 5000), 1500);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // non chiudo l'app: la mando in secondo piano (la registrazione continua comunque)
        moveTaskToBack(true);
    }

    // ---------- file dell'app (index.html, leaflet) ----------
    private WebResourceResponse asset(String path) {
        if (path == null || path.equals("/")) path = "/index.html";
        String name = path.substring(1);
        String mime = name.endsWith(".js") ? "application/javascript"
                : name.endsWith(".css") ? "text/css"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".svg") ? "image/svg+xml" : "text/html";
        try {
            InputStream in = Updater.openWeb(this, name);
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null,
                    new ByteArrayInputStream(new byte[0]));
        }
    }

    // ---------- tessere OpenStreetMap con cache su disco ----------
    private WebResourceResponse tile(String path) {
        if (path == null || !path.matches("/\\d+/\\d+/\\d+\\.png")) return null;
        File dir = new File(getCacheDir(), "tiles");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, path.substring(1).replace('/', '_'));
        long maxAge = 30L * 24 * 3600 * 1000;
        try {
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAge) {
                return new WebResourceResponse("image/png", null, new FileInputStream(f));
            }
            HttpURLConnection c = (HttpURLConnection) new URL("https://tile.openstreetmap.org" + path).openConnection();
            c.setRequestProperty("User-Agent", "TracceAB/1.0 (Android; uso personale)");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] bytes = bo.toByteArray();
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(bytes); } catch (Exception ignored) { }
            return new WebResourceResponse("image/png", null, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            try {
                if (f.exists()) return new WebResourceResponse("image/png", null, new FileInputStream(f));
            } catch (Exception ignored) { }
            return new WebResourceResponse("image/png", null, 404, "Not found", null,
                    new ByteArrayInputStream(new byte[0]));
        }
    }
}
