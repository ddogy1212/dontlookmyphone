package com.kyubang.dontlookmyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Foreground, user-enabled, time-limited local app-usage monitor.
 * When a new app is opened, attempts to show a VISIBLE front camera screen.
 * Android's background-activity-start restrictions might disallow this:
 * a tap-to-open notification is always available as fallback.
 * Never takes photos in the background.
 */
public class GuardianService extends Service {
    private static final String GUARD_CHANNEL = "afk_guard_channel";
    private static final String CAMERA_CHANNEL = "afk_camera_channel";
    private static final int GUARD_NOTIFICATION = 712;
    private static final int CAMERA_NOTIFICATION = 713;
    private static final String PREFS = "privacy_guard";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private UsageStatsManager usageStats;
    private NotificationManager notificationManager;
    private long lastScan;
    private long lastTrigger = 0;
    private String lastPackage = "";

    private final Runnable scanTask = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (prefs.getLong("armed_until", 0L) <= now) {
                prefs.edit().remove("armed_until").apply();
                stopSelf();
                return;
            }
            if (!MainActivity.hasUsageAccess(GuardianService.this)
                    || !Settings.canDrawOverlays(GuardianService.this)) {
                stopSelf();
                return;
            }
            scan(now);
            handler.postDelayed(this, 900L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        usageStats = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        lastScan = System.currentTimeMillis();

        notificationManager.createNotificationChannel(
                new NotificationChannel(GUARD_CHANNEL, "afk 보호 모드",
                        NotificationManager.IMPORTANCE_LOW));
        notificationManager.createNotificationChannel(
                new NotificationChannel(CAMERA_CHANNEL, "afk 카메라 바로가기",
                        NotificationManager.IMPORTANCE_DEFAULT));

        Intent panel = new Intent(this, MainActivity.class);
        panel.putExtra("owner_panel", true);
        panel.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPanel = PendingIntent.getActivity(this, 718, panel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification ongoing = new Notification.Builder(this, GUARD_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("afk · 보호 모드 실행 중")
                .setContentText("다른 앱 실행을 감지하면 셀카 화면을 열어요")
                .setContentIntent(openPanel)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(GUARD_NOTIFICATION, ongoing,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(GUARD_NOTIFICATION, ongoing);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(scanTask);
        lastScan = System.currentTimeMillis();
        handler.post(scanTask);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void scan(long now) {
        if (usageStats == null) return;
        UsageEvents events;
        try {
            events = usageStats.queryEvents(Math.max(lastScan, now - 5000L), now);
        } catch (SecurityException ex) {
            return;
        }
        lastScan = now + 1;
        if (events == null) return;

        UsageEvents.Event event = new UsageEvents.Event();
        String app = null;
        long eventTime = 0L;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
            String pkg = event.getPackageName();
            if (pkg == null) continue;
            // Home screen resets the same-app filter so reopening an app later is detected.
            // The afk camera itself must NOT reset it: that could cause a capture loop.
            if (pkg.contains("launcher")) {
                lastPackage = "";
                continue;
            }
            if (ignore(pkg)) continue;
            app = pkg;
            eventTime = event.getTimeStamp();
        }
        if (app == null) return;
        // Never retrigger a selfie from returning to the same app after capture.
        if (app.equals(lastPackage)) return;
        // Brief global cooldown for fast system transitions / notification bounce.
        if (now - lastTrigger < 5000L) return;
        lastPackage = app;
        lastTrigger = now;

        String label = appLabel(app);
        String id = recordEvent(app, label, eventTime > 0 ? eventTime : now);
        Intent capture = new Intent(this, MainActivity.class);
        capture.putExtra("capture_from_detected", true);
        capture.putExtra("capture_event_id", id);
        capture.putExtra("capture_app_name", label);
        capture.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Always publish a tappable fallback. Android may silently refuse a
        // background activity start, depending on version/manufacturer.
        PendingIntent openCamera = PendingIntent.getActivity(this, 719, capture,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification fallback = new Notification.Builder(this, CAMERA_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("afk · " + label + " 실행 감지")
                .setContentText("셀카 화면을 열려면 누르세요")
                .setContentIntent(openCamera)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        notificationManager.notify(CAMERA_NOTIFICATION, fallback);

        try {
            // Best effort: requires Android to permit a background activity start.
            startActivity(capture);
        } catch (Exception ignored) {
            // The notification above remains available for an explicit user tap.
        }
    }

    private boolean ignore(String pkg) {
        return pkg.equals(getPackageName())
                || pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.settings")
                || pkg.contains("launcher")
                || pkg.contains("inputmethod")
                || pkg.equals("com.samsung.android.honeyboard")
                || pkg.equals("com.google.android.permissioncontroller");
    }

    private String appLabel(String pkg) {
        if ("com.kakao.talk".equals(pkg)) return "카카오톡";
        if ("com.instagram.android".equals(pkg)) return "인스타그램";
        if ("com.google.android.youtube".equals(pkg)) return "유튜브";
        if ("com.android.chrome".equals(pkg)) return "Chrome";
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ex) {
            return pkg;
        }
    }

    private String recordEvent(String pkg, String label, long time) {
        String id = UUID.randomUUID().toString();
        try {
            JSONArray old;
            try {
                old = new JSONArray(prefs.getString("events", "[]"));
            } catch (Exception ex) {
                old = new JSONArray();
            }
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("time", time);
            item.put("photo", "");
            item.put("status", label + " 실행 감지 · 셀카 촬영 대기");
            item.put("package", pkg);
            JSONArray updated = new JSONArray();
            updated.put(item);
            for (int i = 0; i < old.length() && i < 99; i++) updated.put(old.get(i));
            prefs.edit().putString("events", updated.toString()).apply();
        } catch (Exception ignored) { }
        return id;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (notificationManager != null) notificationManager.cancel(CAMERA_NOTIFICATION);
        super.onDestroy();
    }
}
