package com.silvershield.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页（与首页同风格的白色大卡 + 彩色图标块，适老化大字）：
 *  - 开启无障碍服务（跳系统无障碍列表）；
 *  - 后台保活设置（跳应用详情页，开自启动 / 关电池优化）。
 *
 * 两个入口均直接跳转对应系统设置页，不再额外弹出引导浮层。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View rowEnable = findViewById(R.id.rowEnable);
        View rowKeepAlive = findViewById(R.id.rowKeepAlive);

        // 仅跳转系统「无障碍」设置页
        rowEnable.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                ToastUtil.showCenter(this, "请手动进入 设置 → 无障碍 开启本服务");
            }
        });

        // 仅跳转本应用详情页（自启动 / 电池优化）
        rowKeepAlive.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                ToastUtil.showCenter(this, "请手动进入 设置 → 应用管理 → 银盾 进行保活设置");
            }
        });
    }
}
