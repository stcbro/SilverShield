package com.silvershield.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 引导浮层（v1.4.9）：
 * Toast 在跳转到系统「无障碍」页后，会被页面加载/转场提前顶掉，
 * 显示时长不稳定（后台保活跳的应用详情页加载快，所以显得正常）。
 * 改用透明、不挡触摸的浮层 Activity 承载引导文字：
 *  - 显示在目标设置页之上，位置屏幕中部偏下，20sp 白字深色圆角底；
 *  - 不挡触摸：FLAG_NOT_TOUCHABLE 让点击穿透直达下层设置页；
 *  - 固定显示 2 秒后自动消失，两个设置入口时长完全一致。
 *
 * 关键：窗口必须「可聚焦」，不能加 FLAG_NOT_FOCUSABLE。本浮层通过
 * FLAG_ACTIVITY_NEW_TASK 以独立任务叠在系统设置页之上；若窗口不可聚焦，
 * 系统不会把该任务带到前台，浮层会静默留在后台而不显示（表现为点按不弹）。
 */
public class GuideOverlayActivity extends Activity {

    /** 固定显示时长：两个引导入口一致，2 秒。 */
    private static final long SHOW_MS = 2000L;

    public static final String EXTRA_MSG = "msg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 仅触摸穿透，下层系统设置页照常可操作；窗口保持可聚焦，确保浮层能前台显示
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        TextView tv = new TextView(this);
        String msg = getIntent() != null ? getIntent().getStringExtra(EXTRA_MSG) : null;
        tv.setText("🛡️ " + (msg == null ? "" : msg));
        tv.setTextSize(20);
        tv.setLineSpacing(dp(3), 1.15f);
        tv.setTextColor(0xFFFFFFFF);
        int pad = dp(18);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackgroundResource(R.drawable.bg_toast);

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.topMargin = dp(144);  // 屏幕中部略偏下
        lp.leftMargin = dp(56);  // 收窄背景：左右留白，不再撑满整行
        lp.rightMargin = dp(56);
        root.addView(tv, lp);
        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(this::finish, SHOW_MS);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
