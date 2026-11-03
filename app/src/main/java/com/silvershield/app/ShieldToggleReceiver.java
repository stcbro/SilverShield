package com.silvershield.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知栏开关接收器：点击通知上的「暂停/恢复守护」按钮时触发，
 * 翻转守护开关并刷新通知与首页界面。
 */
public class ShieldToggleReceiver extends BroadcastReceiver {

    public static final String ACTION_TOGGLE = "com.silvershield.app.ACTION_TOGGLE_SHIELD";
    /** 通知栏开关后，用于刷新首页 UI 的本地广播。 */
    public static final String ACTION_REFRESH_UI = "com.silvershield.app.ACTION_REFRESH_UI";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_TOGGLE.equals(intent.getAction())) {
            return;
        }
        boolean nowOn;
        if (SkipAccessibilityService.instance != null) {
            // 直接通过服务切换（会同步持久化 + 刷新通知）
            nowOn = !SkipAccessibilityService.paused;
            SkipAccessibilityService.instance.setPaused(!nowOn);
        } else {
            // 服务未运行：仅翻转本地记录（下次服务启动会读取）
            android.content.SharedPreferences sp = context
                    .getSharedPreferences("silvershield", Context.MODE_PRIVATE);
            boolean wasOn = sp.getBoolean("switch_on", true);
            nowOn = !wasOn;
            sp.edit().putBoolean("switch_on", nowOn).apply();
            SkipAccessibilityService.paused = !nowOn;
        }
        // 通知首页刷新（若前台）
        Intent ui = new Intent(ACTION_REFRESH_UI);
        context.sendBroadcast(ui);
    }
}
