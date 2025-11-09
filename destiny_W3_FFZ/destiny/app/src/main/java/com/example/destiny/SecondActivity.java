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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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
    private MyLocationNewOverlay myLocationOverlay;
    private Polyline pathPolyline;
    private final List<GeoPoint> pathPoints = new ArrayList<>();

    // *** 核心修改：新增 isPaused 狀態來區分「暫停」和「完全停止」 ***
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
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_second);

        initViews();
        setupMap();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        initPermissionLauncher();
        setupListeners();
        fetchInitialLocation();
    }

    private void initViews() {
        tvHeaderInfo = findViewById(R.id.tv_header_info);
        tvMainDistance = findViewById(R.id.tv_main_distance);
        tvMainTimer = findViewById(R.id.tv_main_timer);
        btnStartStop = findViewById(R.id.btn_start_stop);
        btnNextStep = findViewById(R.id.btn_next_step);
        mapView = findViewById(R.id.mapView);
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);

        pathPolyline = new Polyline();
        pathPolyline.setColor(Color.BLUE);
        pathPolyline.setWidth(10f);
        mapView.getOverlays().add(pathPolyline);

        myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mapView);
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.setDrawAccuracyEnabled(true);
        mapView.getOverlays().add(myLocationOverlay);
    }

    private void updateMap(Location location) {
        if (mapView == null) return;
        GeoPoint currentGeoPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        pathPoints.add(currentGeoPoint);
        pathPolyline.setPoints(pathPoints);

        if (isTracking) {
            mapView.getController().animateTo(currentGeoPoint);
        }
        mapView.invalidate();
    }

    @SuppressLint("MissingPermission")
    private void fetchInitialLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            updateLocationNameFromCoords(location);
                            if (mapView != null) {
                                GeoPoint initialGeoPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
                                mapView.getController().setCenter(initialGeoPoint);
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

    /**
     * *** 核心修改：將 handleStartStopClick 的邏輯完全重寫 ***
     */
    private void handleStartStopClick() {
        if (!isTracking) {
            // 情況 1: 從未開始或已完全重置 -> 檢查權限並開始全新的一次跑步
            isPaused = false;
            checkPermissionsAndStart();
        } else if (isTracking && !isPaused) {
            // 情況 2: 正在跑步中 -> 暫停
            pauseTracking();
            handleConsecutiveClicks(); // 連續點擊解鎖功能依然保留
        } else if (isTracking && isPaused) {
            // 情況 3: 已暫停 -> 繼續
            resumeTracking();
        }
    }

    /**
     * *** 新增：暫停跑步的方法 ***
     */
    private void pauseTracking() {
        isPaused = true;
        btnStartStop.setText("繼續");
        // 停止 Service，讓它保存當前狀態
        stopService(new Intent(this, RunningService.class));
    }

    /**
     * *** 新增：繼續跑步的方法 ***
     */
    private void resumeTracking() {
        isPaused = false;
        btnStartStop.setText("暫停");
        // 重新啟動 Service，並傳遞當前數據，讓 Service 從此基礎上繼續
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

    /**
     * *** 修改：將 toggleTracking 簡化為只處理「全新開始」的邏輯 ***
     */
    private void startNewRun() {
        isTracking = true;
        isPaused = false; // 確保不是暫停狀態
        btnStartStop.setText("暫停");

        // 清空舊的軌跡並重置UI
        pathPoints.clear();
        if (pathPolyline != null) {
            pathPolyline.setPoints(pathPoints);
        }
        mapView.invalidate();
        resetUIForNewRun();

        // 啟動服務
        Intent serviceIntent = new Intent(this, RunningService.class);
        serviceIntent.putExtra("INITIAL_LOCATION_NAME", currentLocationName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void checkPermissionsAndStart() {
        String[] requiredPermissions = {Manifest.permission.ACCESS_FINE_LOCATION};
        boolean allPermissionsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (allPermissionsGranted) {
            startNewRun(); // 權限OK，開始全新的一次跑步
        } else {
            permissionRequest.launch(requiredPermissions);
        }
    }

    private void setupListeners() {
        btnStartStop.setOnClickListener(v -> handleStartStopClick());
        btnNextStep.setOnClickListener(v -> {
            Intent intent = new Intent(SecondActivity.this, ThirdActivity.class);
            intent.putExtra("RUN_DISTANCE", totalDistance);
            intent.putExtra("RUN_TIME", elapsedTimeMillis);
            startActivity(intent);

            // *** 核心修改：跳轉後，停止 Service 並重置第二頁的狀態 ***
            if (isTracking) {
                stopService(new Intent(this, RunningService.class));
            }
            resetActivityState();
        });
    }

    /**
     * *** 新增：一個完整重置 Activity 狀態的方法 ***
     * 用於跳轉到第三頁後，或未來可能需要的地方
     */
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
        updateRunningUI(); // 用0值更新UI
        btnStartStop.setText("開始");
        btnNextStep.setEnabled(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(runningUpdateReceiver, new IntentFilter(RunningService.ACTION_BROADCAST));
        startClock();

        // *** 核心修改：如果不是在暫停狀態，且不在追蹤中，就確保UI是重置的 ***
        // 這能解決從第三頁返回時，UI還顯示舊數據的問題
        if (!isTracking && !isPaused) {
            resetActivityState();
        }
    }


    // --- 以下為其他未變動或僅有微小變動的方法 ---

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        // 如果是在追蹤中離開畫面(例如按Home鍵)，則不取消註冊Receiver，讓背景資料能更新
        if (!isTracking) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(runningUpdateReceiver);
        }
        stopClock();
    }


    private void resetUIForNewRun() {
        totalDistance = 0f;
        elapsedTimeMillis = 0;
        updateRunningUI();
        btnNextStep.setEnabled(false);
    }

    private void initPermissionLauncher() {
        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    Boolean fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                    if (fineLocationGranted != null && fineLocationGranted) {
                        fetchInitialLocation();
                        startNewRun(); // 獲取權限後直接開始
                    } else {
                        Toast.makeText(this, "需要定位權限才能使用地圖和跑步功能", Toast.LENGTH_LONG).show();
                    }
                });
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
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(hours);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes);
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

    private void unlockNextButton(String reason) {
        if (!btnNextStep.isEnabled()) {
            btnNextStep.setEnabled(true);
            Toast.makeText(this, "已解鎖下一步 (" + reason + ")", Toast.LENGTH_SHORT).show();
        }
    }
}
