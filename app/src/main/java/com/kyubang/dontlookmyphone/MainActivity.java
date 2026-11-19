package com.kyubang.dontlookmyphone;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends ComponentActivity {

    private static final String PREFS = "privacy_guard";
    private static final String KEY_UNTIL = "armed_until";
    private static final String KEY_MINUTES = "minutes";
    private static final String KEY_EVENTS = "events";
    private static final int CAMERA_REQUEST = 41;
    private static final int CAPTURE_PERMISSION_REQUEST = 43;
    private static final int OWNER_REQUEST = 42;

    private static final int BG = Color.rgb(13, 14, 23);
    private static final int SURFACE = Color.rgb(29, 31, 45);
    private static final int PINK = Color.rgb(255, 99, 158);
    private static final int MUTED = Color.rgb(173, 176, 195);
    private static final int WHITE = Color.WHITE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean captureResolved = false;
    private String activeEventId;
    private int pendingMinutes = 30;
    private boolean authenticating = false;
    private String ownerAction = "";
    private Screen screen = Screen.HOME;

    private enum Screen { HOME, CAPTURE, HISTORY }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (getIntent().getBooleanExtra("capture_from_detected", false)) {
            startCapture();
        } else if (isArmed() && !getIntent().getBooleanExtra("owner_panel", false)) {
            startCapture();
        } else {
            showHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null && screen == Screen.HOME) showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("capture_from_detected", false)) {
            startCapture();
        } else if (intent.getBooleanExtra("owner_panel", false)) {
            showHome();
        }
    }

    static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private void openUsageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception ex) {
            Toast.makeText(this, "설정에서 앱 사용정보 접근 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(this, "설정에서 다른 앱 위에 표시 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isArmed() {
        long until = prefs.getLong(KEY_UNTIL, 0);
        if (until > 0 && System.currentTimeMillis() >= until) {
            prefs.edit().remove(KEY_UNTIL).apply();
            return false;
        }
        return until > System.currentTimeMillis();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String title, int bgColor, int fgColor) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(title);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(fgColor);
        view.setBackground(shape(bgColor, 17));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    private LinearLayout column(int pad) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        return view;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        parent.addView(child, lp);
    }

    private LinearLayout basePage() {
        screen = Screen.HOME;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout body = column(24);
        body.setPadding(dp(24), dp(38), dp(24), dp(40));
        scroll.addView(body);
        setContentView(scroll);
        return body;
    }

    private void showHome() {
        LinearLayout root = basePage();
        boolean active = isArmed();

        TextView eyebrow = text("🔒  PRIVACY MODE", 14, PINK, true);
        add(root, eyebrow, 0);
        add(root, text("내 폰을\n보지 마.", 43, WHITE, true), 12);
        add(root, text("누군가 앱을 열면 실행 시각을 기록하고\n전면 카메라 촬영 후 경고 화면을 띄워요.", 15, MUTED, false), 12);

        LinearLayout status = column(20);
        status.setBackground(shape(SURFACE, 22));
        add(status, text(active ? "●  보호 모드 실행 중" : "○  보호 모드 꺼짐",
                18, active ? PINK : WHITE, true), 0);
        if (active) {
            long until = prefs.getLong(KEY_UNTIL, 0);
            String time = new SimpleDateFormat("MM월 dd일 HH:mm", Locale.KOREA).format(new Date(until));
            add(status, text(time + "까지 앱 열기 감지", 14, MUTED, false), 10);
        } else {
            add(status, text("원하는 시간을 선택하고 보호를 시작하세요.", 14, MUTED, false), 10);
        }
        add(root, status, 28);

        LinearLayout settings = column(20);
        settings.setBackground(shape(SURFACE, 22));
        add(settings, text("보호 지속 시간 (분)", 17, WHITE, true), 0);
        add(settings, text("앱 사용정보 접근과 다른 앱 위에 표시를 허용하면 카톡 등 다른 앱 실행도 감지해요.", 14, MUTED, false), 12);
        Button usage = button((hasUsageAccess(this) ? "✓ " : "① ") + "앱 사용정보 접근 " +
                (hasUsageAccess(this) ? "허용됨" : "권한 설정"), SURFACE, WHITE);
        usage.setOnClickListener(v -> openUsageSettings());
        add(settings, usage, 14);
        Button overlayPermission = button((Settings.canDrawOverlays(this) ? "✓ " : "② ") +
                "다른 앱 위에 표시 " + (Settings.canDrawOverlays(this) ? "허용됨" : "권한 설정"),
                SURFACE, WHITE);
        overlayPermission.setOnClickListener(v -> openOverlaySettings());
        add(settings, overlayPermission, 10);
        EditText minutesInput = new EditText(this);
        minutesInput.setSingleLine(true);
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesInput.setText(String.valueOf(prefs.getInt(KEY_MINUTES, 30)));
        minutesInput.setSelectAllOnFocus(true);
        minutesInput.setTextColor(WHITE);
        minutesInput.setTextSize(27);
        minutesInput.setHintTextColor(MUTED);
        minutesInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PINK));
        add(settings, minutesInput, 10);
        add(settings, text("1분 ~ 1,440분 · 설정 시간이 끝나면 자동 해제", 13, MUTED, false), 8);
        Button arm = button(active ? "보호 시간 새로 설정" : "보호 시작 →", PINK, BG);
        arm.setOnClickListener(v -> {
            String raw = minutesInput.getText().toString().trim();
            int minutes;
            try {
                minutes = Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                Toast.makeText(this, "숫자로 시간을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (minutes < 1 || minutes > 1440) {
                Toast.makeText(this, "1~1440분 사이로 설정해 주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!hasUsageAccess(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("앱 사용정보 접근 권한 필요")
                        .setMessage("이 권한을 허용해야 카카오톡 등 다른 앱을 열었는지 감지할 수 있어요. afk를 찾아 권한을 켜 주세요.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("권한 설정", (d, w) -> openUsageSettings())
                        .show();
                return;
            }
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("다른 앱 위에 표시 권한 필요")
                        .setMessage("카톡 화면 위에 2분 경고를 띄우려면 afk의 다른 앱 위에 표시를 허용해야 해요.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("권한 설정", (d, w) -> openOverlaySettings())
                        .show();
                return;
            }
            pendingMinutes = minutes;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_REQUEST);
            } else {
                armNow(minutes);
            }
        });
        add(settings, arm, 22);
        add(root, settings, 16);

        Button history = button("📷  접근 기록 및 사진 보기", SURFACE, WHITE);
        history.setOnClickListener(v -> requestOwnerAccess("history"));
        add(root, history, 20);

        if (active) {
            Button stop = button("보호 모드 종료", Color.rgb(62, 37, 53), PINK);
            stop.setOnClickListener(v -> requestOwnerAccess("stop"));
            add(root, stop, 10);
        }

        add(root, text("다른 앱 실행을 감지하면 셀카 카메라가 바로 열립니다.\n카메라 화면에 촬영 예고가 표시되고 약 3초 뒤 사진이 자동으로 저장돼요.",
                13, MUTED, false), 26);
    }

    private void armNow(int minutes) {
        long until = System.currentTimeMillis() + minutes * 60_000L;
        prefs.edit()
                .putLong(KEY_UNTIL, until)
                .putInt(KEY_MINUTES, minutes)
                .apply();
        try {
            Intent monitor = new Intent(this, GuardianService.class);
            ContextCompat.startForegroundService(this, monitor);
            Toast.makeText(this, minutes + "분 동안 모든 앱을 감지해요.", Toast.LENGTH_LONG).show();
            finishAndRemoveTask();
        } catch (Exception ex) {
            prefs.edit().remove(KEY_UNTIL).apply();
            Toast.makeText(this, "보호 시작 실패: 알림/앱 권한과 배터리 설정을 확인해 주세요.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAPTURE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCapture();
            } else {
                resolveCapture(false, "", "카메라 권한 거부");
            }
            return;
        }
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                armNow(pendingMinutes);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("사진 촬영 권한이 없어요")
                        .setMessage("권한 없이 계속하면 다른 앱 실행 시각만 기록되며 사진은 저장되지 않습니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("사진 없이 시작", (d, w) -> armNow(pendingMinutes))
                        .show();
            }
        }
    }

    private void startCapture() {
        screen = Screen.CAPTURE;
        captureResolved = false;
        if (getIntent().getBooleanExtra("capture_from_detected", false)) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(713);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);

        long triggeredAt = System.currentTimeMillis();
        String eventFromWarning = getIntent().getStringExtra("capture_event_id");
        activeEventId = eventFromWarning == null || eventFromWarning.isEmpty()
                ? UUID.randomUUID().toString() : eventFromWarning;
        if (eventFromWarning == null || eventFromWarning.isEmpty()) {
            addEvent(activeEventId, triggeredAt);
        }

        LinearLayout page = basePage();
        screen = Screen.CAPTURE;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        add(page, text("●  실행 감지됨", 15, PINK, true), 0);
        add(page, text("잠깐! 📸", 38, WHITE, true), 14);
        add(page, text("전면 카메라 촬영 안내 📸\n3초 뒤 자동으로 촬영하고 이 기기에 저장해요.",
                16, MUTED, false), 13);

        PreviewView previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setBackground(shape(SURFACE, 20));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(350));
        previewLp.topMargin = dp(20);
        page.addView(previewView, previewLp);
        add(page, text("🔴  전면 카메라 촬영 준비 중 · 뒤로가기로 나갈 수 있어요", 13, PINK, false), 16);

        TextView countdown = text("카메라 준비 중…", 23, PINK, true);
        countdown.setGravity(Gravity.CENTER);
        add(page, countdown, 16);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            getIntent().putExtra("capture_event_id", activeEventId);
            requestPermissions(new String[] { Manifest.permission.CAMERA }, CAPTURE_PERMISSION_REQUEST);
            return;
        }

        try {
            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                if (captureResolved || screen != Screen.CAPTURE || isFinishing()) return;
                try {
                    cameraProvider = future.get();
                    cameraProvider.unbindAll();
                    if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        resolveCapture(false, "", "전면 카메라 없음");
                        return;
                    }
                    Preview preview = new Preview.Builder().build();
                    imageCapture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture);
                    // Visible 3-second countdown before a single automatic selfie.
                    for (int n = 3; n >= 1; n--) {
                        final int seconds = n;
                        handler.postDelayed(() -> {
                            if (!captureResolved && screen == Screen.CAPTURE && !isFinishing()) {
                                countdown.setText("📸  " + seconds + "초 뒤 자동 촬영");
                            }
                        }, (3 - n) * 1000L);
                    }
                    handler.postDelayed(() -> {
                        if (!captureResolved && screen == Screen.CAPTURE && !isFinishing()) {
                            countdown.setText("촬영 중…");
                            takePhoto();
                        }
                    }, 3100L);
                } catch (Exception error) {
                    resolveCapture(false, "", "카메라 시작 실패");
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception error) {
            resolveCapture(false, "", "카메라 접근 실패");
        }
    }

    private void takePhoto() {
        if (captureResolved || screen != Screen.CAPTURE || imageCapture == null) return;
        File dir = photoDirectory();
        if (!dir.exists() && !dir.mkdirs()) {
            resolveCapture(false, "", "저장 폴더 생성 실패");
            return;
        }
        File file = new File(dir, UUID.randomUUID().toString() + ".jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(file).build();
        try {
            imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                            resolveCapture(true, file.getName(), "사진 저장됨");
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            file.delete();
                            resolveCapture(false, "", "촬영 실패");
                        }
                    });
        } catch (Exception error) {
            file.delete();
            resolveCapture(false, "", "촬영 오류");
        }
    }

    private void resolveCapture(boolean saved, String filename, String message) {
        if (captureResolved) return;
        captureResolved = true;
        String app = getIntent().getStringExtra("capture_app_name");
        String status = app == null ? message : (app + " 실행 감지 · " + message);
        updateEvent(activeEventId, saved ? filename : "", status);
        handler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) cameraProvider.unbindAll();
        imageCapture = null;
        Toast.makeText(this, saved ? "셀카가 기기에 저장됐어요 📸" : status,
                Toast.LENGTH_LONG).show();
        finishAndRemoveTask();
    }

    private void requestOwnerAccess(String action) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isDeviceSecure()) {
            Intent confirmation = km.createConfirmDeviceCredentialIntent(
                    "보호자 확인", "기기 잠금 해제 정보로 본인 확인 후 계속합니다.");
            if (confirmation != null) {
                ownerAction = action;
                authenticating = true;
                startActivityForResult(confirmation, OWNER_REQUEST);
                return;
            }
        }
        performOwnerAction(action);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OWNER_REQUEST) {
            authenticating = false;
            if (resultCode == RESULT_OK) performOwnerAction(ownerAction);
            ownerAction = "";
        }
    }

    private void performOwnerAction(String action) {
        if ("history".equals(action)) {
            showHistory();
        } else if ("stop".equals(action)) {
            prefs.edit().remove(KEY_UNTIL).apply();
            stopService(new Intent(this, GuardianService.class));
            Toast.makeText(this, "보호를 종료했어요.", Toast.LENGTH_SHORT).show();
            showHome();
        }
    }

    private void showHistory() {
        LinearLayout page = basePage();
        screen = Screen.HISTORY;
        add(page, text("접근 기록", 33, WHITE, true), 0);
        add(page, text("실행 시간과 촬영 결과 · 저장 공간은 이 기기 내부", 14, MUTED, false), 10);

        Button back = button("← 돌아가기", SURFACE, WHITE);
        back.setOnClickListener(v -> showHome());
        add(page, back, 22);

        JSONArray events = readEvents();
        if (events.length() == 0) {
            add(page, text("아직 기록된 실행이 없어요. 🌙", 19, WHITE, true), 40);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA);
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            LinearLayout card = column(18);
            card.setBackground(shape(SURFACE, 20));
            long ts = event.optLong("time", 0);
            String photo = event.optString("photo", "");
            add(card, text(fmt.format(new Date(ts)), 17, WHITE, true), 0);
            add(card, text(event.optString("status", "실행 기록"), 14, MUTED, false), 6);
            if (!photo.isEmpty()) {
                File file = new File(photoDirectory(), photo);
                if (file.exists()) {
                    Button open = button("📷 사진 확인", Color.rgb(68, 39, 64), WHITE);
                    open.setOnClickListener(v -> openPhoto(file));
                    add(card, open, 12);
                }
            }
            add(page, card, 14);
        }

        if (events.length() > 0) {
            Button erase = button("모든 기록과 사진 삭제", Color.rgb(62, 37, 53), PINK);
            erase.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("전체 기록 삭제")
                    .setMessage("모든 접근 기록과 저장된 사진을 삭제합니다. 이 작업은 취소할 수 없어요.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (dialog, which) -> {
                        deleteAllEvents();
                        showHistory();
                    }).show());
            add(page, erase, 24);
        }
    }

    private void openPhoto(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        opts.inSampleSize = Math.max(1, Math.max(opts.outWidth, opts.outHeight) / 1200);
        opts.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bitmap == null) {
            Toast.makeText(this, "사진을 불러올 수 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setPadding(dp(10), dp(10), dp(10), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("저장된 전면 카메라 사진")
                .setView(image)
                .setPositiveButton("닫기", null)
                .show();
    }

    private File photoDirectory() {
        return new File(getFilesDir(), "photos");
    }

    private JSONArray readEvents() {
        try {
            return new JSONArray(prefs.getString(KEY_EVENTS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void addEvent(String id, long time) {
        try {
            JSONArray old = readEvents();
            JSONArray updated = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("time", time);
            e.put("photo", "");
            e.put("status", "촬영 준비 중");
            updated.put(e);
            for (int i = 0; i < old.length() && i < 99; i++) updated.put(old.get(i));
            prefs.edit().putString(KEY_EVENTS, updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void updateEvent(String id, String photo, String status) {
        JSONArray entries = readEvents();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject event = entries.optJSONObject(i);
            if (event != null && id.equals(event.optString("id"))) {
                try {
                    event.put("photo", photo);
                    event.put("status", status);
                } catch (Exception ignored) { }
                break;
            }
        }
        prefs.edit().putString(KEY_EVENTS, entries.toString()).apply();
    }

    private void deleteAllEvents() {
        File dir = photoDirectory();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        prefs.edit().remove(KEY_EVENTS).apply();
    }

    private void cancelPendingCapture() {
        if (captureResolved) return;
        captureResolved = true;
        handler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) cameraProvider.unbindAll();
        imageCapture = null;
        if (activeEventId != null) updateEvent(activeEventId, "", "셀카 촬영 취소");
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.CAPTURE) {
            cancelPendingCapture();
            finishAndRemoveTask();
            return;
        }
        if (screen == Screen.HISTORY) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (screen == Screen.CAPTURE && !captureResolved) {
            cancelPendingCapture();
            finish();
        }
        if (!authenticating && screen == Screen.HOME && isArmed() && !isChangingConfigurations()) {
            // The next launcher tap should start a fresh activity and log a fresh event.
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) cameraProvider.unbindAll();
        super.onDestroy();
    }
}
