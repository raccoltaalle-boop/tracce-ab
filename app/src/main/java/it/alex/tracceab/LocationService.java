package it.alex.tracceab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Locale;

/**
 * Servizio in primo piano che registra il GPS anche a schermo spento.
 * Resta attivo finché c'è la notifica "Registrazione in corso".
 */
public class LocationService extends Service implements LocationListener {

    public static final String ACTION_STOP = "it.alex.tracceab.STOP";
    public static final String ACTION_PAUSE = "it.alex.tracceab.PAUSE";
    public static final String ACTION_RESUME = "it.alex.tracceab.RESUME";
    public static final String EXTRA_VEHICLE = "vehicle";
    private static final String CHANNEL = "rec";
    private static final int NOTIF_ID = 1;

    // stato letto dall'interfaccia
    public static volatile long trackId = -1;
    public static volatile String vehicle = "";
    public static volatile long startTime = 0;
    public static volatile int count = 0;
    public static volatile float lastAcc = -1;
    public static volatile long lastFinished = -1;
    public static volatile double[] lastFix = null;
    public static volatile boolean paused = false;
    public static volatile long pauseStart = 0;
    public static volatile long pausedTotal = 0;
    private static final java.util.List<long[]> pauses = new java.util.ArrayList<>();

    /** Intervalli di pausa della registrazione in corso, in JSON. */
    public static synchronized String pausesJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pauses.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(pauses.get(i)[0]).append(',').append(pauses.get(i)[1]).append(']');
        }
        return sb.append(']').toString();
    }

    private Location last;
    private double distance = 0;
    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (trackId < 0) return;
            updateNotification();
            handler.postDelayed(this, 10000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(intent.getAction())) { pause(); return START_NOT_STICKY; }
        if (ACTION_RESUME.equals(intent.getAction())) { resume(); return START_NOT_STICKY; }
        if (trackId >= 0) return START_NOT_STICKY; // già in registrazione

        vehicle = intent.getStringExtra(EXTRA_VEHICLE);
        if (vehicle == null) vehicle = "auto";
        startTime = System.currentTimeMillis();
        count = 0;
        distance = 0;
        last = null;
        paused = false;
        pauseStart = 0;
        pausedTotal = 0;
        synchronized (LocationService.class) { pauses.clear(); }
        trackId = Db.get(this).newTrack(vehicle, startTime);

        createChannel();
        Notification n = buildNotification("Ricerca segnale GPS…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TracceAB:rec");
        wakeLock.acquire(12L * 60 * 60 * 1000); // massimo 12 ore

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            stopRecording();
            return START_NOT_STICKY;
        }
        handler.postDelayed(tick, 5000);
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(Location loc) {
        lastAcc = loc.hasAccuracy() ? loc.getAccuracy() : -1;
        lastFix = new double[]{loc.getLatitude(), loc.getLongitude()};
        if (trackId < 0 || paused) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 40) return;          // fix impreciso
        long t = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        if (last != null) {
            float d = loc.distanceTo(last);
            long dt = t - last.getTime();
            if (d < 5 && dt < 15000) return;                                   // fermo: non accumulo punti
            if (dt > 0 && d / (dt / 1000f) > 70) return;                       // salto impossibile (>250 km/h)
            distance += d;
        }
        Db.get(this).addPoint(trackId, loc.getLatitude(), loc.getLongitude(), t, lastAcc < 0 ? 0 : lastAcc);
        last = new Location(loc);
        last.setTime(t);
        count++;
        if (count == 1) updateNotification();
    }

    /** Pausa: smette di ascoltare il GPS e annota l'inizio della pausa. */
    private void pause() {
        if (trackId < 0 || paused) return;
        paused = true;
        pauseStart = System.currentTimeMillis();
        if (lm != null) { try { lm.removeUpdates(this); } catch (Exception ignored) { } }
        updateNotification();
    }

    /** Ripresa: salva l'intervallo di pausa e riattiva il GPS. */
    private void resume() {
        if (trackId < 0 || !paused) return;
        long now = System.currentTimeMillis();
        synchronized (LocationService.class) { pauses.add(new long[]{pauseStart, now}); }
        pausedTotal += now - pauseStart;
        paused = false;
        last = null; // il primo punto dopo la pausa non viene confrontato con quello prima
        Db.get(this).setPauses(trackId, pausesJson());
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) { stopRecording(); return; }
        updateNotification();
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void stopRecording() {
        handler.removeCallbacks(tick);
        if (lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) { }
        }
        long id = trackId;
        if (id >= 0 && paused) {
            synchronized (LocationService.class) { pauses.add(new long[]{pauseStart, System.currentTimeMillis()}); }
        }
        if (id >= 0) Db.get(this).setPauses(id, pausesJson());
        paused = false;
        trackId = -1;
        if (id >= 0) {
            boolean kept = Db.get(this).finishTrack(id);
            lastFinished = kept ? id : -1;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (trackId >= 0) stopRecording();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Registrazione tracce", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private static String label(String v) {
        if ("scooter".equals(v)) return "🛵 Scooter";
        if ("bici".equals(v)) return "🚲 Bici";
        return "🚗 Auto";
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, LocationService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "Ferma e salva", piStop).build();
        Intent pr = new Intent(this, LocationService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent piPr = PendingIntent.getService(this, 2, pr,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action prAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), paused ? "Riprendi" : "Pausa", piPr).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle((paused ? "In pausa · " : "Registrazione in corso · ") + label(vehicle))
                .addAction(prAction)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piOpen)
                .addAction(stopAction)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification() {
        long now = System.currentTimeMillis();
        long sec = (now - startTime - pausedTotal - (paused ? now - pauseStart : 0)) / 1000;
        String txt = String.format(Locale.ITALY, "%d:%02d · %.2f km · %d punti GPS",
                sec / 60, sec % 60, distance / 1000.0, count);
        if (paused) txt = "In pausa · " + txt + " · tocca Riprendi per continuare";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(txt));
    }
}
