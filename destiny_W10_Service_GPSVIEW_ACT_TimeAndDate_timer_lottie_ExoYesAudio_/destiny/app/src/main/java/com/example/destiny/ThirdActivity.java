package com.example.destiny;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ThirdActivity extends AppCompatActivity {

    // SharedPreferences 的檔案名稱和 Key
    private static final String PREFS_NAME = "RunningHistoryPrefs";
    private static final String KEY_HISTORY_SET = "history_set";

    // UI 元件
    private TextView tvLatestResult;
    private ListView listViewHistory;
    private Button btnRestart, btnFinish, btnClearHistory;

    // ListView 相關
    private ArrayList<String> historyList;
    private ArrayAdapter<String> historyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_third);

        // 找到 UI 元件
        tvLatestResult = findViewById(R.id.tv_latest_result);
        listViewHistory = findViewById(R.id.listView_history);
        btnRestart = findViewById(R.id.btn_restart);
        btnFinish = findViewById(R.id.btn_finish);
        btnClearHistory = findViewById(R.id.btn_clear_history);

        // --- 核心邏輯 ---
        handleNewRecord();
        loadAndDisplayHistory();
        setupListeners();
    }

    /**
     * 處理從 SecondActivity 傳來的新紀錄
     */
    private void handleNewRecord() {
        Intent intent = getIntent();
        // 檢查 Intent 是否包含有效的跑步數據 (時間大於0)
        if (intent != null && intent.hasExtra("RUN_TIME") && intent.getLongExtra("RUN_TIME", 0L) > 0) {
            float finalDistance = intent.getFloatExtra("RUN_DISTANCE", 0f);
            long finalTimeMillis = intent.getLongExtra("RUN_TIME", 0L);

            // 格式化本次成績的字串
            String formattedTime = formatDuration(finalTimeMillis);
            String latestResultString = String.format(Locale.getDefault(), "最新成績：%.0f 公尺 / %s", finalDistance, formattedTime);
            tvLatestResult.setText(latestResultString);

            // 儲存這筆新紀錄
            saveRecord(finalDistance, finalTimeMillis);
        } else {
            // 如果沒有新紀錄，則不顯示最新成績
            tvLatestResult.setText("沒有新的跑步紀錄");
        }
    }

    /**
     * 載入 SharedPreferences 中的歷史紀錄並顯示在 ListView 上
     */
    private void loadAndDisplayHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> historySet = prefs.getStringSet(KEY_HISTORY_SET, new HashSet<>());

        // 將 Set 轉換為 List 並排序 (讓最新的在最上面)
        historyList = new ArrayList<>(historySet);
        Collections.sort(historyList, Collections.reverseOrder());

        // 設定 Adapter
        historyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, historyList);
        listViewHistory.setAdapter(historyAdapter);
    }

    /**
     * 設定所有按鈕的監聽事件
     */
    private void setupListeners() {
        // "返回第一頁" 按鈕
        btnRestart.setOnClickListener(v -> {
            Intent restartIntent = new Intent(ThirdActivity.this, MainActivity.class);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(restartIntent);
            finish();
        });

        // "結束應用程式" 按鈕
        btnFinish.setOnClickListener(v -> finishAffinity());

        // "清除紀錄" 按鈕
        btnClearHistory.setOnClickListener(v -> {
            // 顯示一個對話框，再次確認是否要清除
            new AlertDialog.Builder(this)
                    .setTitle("確認清除")
                    .setMessage("您確定要清除所有歷史紀錄嗎？此操作無法復原。")
                    .setPositiveButton("確定", (dialog, which) -> {
                        clearHistory();
                    })
                    .setNegativeButton("取消", null) // 取消則不做任何事
                    .show();
        });
    }

    /**
     * 將一筆新紀錄儲存到 SharedPreferences
     * @param distance 距離
     * @param timeMillis 時間 (毫秒)
     */
    private void saveRecord(float distance, long timeMillis) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // 讀取現有的紀錄
        Set<String> historySet = new HashSet<>(prefs.getStringSet(KEY_HISTORY_SET, new HashSet<>()));

        // 建立這筆新紀錄的字串，包含日期和時間戳以方便排序
        String currentDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String newRecord = String.format(Locale.getDefault(),
                "%s\n距離: %.0f 公尺, 時間: %s",
                currentDate, distance, formatDuration(timeMillis));

        // 加入新紀錄
        historySet.add(newRecord);

        // 存回 SharedPreferences
        editor.putStringSet(KEY_HISTORY_SET, historySet);
        editor.apply(); // 使用 apply() 在背景執行，更高效
    }

    /**
     * 清除所有儲存的紀錄
     */
    private void clearHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // 清除儲存的 Set
        editor.remove(KEY_HISTORY_SET);
        editor.apply();

        // 清空畫面上的 ListView
        if (historyList != null && historyAdapter != null) {
            historyList.clear();
            historyAdapter.notifyDataSetChanged();
        }

        Toast.makeText(this, "歷史紀錄已清除", Toast.LENGTH_SHORT).show();
    }

    /**
     * 格式化時間的方法
     */
    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(hours);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes);
        long hundredths = (millis / 10) % 100;

        return String.format(Locale.getDefault(), "%02d:%02d:%02d.%02d", hours, minutes, seconds, hundredths);
    }
}
