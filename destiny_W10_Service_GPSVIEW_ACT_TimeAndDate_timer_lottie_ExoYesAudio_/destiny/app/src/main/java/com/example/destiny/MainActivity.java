package com.example.destiny;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
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

import com.airbnb.lottie.LottieAnimationView;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.TimeoutError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String OWM_API_KEY = "a40a9237704b1b486feb03c7cc60c32d";
    private static final String VOLLEY_TAG = "WeatherRequest";

    private TextView tvWeatherInfo, tvWeatherStatus;
    private ListView lvForecast;
    private Button btnSwitchToSecond;
    private LottieAnimationView lottieWeatherAnimation;

    private FusedLocationProviderClient fusedLocationClient;
    private RequestQueue requestQueue;
    private ActivityResultLauncher<String> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化 UI 元件
        tvWeatherInfo = findViewById(R.id.tv_weather_info);
        tvWeatherStatus = findViewById(R.id.tv_weather_status);
        lvForecast = findViewById(R.id.lv_forecast);
        btnSwitchToSecond = findViewById(R.id.btn_switch_to_second);
        lottieWeatherAnimation = findViewById(R.id.lottie_weather_animation);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        requestQueue = Volley.newRequestQueue(this);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        getCurrentLocationAndWeather();
                    } else {
                        Toast.makeText(this, "需要位置權限才能獲取天氣資訊", Toast.LENGTH_LONG).show();
                    }
                });

        checkAndRequestLocationPermission();

        btnSwitchToSecond.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SecondActivity.class);
            startActivity(intent);
        });
    }

    /**
     * *** 核心修改 1：此方法現在會同時請求「目前天氣」和「未來預報」兩個 API ***
     */
    private void fetchWeatherData(Location location) {
        // --- 請求 1：獲取目前天氣 ---
        String currentWeatherUrl = String.format(Locale.US,
                "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&units=metric&lang=zh_tw",
                location.getLatitude(), location.getLongitude(), OWM_API_KEY);

        Log.d(TAG, "Requesting Current Weather URL: " + currentWeatherUrl);

        JsonObjectRequest currentWeatherRequest = new JsonObjectRequest(Request.Method.GET, currentWeatherUrl, null,
                this::parseWeatherResponse, // 成功後呼叫 parseWeatherResponse 處理
                this::handleVolleyError); // 失敗後呼叫統一的錯誤處理方法

        currentWeatherRequest.setTag(VOLLEY_TAG);
        requestQueue.add(currentWeatherRequest);


        // --- 請求 2：獲取未來 5 天 / 每 3 小時的天氣預報 ---
        String forecastUrl = String.format(Locale.US,
                "https://api.openweathermap.org/data/2.5/forecast?lat=%f&lon=%f&appid=%s&units=metric&lang=zh_tw",
                location.getLatitude(), location.getLongitude(), OWM_API_KEY);

        Log.d(TAG, "Requesting Forecast URL: " + forecastUrl);

        JsonObjectRequest forecastRequest = new JsonObjectRequest(Request.Method.GET, forecastUrl, null,
                this::parseForecastResponse, // 成功後呼叫新的 parseForecastResponse 處理
                this::handleVolleyError); // 失敗後也呼叫統一的錯誤處理方法

        forecastRequest.setTag(VOLLEY_TAG);
        requestQueue.add(forecastRequest);
    }

    /**
     * *** 核心修改 2：此方法現在只專注於解析「目前天氣」並更新頂部 UI ***
     */
    private void parseWeatherResponse(JSONObject response) {
        try {
            JSONArray weatherArray = response.getJSONArray("weather");
            String weatherStatus = "";
            if (weatherArray.length() > 0) {
                weatherStatus = weatherArray.getJSONObject(0).getString("description");
            }

            JSONObject main = response.getJSONObject("main");
            double temp = main.getDouble("temp");
            int humidity = main.getInt("humidity");

            String locationName = response.getString("name");

            String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            String infoText = String.format(Locale.getDefault(),
                    "%s, %s\n氣溫: %.1f°C, 濕度: %d%%",
                    locationName, currentTime, temp, humidity);

            tvWeatherInfo.setText(infoText);
            tvWeatherStatus.setText(weatherStatus);

            // 根據目前天氣更換動畫
            switchAnimationByWeather(weatherStatus);

        } catch (JSONException e) {
            Log.e(TAG, "Current Weather JSON Parsing Error: ", e);
            tvWeatherInfo.setText("目前天氣資料解析錯誤");
        }
    }

    /**
     * *** 核心修改 3：新增方法，專門用來解析「未來預報」並更新下方的 ListView ***
     */
    private void parseForecastResponse(JSONObject response) {
        ArrayList<String> forecastListItems = new ArrayList<>();
        try {
            JSONArray list = response.getJSONArray("list");

            // OpenWeatherMap 的免費預報 API 最多提供 40 筆資料 (5天 * 8筆/天)
            // 我們可以只顯示未來的幾筆，例如 5 筆
            int forecastCount = Math.min(list.length(), 5);

            for (int i = 0; i < forecastCount; i++) {
                JSONObject forecast = list.getJSONObject(i);

                // 獲取時間戳 (dt_txt 格式為 "2024-05-23 12:00:00")
                String timeTxt = forecast.getString("dt_txt");
                String formattedTime = formatDateTime(timeTxt); // 轉換為 "23日 12:00"

                // 獲取溫度
                JSONObject main = forecast.getJSONObject("main");
                double temp = main.getDouble("temp");

                // 獲取天氣描述
                JSONArray weatherArray = forecast.getJSONArray("weather");
                String description = weatherArray.getJSONObject(0).getString("description");

                // 組合成一行字串
                String forecastEntry = String.format(Locale.getDefault(),
                        "%s: %s, %.1f°C",
                        formattedTime, description, temp);
                forecastListItems.add(forecastEntry);
            }

            // 更新 ListView
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, forecastListItems);
            lvForecast.setAdapter(adapter);

        } catch (JSONException e) {
            Log.e(TAG, "Forecast JSON Parsing Error: ", e);
            // 如果解析失敗，也給使用者一個提示
            forecastListItems.clear();
            forecastListItems.add("天氣預報解析失敗");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, forecastListItems);
            lvForecast.setAdapter(adapter);
        }
    }

    /**
     * *** 核心修改 4：將錯誤處理邏輯抽出來，成為一個獨立的方法 ***
     */
    private void handleVolleyError(Exception error) {
        String errorMessage = "天氣資訊獲取失敗";
        if (error instanceof NoConnectionError) {
            errorMessage = "網路連線失敗，請檢查您的網路";
        } else if (error instanceof TimeoutError) {
            errorMessage = "連線逾時，請稍後再試";
        } else {
            NetworkResponse response = (error instanceof com.android.volley.VolleyError) ? ((com.android.volley.VolleyError) error).networkResponse : null;
            if (response != null && response.data != null) {
                String errorData = new String(response.data);
                Log.e(TAG, "Volley Error Data: " + errorData);
                try {
                    JSONObject errorJson = new JSONObject(errorData);
                    errorMessage = "API 錯誤: " + errorJson.optString("message", "未知錯誤");
                } catch (JSONException e) {
                    errorMessage = "無法解析的 API 錯誤";
                }
                Log.e(TAG, "Volley Status Code: " + response.statusCode);
            } else {
                errorMessage = "未知的網路錯誤";
            }
        }
        Log.e(TAG, "Final Error Message: " + errorMessage, error);
        tvWeatherInfo.setText(errorMessage); // 將錯誤訊息顯示在主資訊區
    }


    // --- 以下方法保持不變 ---

    private void switchAnimationByWeather(String weatherStatus) {
        if (weatherStatus == null || weatherStatus.isEmpty()) {
            lottieWeatherAnimation.setAnimation(R.raw.weather_animation);
            lottieWeatherAnimation.playAnimation();
            return;
        }

        if (weatherStatus.contains("雨") || weatherStatus.contains("雷")) {
            lottieWeatherAnimation.setAnimation(R.raw.rainy_animation);
        } else if (weatherStatus.contains("晴")) {
            lottieWeatherAnimation.setAnimation(R.raw.sunny_animation);
        } else if (weatherStatus.contains("雲") || weatherStatus.contains("陰")) {
            lottieWeatherAnimation.setAnimation(R.raw.cloudy_animation);
        } else {
            lottieWeatherAnimation.setAnimation(R.raw.weather_animation);
        }
        lottieWeatherAnimation.playAnimation();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (requestQueue != null) {
            requestQueue.cancelAll(VOLLEY_TAG);
        }
    }

    private void checkAndRequestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocationAndWeather();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void getCurrentLocationAndWeather() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        fetchWeatherData(location);
                    } else {
                        tvWeatherInfo.setText("無法獲取目前位置");
                        Toast.makeText(this, "請開啟GPS或檢查位置設定", Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * 這個方法現在對解析預報時間至關重要
     */
    private String formatDateTime(String rawDateTime) {
        // 輸入格式: "2024-05-23 12:00:00"
        // 輸出格式: "23日 12:00"
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            SimpleDateFormat outputFormat = new SimpleDateFormat("dd日 HH:mm", Locale.getDefault());
            Date date = inputFormat.parse(rawDateTime);
            return outputFormat.format(date);
        } catch (ParseException e) {
            Log.e(TAG, "Failed to parse date: " + rawDateTime, e);
            // 如果解析失敗，嘗試只返回時間部分
            String[] parts = rawDateTime.split(" ");
            if (parts.length > 1) {
                return parts[1]; // "12:00:00"
            }
            return rawDateTime;
        }
    }
}
