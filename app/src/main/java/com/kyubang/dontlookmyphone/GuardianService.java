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
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * User-started, time-limited, visibly notified foreground service.
 * Only checks which app was brought to the foreground. Does not capture
 * the screen, read messages, or access the camera in the background.
 */
public class GuardianService extends Service {
    private static final String CHANNEL = "afk_guard_channel";
    private static final int NOTIFICATION_ID = 712;
    private static final String PREFS = "privacy_guard";
    private static final long WARNING_DURATION = 120_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsageStatsManager usage;
    private WindowManager windowManager;
    private SharedPreferences preferences;
    private View overlay;
    private long overlayUntil = 0L;
    private long lastScan;
    private String lastDetectedPackage = "";

    private final Runnable scanner = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long until = preferences.getLong("armed_until", 0);
            if (until <= now) {
                preferences.edit().remove("armed_until").apply();
                shutdown();
                return;
            }
            if (!hasPermissions()) {
                shutdown();
                return;
            }
            if (overlay != null && now >= overlayUntil) {
                removeOverlay();
            }
            scan(now);
            handler.postDelayed(this, 900L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        usage = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lastScan = System.currentTimeMillis();
        createChannel();
        Intent panel = new Intent(this, MainActivity.class);
        panel.putExtra("owner_panel", true);
        panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 718, panel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("afk · 보호 중")
                .setContentText("다른 앱 실행을 감지합니다. 눌러서 설정과 기록 확인")
                .setContentIntent(pending)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(scanner);
        lastScan = System.currentTimeMillis();
        handler.post(scanner);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private boolean hasPermissions() {
        return Settings.canDrawOverlays(this) && MainActivity.hasUsageAccess(this);
    }

    private void scan(long now) {
        if (usage == null) return;
        long from = Math.max(lastScan, now - 5000L);
        lastScan = now + 1L;
        UsageEvents events;
        try {
            events = usage.queryEvents(from, now);
        } catch (SecurityException ex) {
            return;
        }
        if (events == null) return;
        UsageEvents.Event event = new UsageEvents.Event();
        String newest = null;
        long newestAt = 0L;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
            String pkg = event.getPackageName();
            if (pkg == null || shouldIgnore(pkg)) continue;
            newest = pkg;
            newestAt = event.getTimeStamp();
        }
        if (newest == null) return;
        if (newest.equals(lastDetectedPackage)) return;
        lastDetectedPackage = newest;
        if (overlay != null) return;

        String label = appLabel(newest);
        saveEvent(label, newest, newestAt > 0 ? newestAt : now);
        showOverlay(label);
    }

    private boolean shouldIgnore(String pkg) {
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
        if ("com.sec.android.app.sbrowser".equals(pkg)) return "삼성 인터넷";
        if ("com.android.chrome".equals(pkg)) return "Chrome";
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception ex) {
            return pkg;
        }
    }

    private void saveEvent(String label, String pkg, long time) {
        try {
            JSONArray old;
            try {
                old = new JSONArray(preferences.getString("events", "[]"));
            } catch (Exception ex) {
                old = new JSONArray();
            }
            JSONObject item = new JSONObject();
            item.put("id", UUID.randomUUID().toString());
            item.put("time", time);
            item.put("photo", "");
            item.put("status", "다른 앱 실행 감지 · " + label + " (사진 촬영 없음)");
            item.put("package", pkg);
            JSONArray updated = new JSONArray();
            updated.put(item);
            for (int i = 0; i < old.length() && i < 99; i++) {
                updated.put(old.get(i));
            }
            preferences.edit().putString("events", updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String content, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(content);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private void showOverlay(String appName) {
        if (windowManager == null || !Settings.canDrawOverlays(this)) return;
        long startElapsed = android.os.SystemClock.elapsedRealtime();
        overlayUntil = System.currentTimeMillis() + WARNING_DURATION;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(22), dp(30), dp(22), dp(30));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(85, 21, 55), Color.rgb(15, 16, 27), Color.rgb(92, 29, 59)});
        panel.setBackground(background);
        TextView icon = label("⚠", 65, Color.rgb(255, 124, 176), true);
        panel.addView(icon);
        TextView title = label("내 폰을\n보지 마", 43, Color.WHITE, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(16);
        panel.addView(title, titleLp);
        TextView info = label(appName + " 실행이 기록됐습니다.\n이 휴대폰은 개인 소유입니다.", 16,
                Color.rgb(244, 198, 222), false);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.topMargin = dp(26);
        panel.addView(info, infoLp);
        TextView countdown = label("02:00", 42, Color.rgb(255, 124, 176), true);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-1, -2);
        clockLp.topMargin = dp(38);
        panel.addView(countdown, clockLp);
        TextView note = label("2분 뒤 자동으로 사라집니다.\n시스템 탐색 및 긴급 기능은 사용할 수 있습니다.", 12,
                Color.rgb(212, 184, 198), false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(18);
        panel.addView(note, noteLp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        try {
            windowManager.addView(panel, params);
            overlay = panel;
        } catch (Exception ex) {
            overlay = null;
            return;
        }

        Runnable countdownTask = new Runnable() {
            @Override public void run() {
                if (overlay != panel) return;
                long left = Math.max(0, WARNING_DURATION -
                        (android.os.SystemClock.elapsedRealtime() - startElapsed));
                long seconds = (left + 999L) / 1000L;
                countdown.setText(String.format(Locale.KOREA, "%02d:%02d",
                        seconds / 60L, seconds % 60L));
                if (left <= 0L) removeOverlay();
                else handler.postDelayed(this, 500L);
            }
        };
        countdownTask.run();
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try { windowManager.removeView(overlay); }
        catch (Exception ignored) { }
        overlay = null;
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "afk 보호 모드",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("다른 앱 실행 감지 동작 중");
        manager.createNotificationChannel(channel);
    }

    private void shutdown() {
        handler.removeCallbacks(scanner);
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
        super.onDestroy();
    }
}
