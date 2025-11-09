package com.example.destiny;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class SecondActivity extends AppCompatActivity {

    private static final String TAG = "SecondActivity";

    // UI 元件
    private TextView tvHeaderInfo, tvMainDistance, tvMainTimer;
    private Button btnStartStop, btnNextStep;
    private MapView mapView;
    private LottieAnimationView lottieAnimationView;

    private ExoPlayer pageEnterPlayer;

    // 地圖與路徑相關
    private MyLocationNewOverlay myLocationOverlay;
    private Polyline pathPolyline;
    private final List<GeoPoint> pathPoints = new ArrayList<>();

    // 狀態變數 (以下為您原有的程式碼)
    private boolean isTracking = false;
    private boolean isPaused = false;
    private float totalDistance = 0f;
    private long elapsedTimeMillis = 0;
    private String currentLocationName = "獲取中...";

    private int consecutiveClickCount = 0;
    private long lastClickTime = 0;
    private static final int CLICK_THRESHOLD_MS = 1500;
    private static final int CLICK_COUNT_TO_UNLOCK = 5;

    private static final float TARGET_DISTANCE_METERS = 100f;
    private static final int TARGET_TIME_SECONDS = 10;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> permissionRequest;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    private final BroadcastReceiver runningUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(RunningService.ACTION_BROADCAST)) {
                totalDistance = intent.getFloatExtra(RunningService.EXTRA_DISTANCE, 0);
                elapsedTimeMillis = intent.getLongExtra(RunningService.EXTRA_ELAPSED_TIME, 0);
                currentLocationName = intent.getStringExtra(RunningService.EXTRA_LOCATION_NAME);

                if (intent.hasExtra(RunningService.EXTRA_LOCATION)) {
                    Location location = intent.getParcelableExtra(RunningService.EXTRA_LOCATION);
                    if (location != null) {
                        updateMap(location);
                    }
                }
                updateRunningUI();
                checkUnlockConditions();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);

        // 步驟 1: 先初始化地圖設定
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        // 步驟 2: 初始化 UI 元件並設定地圖
        initViews();
        setupMap(); // 這一步會建立 myLocationOverlay，讓定位圖層出現

        // 步驟 3: 在地圖和UI都準備好後，再初始化並播放音效
        // (1) 設定音訊屬性
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                .setUsage(C.USAGE_ASSISTANCE_SONIFICATION)
                .build();

        // (2) 建立播放器
        pageEnterPlayer = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttrs, false) // 確保是 false 來避免閃退
                .build();

        // (3) 指向 raw/unlock.mp3 並播放
        Uri unlockUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.unlock);
        MediaItem item = MediaItem.fromUri(unlockUri);
        pageEnterPlayer.setMediaItem(item);
        pageEnterPlayer.prepare();
        pageEnterPlayer.play();

        // 步驟 4: 執行剩下的初始化
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        initPermissionLauncher();
        setupListeners();
        fetchInitialLocation();
    }

    @Override
    protected void onDestroy() {
        if (pageEnterPlayer != null) {
            pageEnterPlayer.release();
            pageEnterPlayer = null;
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(runningUpdateReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver not registered or already unregistered.", e);
        }
        super.onDestroy();
    }

    /**
     * *** 核心修改：在解鎖按鈕時，也播放音效 ***
     */
    private void unlockNextButton(String reason) {
        if (!btnNextStep.isEnabled()) {
            btnNextStep.setEnabled(true);
            Toast.makeText(this, "已解鎖下一步 (" + reason + ")", Toast.LENGTH_SHORT).show();

            // 使用我們已經建立好的播放器來播放音效
            if (pageEnterPlayer != null) {
                // 將播放進度跳到開頭，這樣每次都能從頭播放
                pageEnterPlayer.seekTo(0);
                // 開始播放
                pageEnterPlayer.play();
            }
        }
    }

    // --- 以下為您所有的既有方法，完全不變 ---

    private void updateMap(Location location) {
        if (mapView == null) return;
        GeoPoint currentGeoPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        pathPoints.add(currentGeoPoint);
        pathPolyline.setPoints(pathPoints);
        mapView.invalidate();
    }

    private void initViews() {
        tvHeaderInfo = findViewById(R.id.tv_header_info);
        tvMainDistance = findViewById(R.id.tv_main_distance);
        tvMainTimer = findViewById(R.id.tv_main_timer);
        btnStartStop = findViewById(R.id.btn_start_stop);
        btnNextStep = findViewById(R.id.btn_next_step);
        mapView = findViewById(R.id.mapView);
        lottieAnimationView = findViewById(R.id.lottie_animation_view);
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        pathPolyline = new Polyline();
        pathPolyline.setColor(Color.BLUE);
        pathPolyline.setWidth(10f);
        mapView.getOverlays().add(pathPolyline);

        myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mapView);
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.enableFollowLocation();
        mapView.getOverlays().add(myLocationOverlay);
    }

    private void setupListeners() {
        btnStartStop.setOnClickListener(v -> handleStartStopClick());
        btnNextStep.setOnClickListener(v -> {
            Intent intent = new Intent(SecondActivity.this, ThirdActivity.class);
            intent.putExtra("RUN_DISTANCE", totalDistance);
            intent.putExtra("RUN_TIME", elapsedTimeMillis);
            startActivity(intent);

            if (isTracking) {
                stopService(new Intent(this, RunningService.class));
            }
            resetActivityState();
        });
    }

    private void handleStartStopClick() {
        if (!isTracking) {
            isPaused = false;
            checkPermissionsAndStart();
        } else if (!isPaused) {
            pauseTracking();
            handleConsecutiveClicks();
        } else {
            resumeTracking();
        }
    }

    private void pauseTracking() {
        isPaused = true;
        btnStartStop.setText("繼續");
        stopService(new Intent(this, RunningService.class));
        lottieAnimationView.pauseAnimation();
        lottieAnimationView.setVisibility(View.INVISIBLE);
        if (myLocationOverlay != null) {
            myLocationOverlay.enableFollowLocation();
        }
    }

    private void resumeTracking() {
        isPaused = false;
        btnStartStop.setText("暫停");
        lottieAnimationView.setVisibility(View.VISIBLE);
        lottieAnimationView.resumeAnimation();

        if (myLocationOverlay != null) {
            myLocationOverlay.disableFollowLocation();
        }

        Intent serviceIntent = new Intent(this, RunningService.class);
        serviceIntent.putExtra("RESUME_DISTANCE", totalDistance);
        serviceIntent.putExtra("RESUME_TIME", elapsedTimeMillis);
        serviceIntent.putExtra("INITIAL_LOCATION_NAME", currentLocationName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void startNewRun() {
        isTracking = true;
        isPaused = false;
        btnStartStop.setText("暫停");
        lottieAnimationView.setVisibility(View.VISIBLE);
        lottieAnimationView.playAnimation();

        if (myLocationOverlay != null) {
            myLocationOverlay.disableFollowLocation();
        }

        pathPoints.clear();
        if (pathPolyline != null) {
            pathPolyline.setPoints(pathPoints);
        }
        mapView.invalidate();
        resetUIForNewRun();

        Intent serviceIntent = new Intent(this, RunningService.class);
        serviceIntent.putExtra("INITIAL_LOCATION_NAME", currentLocationName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void resetActivityState() {
        isTracking = false;
        isPaused = false;
        totalDistance = 0f;
        elapsedTimeMillis = 0;

        pathPoints.clear();
        if (pathPolyline != null) {
            pathPolyline.setPoints(pathPoints);
        }
        mapView.invalidate();
        updateRunningUI();
        btnStartStop.setText("開始");
        btnNextStep.setEnabled(false);
        lottieAnimationView.cancelAnimation();
        lottieAnimationView.setVisibility(View.INVISIBLE);

        if (myLocationOverlay != null) {
            myLocationOverlay.enableFollowLocation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(runningUpdateReceiver, new IntentFilter(RunningService.ACTION_BROADCAST));
        startClock();
        if (!isTracking && !isPaused) {
            resetActivityState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        if (!isTracking) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(runningUpdateReceiver);
        }
        stopClock();
    }

    @SuppressLint("MissingPermission")
    private void fetchInitialLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            updateLocationNameFromCoords(location);
                            if (mapView != null) {
                                mapView.getController().setZoom(17.0);
                            }
                        } else {
                            currentLocationName = "無法獲取位置";
                            updateHeaderInfo();
                        }
                    });
        } else {
            currentLocationName = "沒有定位權限";
            updateHeaderInfo();
        }
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startNewRun();
        } else {
            permissionRequest.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    private void initPermissionLauncher() {
        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
                        fetchInitialLocation();
                    } else {
                        Toast.makeText(this, "需要定位權限才能使用地圖和跑步功能", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void resetUIForNewRun() {
        totalDistance = 0f;
        elapsedTimeMillis = 0;
        updateRunningUI();
        btnNextStep.setEnabled(false);
    }

    private void updateHeaderInfo() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String currentTime = sdf.format(new Date());
        String headerText = currentTime + " " + currentLocationName;
        tvHeaderInfo.setText(headerText);
    }

    private void updateRunningUI() {
        tvMainDistance.setText(String.format(Locale.getDefault(), "%.0f", totalDistance));
        tvMainTimer.setText(formatDuration(elapsedTimeMillis));
    }

    private void startClock() {
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                updateHeaderInfo();
                clockHandler.postDelayed(this, 1000);
            }
        };
        clockHandler.post(clockRunnable);
    }

    private void stopClock() {
        if (clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }
    }

    private void updateLocationNameFromCoords(Location location) {
        new Thread(() -> {
            Geocoder geocoder = new Geocoder(this, Locale.TAIWAN);
            try {
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String city = address.getAdminArea();
                    String district = address.getLocality();
                    StringBuilder nameBuilder = new StringBuilder();
                    if (city != null) nameBuilder.append(city);
                    if (district != null) nameBuilder.append(" ").append(district);
                    currentLocationName = nameBuilder.length() > 0 ? nameBuilder.toString() : "未知區域";
                } else {
                    currentLocationName = "無法解析地名";
                }
            } catch (IOException e) {
                Log.e(TAG, "Geocoder failed", e);
                currentLocationName = "位置服務錯誤";
            }
            runOnUiThread(this::updateHeaderInfo);
        }).start();
    }

    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        long hundredths = (millis / 10) % 100;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d.%02d", hours, minutes, seconds, hundredths);
    }

    private void checkUnlockConditions() {
        if (!btnNextStep.isEnabled()) {
            if (totalDistance >= TARGET_DISTANCE_METERS) {
                unlockNextButton("跑步距離達標");
            } else if (TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis) >= TARGET_TIME_SECONDS) {
                unlockNextButton("跑步時間達標");
            }
        }
    }

    private void handleConsecutiveClicks() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < CLICK_THRESHOLD_MS) {
            consecutiveClickCount++;
        } else {
            consecutiveClickCount = 1;
        }
        lastClickTime = currentTime;
        if (consecutiveClickCount >= CLICK_COUNT_TO_UNLOCK) {
            unlockNextButton("連續點擊解鎖");
        }
    }
}
