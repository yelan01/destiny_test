package com.example.destiny;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.Locale;

public class RunningService extends Service {

    private static final String TAG = "RunningService";
    private static final String NOTIFICATION_CHANNEL_ID = "RunningChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final float DISTANCE_NOISE_THRESHOLD = 2.0f;

    public static final String ACTION_BROADCAST = RunningService.class.getName() + "Broadcast";
    public static final String EXTRA_DISTANCE = "extra_distance";
    public static final String EXTRA_ELAPSED_TIME = "extra_elapsed_time";
    public static final String EXTRA_LOCATION_NAME = "extra_location_name";
    public static final String EXTRA_LOCATION = "extra_location";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location previousLocation = null;
    private Location latestLocation = null;

    private float totalDistance = 0f;
    private long startTime = 0;
    // *** 核心修改：新增變數來儲存從暫停中恢復的時間 ***
    private long timeOffset = 0;
    private String currentLocationName = "獲取中...";

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private static final int TIMER_UPDATE_INTERVAL_MS = 50;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        RunningService getService() {
            return RunningService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        createLocationCallback();
        Log.d(TAG, "Service onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Running Service Started");

        // *** 核心修改：判斷是「全新開始」還是「從暫停中繼續」 ***
        if (intent != null) {
            // 如果是從暫停中繼續
            if (intent.hasExtra("RESUME_TIME")) {
                totalDistance = intent.getFloatExtra("RESUME_DISTANCE", 0f);
                timeOffset = intent.getLongExtra("RESUME_TIME", 0L); // 載入已跑的時間
                currentLocationName = intent.getStringExtra("INITIAL_LOCATION_NAME");
                Log.d(TAG, "Resuming run. Distance: " + totalDistance + ", Time Offset: " + timeOffset);
            }
            // 如果是全新開始 (或者是從暫停繼續，也需要更新地名)
            else if (intent.hasExtra("INITIAL_LOCATION_NAME")) {
                totalDistance = 0f;
                timeOffset = 0L; // 全新開始，時間偏移為0
                currentLocationName = intent.getStringExtra("INITIAL_LOCATION_NAME");
            }
        }

        startTime = System.currentTimeMillis(); // 無論如何，都重置計時的起點
        startForegroundService();
        startLocationUpdates();
        startTimer();

        return START_STICKY;
    }

    private void startTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                broadcastUpdate();
                timerHandler.postDelayed(this, TIMER_UPDATE_INTERVAL_MS);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void broadcastUpdate() {
        // *** 核心修改：計算總時間時，要加上之前已跑的時間偏移量 ***
        long now = System.currentTimeMillis();
        long elapsedTimeMillis = (now - startTime) + timeOffset; // 當前經過時間 + 暫停前的時間

        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtra(EXTRA_DISTANCE, totalDistance);
        intent.putExtra(EXTRA_ELAPSED_TIME, elapsedTimeMillis);
        intent.putExtra(EXTRA_LOCATION_NAME, currentLocationName);

        if (latestLocation != null) {
            intent.putExtra(EXTRA_LOCATION, latestLocation);
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTimer();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        Log.d(TAG, "Service onDestroy");
    }

    // --- 以下為未變動的方法 ---

    private void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, SecondActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("跑步追蹤中")
                .setContentText("距離: 0 公尺")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Running Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted.", e);
        }
    }

    private void createLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        processNewLocation(location);
                    }
                }
            }
        };
    }

    private void processNewLocation(Location newLocation) {
        latestLocation = newLocation;
        if (previousLocation != null) {
            float distance = previousLocation.distanceTo(newLocation);
            if (distance > DISTANCE_NOISE_THRESHOLD) {
                totalDistance += distance;
                previousLocation = newLocation;
                updateNotification();
            }
        } else {
            // 這是第一次獲取位置，特別是在「繼續」時
            previousLocation = newLocation;
        }
    }

    private void updateNotification() {
        String distanceText = String.format(Locale.getDefault(), "距離: %.0f 公尺", totalDistance);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Intent notificationIntent = new Intent(this, SecondActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("跑步追蹤中")
                .setContentText(distanceText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .build();

        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }
}
