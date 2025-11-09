package com.example.destiny;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    // UI 元件
    private Button btnSwitchToSecond;
    private TextView tvWeatherInfo;
    private TextView tvWeatherStatus;
    private ListView lvForecast;

    // Volley & API 相關
    private final String apiKey = "a40a9237704b1b486feb03c7cc60c32d";
    private RequestQueue queue;

    // 定位相關
    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionRequest;

    // 暫存天氣資料
    private String currentCityName;
    private String currentDescription;
    private Double currentTemp;
    private Integer currentRainPercentage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化網路請求隊列
        queue = Volley.newRequestQueue(this);

        // 初始化定位服務客戶端
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // 找到 UI 元件
        btnSwitchToSecond = findViewById(R.id.btn_switch_to_second);
        tvWeatherInfo = findViewById(R.id.tv_weather_info);
        tvWeatherStatus = findViewById(R.id.tv_weather_status);
        lvForecast = findViewById(R.id.lv_forecast);

        // 註冊權限請求的回呼 (非常重要)
        registerPermissionLauncher();

        // 設定切換到第二頁的按鈕
        btnSwitchToSecond.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SecondActivity.class);
            startActivity(intent);
        });

        // App 一啟動就開始定位流程
        requestLocationPermission();
    }

    private void registerPermissionLauncher() {
        locationPermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    // 檢查精確定位權限是否被授予
                    if (Boolean.TRUE.equals(permissions.get(Manifest.permission.ACCESS_FINE_LOCATION))) {
                        // 如果使用者同意了權限，就開始取得位置
                        Toast.makeText(this, "權限已授予，正在取得位置...", Toast.LENGTH_SHORT).show();
                        getCurrentLocationAndFetchWeather();
                    } else {
                        // 如果使用者拒絕，給予提示並載入預設地區(例如台北)的天氣
                        Toast.makeText(this, "定位權限被拒絕，將顯示台北市天氣", Toast.LENGTH_LONG).show();
                        fetchWeatherDataByCity("Taipei");
                    }
                }
        );
    }

    private void requestLocationPermission() {
        // 檢查 App 是否已經擁有精確定位的權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // 如果已經有權限，就直接取得位置並查詢天氣
            getCurrentLocationAndFetchWeather();
        } else {
            // 如果沒有權限，就啟動權限請求對話框
            locationPermissionRequest.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void getCurrentLocationAndFetchWeather() {
        // 安全性檢查：再次確認權限 (這是呼叫 Location API 的標準做法)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        // 成功取得位置，使用經緯度查詢天氣
                        fetchWeatherDataByCoords(location.getLatitude(), location.getLongitude());
                    } else {
                        // 雖然有權限，但系統暫時無法取得位置 (例如剛開機或在室內)
                        Toast.makeText(this, "暫時無法取得位置，將顯示台北市天氣", Toast.LENGTH_LONG).show();
                        fetchWeatherDataByCity("Taipei");
                    }
                });
    }

    // --- 天氣查詢邏輯 (與之前版本相同，但現在被定位功能呼叫) ---

    // 方法 A: 透過城市名稱取得天氣 (備用)
    private void fetchWeatherDataByCity(String city) {
        String weatherUrl = "https://api.openweathermap.org/data/2.5/weather?q=" + city + "&appid=" + apiKey + "&units=metric&lang=zh_tw";
        String forecastUrl = "https://api.openweathermap.org/data/2.5/forecast?q=" + city + "&appid=" + apiKey + "&units=metric&lang=zh_tw";
        executeWeatherRequests(weatherUrl, forecastUrl);
    }

    // 方法 B: 透過經緯度取得天氣 (主要)
    private void fetchWeatherDataByCoords(double lat, double lon) {
        String weatherUrl = "https://api.openweathermap.org/data/2.5/weather?lat=" + lat + "&lon=" + lon + "&appid=" + apiKey + "&units=metric&lang=zh_tw";
        String forecastUrl = "https://api.openweathermap.org/data/2.5/forecast?lat=" + lat + "&lon=" + lon + "&appid=" + apiKey + "&units=metric&lang=zh_tw";
        executeWeatherRequests(weatherUrl, forecastUrl);
    }

    // 執行 API 請求的共用方法
    private void executeWeatherRequests(String weatherUrl, String forecastUrl) {
        clearWeatherDataUI(); // 請求前先清空舊資料

        // 請求目前天氣
        JsonObjectRequest weatherRequest = new JsonObjectRequest(Request.Method.GET, weatherUrl, null,
                response -> {
                    try {
                        currentDescription = response.getJSONArray("weather").getJSONObject(0).getString("description");
                        currentTemp = response.getJSONObject("main").getDouble("temp");
                        currentCityName = response.getString("name");
                        updateMainWeatherLabel();
                    } catch (JSONException e) {
                        handleApiError("解析目前天氣失敗", e);
                    }
                }, error -> handleApiError("無法取得目前天氣", error));

        // 請求預報
        JsonObjectRequest forecastRequest = new JsonObjectRequest(Request.Method.GET, forecastUrl, null,
                response -> {
                    try {
                        JSONArray list = response.getJSONArray("list");
                        if (list.length() > 0) {
                            currentRainPercentage = (int) (list.getJSONObject(0).getDouble("pop") * 100);
                            updateMainWeatherLabel();
                            processForecastList(list);
                        }
                    } catch (JSONException e) {
                        handleApiError("解析預報資料失敗", e);
                    }
                }, error -> handleApiError("無法取得預報資料", error));

        queue.add(weatherRequest);
        queue.add(forecastRequest);
    }

    // --- UI 更新 & 輔助方法 ---

    private void processForecastList(JSONArray list) throws JSONException {
        ArrayList<String> forecastDisplayList = new ArrayList<>();
        int forecastCount = Math.min(list.length(), 5);
        for (int i = 0; i < forecastCount; i++) {
            JSONObject forecast = list.getJSONObject(i);
            int hoursLater = (i + 1) * 3;
            String time = formatTime(forecast.getLong("dt"));
            String description = forecast.getJSONArray("weather").getJSONObject(0).getString("description");
            String formattedTemp = String.format("%.0f°C", forecast.getJSONObject("main").getDouble("temp"));
            int rainPercentage = (int) (forecast.getDouble("pop") * 100);
            String weatherType = getWeatherType(description, rainPercentage);

            String forecastInfo = hoursLater + " 小時後 (" + time + ") | " + weatherType + " | " + formattedTemp + " | " + rainPercentage + "%";
            forecastDisplayList.add(forecastInfo);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, forecastDisplayList);
        lvForecast.setAdapter(adapter);
    }

    private void updateMainWeatherLabel() {
        if (currentCityName != null && currentDescription != null && currentTemp != null && currentRainPercentage != null) {
            // *** 核心修改：在這裡加入日期格式化 ***
            Date now = new Date();
            String currentDate = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(now);
            String currentTime = new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(now);

            String infoString = currentCityName + " : " + currentDate + " " + currentTime + " | " + String.format("%.1f", currentTemp) + "°C | " + currentRainPercentage + "%";
            tvWeatherInfo.setText(infoString);
            tvWeatherStatus.setText(currentDescription);
        }
    }

    private void clearWeatherDataUI() {
        currentCityName = null;
        currentDescription = null;
        currentTemp = null;
        currentRainPercentage = null;
        tvWeatherInfo.setText("正在取得天氣資訊...");
        tvWeatherStatus.setText("-");
        lvForecast.setAdapter(null);
    }

    private void handleApiError(String message, Exception error) {
        tvWeatherInfo.setText(message);
        Log.e("WeatherApp", message, error);
    }

    private String getWeatherType(String description, int rainPercentage) {
        if (description.contains("雨") || rainPercentage > 40) return "雨天";
        if (description.contains("雲") || description.contains("陰")) return "陰天";
        return "晴天";
    }

    private String formatTime(long unixSeconds) {
        Date date = new Date(unixSeconds * 1000L);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.TAIWAN);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        return sdf.format(date);
    }
}
