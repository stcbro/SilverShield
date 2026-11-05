package com.silvershield.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 居中引导提示：
 * 系统默认 Toast 出现在屏幕最底部，老人容易看不到。
 * 这里改为显示在屏幕中部偏下的位置，字号加大到 20sp，
 * 深色圆角底 + 白字，醒目且与系统设置页形成对比。
 *
 * ★ v1.5.11 兼容修复：Android 12（API 31）起系统直接忽略自定义 Toast 视图（setView），
 *   原方案会「静默退化」为系统底部 Toast，老人看不到居中大字提示。
 *   改为优先用 WindowManager 浮层（TYPE_APPLICATION_OVERLAY）自绘居中提示，全版本可用；
 *   若未授予「悬浮窗」权限导致 addView 失败，则降级为标准 Toast，保证至少可见。
 *
 * 单例管理：连续触发时先取消上一条再显示新条，
 * 避免新旧提示排队叠加、旧提示语残留造成「提示语错乱」。
 */
public final class ToastUtil {

    private static View currentView;
    private static WindowManager currentWm;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    /** 居中提示显示时长（毫秒）。 */
    private static final long SHOW_MS = 3500L;

    private ToastUtil() {
    }

    /** 中部偏下大字提示；连续调用时旧提示立即消失。 */
    public static void showCenter(Context ctx, String msg) {
        cancel();
        Context appCtx = ctx.getApplicationContext();
        // 优先用 WindowManager 浮层：全版本（含 Android 12+）都保持居中大字观感。
        if (canOverlay(appCtx) && showOverlay(appCtx, msg)) {
            return;
        }
        // 悬浮窗不可用（未授权/异常）：降级为标准 Toast，保证至少有提示。
        try {
            Toast.makeText(appCtx, "🛡️ " + msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // 提示失败不影响主功能
        }
    }

    /** 是否已获得绘制悬浮窗权限。 */
    private static boolean canOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(ctx);
        }
        return true; // API < 23 仅需清单声明权限
    }

    /** 用 WindowManager 弹出居中浮层；成功返回 true，失败（含无权限）返回 false。 */
    private static boolean showOverlay(Context ctx, String msg) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return false;
            }
            TextView tv = new TextView(ctx);
            tv.setText("🛡️ " + msg);
            tv.setTextSize(20);
            tv.setLineSpacing(dp(ctx, 3), 1.15f);
            tv.setTextColor(Color.WHITE);
            int pad = dp(ctx, 18);
            tv.setPadding(pad, pad, pad, pad);
            tv.setBackgroundResource(R.drawable.bg_toast);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            lp.y = dp(ctx, 72); // 屏幕中部略偏下
            wm.addView(tv, lp);
            currentView = tv;
            currentWm = wm;
            handler.postDelayed(ToastUtil::cancel, SHOW_MS);
            return true;
        } catch (Throwable t) {
            // 无悬浮窗权限等：交回调用方降级为标准 Toast
            return false;
        }
    }

    /** 取消当前显示中的提示。 */
    public static void cancel() {
        handler.removeCallbacksAndMessages(null);
        if (currentView != null && currentWm != null) {
            try {
                currentWm.removeView(currentView);
            } catch (Throwable ignored) {
            }
        }
        currentView = null;
        currentWm = null;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
