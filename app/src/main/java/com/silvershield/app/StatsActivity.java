package com.silvershield.app;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Map;

/**
 * 守护统计页（v1.4.3）：
 *  - 文案全面改为「守护」；
 *  - ★ 实时刷新：页面可见期间每 1 秒自动重读统计并重绘，
 *    新的拦截立刻出现在排行与最近记录里（离开页面即停止刷新，不耗电）；
 *  - 今日 / 累计守护次数与按应用排行（比例条可视化），并可一键分享给子女/家人。
 */
public class StatsActivity extends AppCompatActivity {

    private TextView tvToday;
    private TextView tvTotal;
    private LinearLayout listContainer;
    private Button btnShare;
    private StatsManager sm;

    /** 最近一次渲染的排行数据（分享时引用，实时刷新后自动更新）。 */
    private List<Map.Entry<String, Integer>> top;
    private int today = 0;
    private int total = 0;

    /** 时间格式化器（v1.5.11 提成字段，避免每秒刷新时重复 new）。 */
    private final java.text.SimpleDateFormat fmt =
            new java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.CHINA);

    /** 实时刷新：页面可见时每 1 秒重绘一次。 */
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            renderStats();
            refreshHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        tvToday = findViewById(R.id.tvToday);
        tvTotal = findViewById(R.id.tvTotal);
        listContainer = findViewById(R.id.listContainer);
        btnShare = findViewById(R.id.btnShare);
        // v1.5.6：「清空」从底部挪到标题右侧
        TextView btnClear = findViewById(R.id.btnClear);

        sm = new StatsManager(this);

        // 清空统计（带确认，供用户清除误计/虚增数据）
        btnClear.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空守护记录")
                .setMessage("确定清空全部守护统计吗？此操作不可恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    sm.clearAll();
                    renderStats();
                })
                .setNegativeButton("取消", null)
                .show());

        // 分享：引用最新一次渲染的数据
        btnShare.setOnClickListener(v -> {
            if (top == null || top.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : top) {
                sb.append("· ").append(appLabel(e.getKey())).append("：").append(e.getValue()).append(" 次\n");
            }
            String text = "【银盾】今日帮长辈跳过广告/弹窗 " + today + " 次，累计守护 " + total + " 次。\n"
                    + "应用排行：\n" + sb;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, getString(R.string.stats_share_title)));
        });

        renderStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.post(refreshRunnable); // 立即刷新一次，并开始每秒实时刷新
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable); // 离开页面即停，不后台耗电
    }

    /** 读取最新统计并整页重绘（1 秒一次）。v1.5.11：整页只读取一次，内存里算各项指标。 */
    private void renderStats() {
        // v1.5.11：原先 todayTotal/total/topApps/recent 各自读盘解析，每秒 4 次 IO；
        // 现在只调一次 listAll()，后续运算全在内存完成，UI 线程零重复读盘。
        List<org.json.JSONObject> all = sm.listAll();
        today = sm.todayTotal(all);
        total = sm.total(all);
        tvToday.setText(String.valueOf(today));
        tvTotal.setText(String.valueOf(total));
        listContainer.removeAllViews();

        top = sm.topApps(all, 10);
        if (top.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.stats_empty));
            empty.setTextSize(16);
            empty.setTextColor(getResources().getColor(android.R.color.darker_gray));
            listContainer.addView(empty);
            btnShare.setEnabled(false);
            return;
        }
        btnShare.setEnabled(true);

        int max = 1;
        for (Map.Entry<String, Integer> e : top) {
            max = Math.max(max, e.getValue());
        }
        for (Map.Entry<String, Integer> e : top) {
            listContainer.addView(buildRow(e.getKey(), e.getValue(), max));
        }

        // 最近守护记录（按时间倒序，最多 20 条）
        List<org.json.JSONObject> recent = sm.recent(all, 20);
        if (!recent.isEmpty()) {
            TextView recentTitle = new TextView(this);
            recentTitle.setText("最近守护记录");
            recentTitle.setTextSize(20);
            recentTitle.setTextColor(getResources().getColor(R.color.text_main));
            recentTitle.setPadding(0, 36, 0, 12);
            listContainer.addView(recentTitle);

            for (org.json.JSONObject e : recent) {
                // v1.4.0：按行为分类显示（老记录无 kind 字段，默认显示“跳过广告”）
                String action = "block".equals(e.optString("kind")) ? "关闭广告" : "跳过广告";
                TextView row = new TextView(this);
                row.setText(fmt.format(new java.util.Date(e.optLong("ts", 0)))
                        + " · " + appLabel(e.optString("pkg", ""))
                        + " · " + action + "成功");
                row.setTextSize(17);
                row.setTextColor(getResources().getColor(android.R.color.black));
                row.setPadding(0, 0, 0, 14);
                listContainer.addView(row);
            }
        }
    }

    /** 包名转中文应用名（如 com.miui.packageinstaller → 安装器）；查不到时回退原包名。 */
    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label = pm.getApplicationLabel(ai).toString();
            if (label != null && !label.trim().isEmpty()) {
                return label.trim();
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }

    /** 构建单行：文案 + 比例条（按 max 归一）。 */
    private View buildRow(String pkg, int count, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, 16);

        TextView label = new TextView(this);
        label.setText(appLabel(pkg) + "：" + count + " 次");
        label.setTextSize(16);
        label.setTextColor(getResources().getColor(android.R.color.black));
        row.addView(label);

        // 轨道
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setWeightSum(max);
        track.setBackgroundColor(getResources().getColor(R.color.gray_light));
        ViewGroup.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
        track.setLayoutParams(trackLp);

        // 进度条
        View bar = new View(this);
        bar.setBackgroundColor(getResources().getColor(R.color.purple_500));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, count);
        bar.setLayoutParams(barLp);
        track.addView(bar);

        row.addView(track);
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
