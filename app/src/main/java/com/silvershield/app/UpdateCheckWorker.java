package com.silvershield.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * 自动检查 Worker：
 *  - 启动 App 时立即检查一次（OneTime）；
 *  - 之后每日在联网时检查一次（Periodic）。
 *
 * 检查两类更新：
 *  1) 规则更新：写入本地并广播 ACTION_RULES_UPDATED，无障碍服务收到后重新加载；
 *  2) 应用新版本：后台静默下载升级包，存为待安装，打开 App 时提示一键安装。
 */
public class UpdateCheckWorker extends Worker {

    private static final String TAG = "SilverShieldUpdate";
    private static final String UNIQUE = "SilverShieldDailyUpdate";

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 1) 规则更新
        UpdateManager um = new UpdateManager(getApplicationContext());
        int n = um.checkAndUpdate();
        if (n > 0) {
            Log.i(TAG, "规则已更新，新增/覆盖 " + n + " 条");
            Intent i = new Intent(UpdateManager.ACTION_RULES_UPDATED);
            getApplicationContext().sendBroadcast(i);
        } else if (n == 0) {
            Log.i(TAG, "规则已是最新");
        } else {
            Log.w(TAG, "本次规则更新失败（网络或解析异常），下次重试");
        }

        // 2) 应用新版本：静默检查并预下载，打开 App 时提示安装
        try {
            AppUpdater.UpdateInfo info = AppUpdater.fetchUpdateInfo();
            if (AppUpdater.hasNewer(getApplicationContext(), info)) {
                File apk = AppUpdater.getPendingApk(getApplicationContext());
                if (apk == null || !apk.getName().contains(info.versionName)) {
                    apk = AppUpdater.downloadApk(getApplicationContext(), info);
                }
                if (apk != null) {
                    AppUpdater.savePending(getApplicationContext(), info, apk);
                    Log.i(TAG, "发现新版本 " + info.versionName + "，已后台下载，待用户确认安装");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "应用版本检查失败", e);
        }
        return Result.success();
    }

    /** 在 Application/MainActivity 启动时调用：立即查一次 + 每日定时。 */
    public static void schedule(Context context) {
        WorkManager wm = WorkManager.getInstance(context);

        // 立即检查一次（唯一名 + KEEP：打开 App 多次也不叠加任务，
        // 此前未去重导致两个下载任务并发写同一个 APK 文件，安装时文件损坏报 ENOENT）
        wm.enqueueUniqueWork("SilverShieldOnceCheck",
                androidx.work.ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(UpdateCheckWorker.class).build());

        // 每日定时（最小间隔 15 分钟是 WorkManager 限制，设为 1 天）
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build();
        wm.enqueueUniquePeriodicWork(UNIQUE, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, periodic);
    }
}
