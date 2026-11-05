package com.silvershield.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 首页（v1.4.3：Clash 风格改版，适老化）：
 *  - 顶部彩色状态大卡：守护中（绿）/ 已停止（灰），点击切换（纯显示开关，与真实服务独立）；
 *    卡内副文字实时自检拦截引擎真实状态（升级安装后系统会关闭无障碍，红字醒目提醒）；
 *  - 白卡入口「设置」+ 菜单行「守护统计 / 语音播报 / 使用说明」；
 *  - 使用说明改为点击弹出对话框，文案精简；
 *  - 全局文案：「拦截」统一改为「守护」；
 *  - 启动即调度规则自动检查/更新（WorkManager）；
 *  - 应用版本自动检测升级：发现新版本自动下载，提示一键安装；
 *  - 可选语音播报，用 TTS 朗读引导说明。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvStatusHint;
    private View statusCard;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean updateChecked = false;
    /** 状态卡显示开关（纯显示，与真实服务状态独立）：true=守护中，false=已停止。 */
    private boolean switchOn = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        statusCard = findViewById(R.id.statusCard);
        tvStatusHint = findViewById(R.id.tvStatusHint);

        // 显示当前版本号（自动取自 BuildConfig，升级时无需手动修改）
        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("版本 v" + BuildConfig.VERSION_NAME
                + "（规则 " + countRules() + " 条）");
        // 点击版本号可查看运行诊断详情（便于自行排查或截图留存）
        tvVersion.setOnClickListener(v -> showDiagnostics());

        // v1.4.1 起状态卡是纯显示开关（守护中↔已停止），与真实服务设置互不影响
        switchOn = getSharedPreferences("silvershield", MODE_PRIVATE)
                .getBoolean("switch_on", true);
        statusCard.setOnClickListener(v -> onStatusCardClick());
        findViewById(R.id.rowSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.rowStats).setOnClickListener(
                v -> startActivity(new Intent(this, StatsActivity.class)));
        findViewById(R.id.rowVoice).setOnClickListener(v -> speakGuide());
        findViewById(R.id.rowHelp).setOnClickListener(v -> showHelpDialog());

        // 启动即调度规则自动检查（立即一次 + 每日定时）
        UpdateCheckWorker.schedule(this);

        // 可选语音播报
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
                ttsReady = true;
            }
        });

        // 通知栏状态需要 POST_NOTIFICATIONS 权限（Android 13+），首次进首页申请一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            registerReceiver(refreshReceiver,
                    new IntentFilter(ShieldToggleReceiver.ACTION_REFRESH_UI));
        } catch (Throwable ignored) {
        }
        refreshSwitchCard();
        // v1.4.4：回到首页时把守护开关同步到服务（服务重启后也保持一致）
        if (SkipAccessibilityService.instance != null) {
            SkipAccessibilityService.instance.setPaused(!switchOn);
        } else {
            SkipAccessibilityService.paused = !switchOn;
        }
        // ★ v1.4.2：自检拦截引擎真实状态。
        //   安卓在覆盖安装（升级）本应用时会自动关闭已开启的无障碍服务——
        //   这正是"升级后突然不拦截"的原因。此处把真实状态亮出来。
        refreshEngineStatus();
        // 自动升级：先处理"授权后返回继续安装"，否则执行自动检测
        if (!continuePendingInstall()) {
            startAutoUpdateCheck();
        }
    }

    /**
     * 使用说明（v1.4.5）：点击弹出大字对话框，无关闭按钮——
     * 点弹窗内任意位置或弹窗外任意位置均可关闭。
     */
    private void showHelpDialog() {
        String text = "【怎么守护】开启无障碍后全自动：开屏广告自动点「跳过」，"
                + "弹窗广告自动点「关闭」，不用您动手。"
                + "点顶部状态卡可随时停止或恢复守护。\n\n"
                + "【防止失效】请在「设置」里允许银盾自启动、省电设为无限制；"
                + "升级安装后系统会关闭无障碍，首页会黄字提醒，重新开启即可。\n\n"
                + "【安全承诺】只点击广告自带的「跳过/关闭」按钮，"
                + "不破解、不屏蔽，不收集任何隐私。\n\n"
                + "【能力边界】手机出厂自带的锁屏广告无法处理，可向 12321 反映。";

        // 自定义整卡视图：标题 + 正文（含卡片背景），整卡可点击关闭
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card_white);
        int pad = dp(26);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("使用说明与重要提示");
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(19);
        body.setLineSpacing(dp(4), 1.1f);
        body.setTextColor(Color.parseColor("#1A1A1A"));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        body.setLayoutParams(lp);
        box.addView(body);

        // 点卡片内任意位置（含标题、空白处）即关闭
        View.OnClickListener dismiss = v -> {
            if (helpDialog != null) {
                helpDialog.dismiss();
            }
        };
        box.setOnClickListener(dismiss);
        scroll.addView(box);

        // 点弹窗外任意位置也可关闭（cancelable）
        helpDialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setCancelable(true)
                .show();
    }

    private AlertDialog helpDialog;

    /** 接收通知栏开关后的刷新广播，及时更新首页状态卡。 */
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switchOn = getSharedPreferences("silvershield", MODE_PRIVATE)
                    .getBoolean("switch_on", true);
            SkipAccessibilityService.paused = !switchOn;
            refreshSwitchCard();
            refreshEngineStatus();
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 自动检测应用新版本：发现后自动下载，完成后提示一键安装。
     * 每个页面生命周期内只跑一次；每日 Worker 也会后台预下载。
     */
    private void startAutoUpdateCheck() {
        if (updateChecked) {
            return;
        }
        updateChecked = true;
        // 已有下载好的升级包（如 Worker 夜间预下载的）：
        // 先判断是否仍然比当前已装版本新——用户可能已手动装过，避免误弹
        File pending = AppUpdater.getPendingApk(this);
        if (pending != null) {
            if (AppUpdater.isPendingNewer(this)) {
                String ver = AppUpdater.getPendingVersion(this);
                if (!AppUpdater.isSnoozed(this, ver)) {
                    showInstallDialog(ver, pending);
                }
                return;
            }
            // 已装过或已过期：清掉残留，继续正常检测
            AppUpdater.clearPending(this);
        }
        new Thread(() -> {
            AppUpdater.UpdateInfo info = AppUpdater.fetchUpdateInfo();
            if (!AppUpdater.hasNewer(this, info)) {
                return;
            }
            File apk = AppUpdater.downloadApk(this, info);
            if (apk == null) {
                return;
            }
            AppUpdater.savePending(this, info, apk);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    showInstallDialog(info.versionName, apk);
                }
            });
        }, "AppUpdateCheck").start();
    }

    /** 发现新版本的安装提示（大按钮，适老化）。 */
    private void showInstallDialog(String versionName, File apk) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (AppUpdater.isSnoozed(this, versionName)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + versionName)
                .setMessage("新版本已自动下载完成，安装后广告跳过更好用。\n\n安装时请点「继续安装」。")
                .setCancelable(false)
                .setPositiveButton("立即安装", (d, w) -> AppUpdater.installApk(this, apk))
                .setNegativeButton("稍后再说", (d, w) -> AppUpdater.snooze(this, versionName))
                .show();
    }

    /**
     * 处理"已去授权安装未知应用，返回后继续安装"。
     * @return true 表示已接管（唤起了安装或授权设置），本次不再做自动检测
     */
    private boolean continuePendingInstall() {
        SharedPreferences prefs = getSharedPreferences("silvershield", MODE_PRIVATE);
        if (!prefs.getBoolean(AppUpdater.PREF_PENDING_INSTALL, false)) {
            return false;
        }
        File apk = AppUpdater.getPendingApk(this);
        if (apk == null) {
            prefs.edit().remove(AppUpdater.PREF_PENDING_INSTALL).apply();
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            // 还没授权成功，保留状态，等下次回来再试
            return true;
        }
        prefs.edit().remove(AppUpdater.PREF_PENDING_INSTALL).apply();
        AppUpdater.installApk(this, apk);
        return true;
    }

    /** 运行诊断详情：把服务各环节状态展示出来，便于排查问题。 */
    private void showDiagnostics() {
        boolean enabled = isServiceEnabled();
        boolean connected = SkipAccessibilityService.serviceConnected;
        long gap = System.currentTimeMillis() - SkipAccessibilityService.lastActiveTime;
        String lastEvent = SkipAccessibilityService.lastActiveTime <= 0
                ? "从未收到"
                : (gap / 1000) + " 秒前";
        String info = "系统无障碍开关： " + (enabled ? "已开启" : "未开启") + "\n"
                + "服务绑定状态： " + (connected ? "已连接（服务已成功启动）" : "未连接（服务未成功启动）") + "\n"
                + "累计收到事件： " + SkipAccessibilityService.eventCount + " 条\n"
                + "最近一次事件： " + lastEvent + "\n"
                + "安卓版本： Android " + Build.VERSION.RELEASE + "（SDK " + Build.VERSION.SDK_INT + "）\n"
                + "手机型号： " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统版本： " + Build.DISPLAY;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.diag_title))
                .setMessage(info)
                .setPositiveButton("好的", null)
                .show();
    }

    /**
     * 状态卡点击（v1.4.4 真·守护开关）：
     * 点「已停止」→ 拦截真的全停（服务忽略一切事件与补扫）；
     * 点「守护中」→ 恢复拦截。不影响无障碍/保活等系统设置。
     */
    private void onStatusCardClick() {
        switchOn = !switchOn;
        // 立即同步到服务：停止/恢复拦截（并刷新通知栏）
        if (SkipAccessibilityService.instance != null) {
            SkipAccessibilityService.instance.setPaused(!switchOn);
        } else {
            getSharedPreferences("silvershield", MODE_PRIVATE)
                    .edit().putBoolean("switch_on", switchOn).apply();
            SkipAccessibilityService.paused = !switchOn;
        }
        refreshSwitchCard();
        refreshEngineStatus();
        ToastUtil.showCenter(this, switchOn ? "银盾已开始守护" : "银盾已停止守护");
    }

    /** 状态卡显示：switchOn=true → 蓝卡「守护中」；false → 灰卡「已停止」。 */
    private void refreshSwitchCard() {
        android.widget.ImageView ivIcon = findViewById(R.id.ivStatusIcon);
        if (switchOn) {
            statusCard.setBackgroundResource(R.drawable.bg_status_on);
            tvStatus.setText("守护中");
            ivIcon.setImageResource(R.drawable.ic_w_check);
        } else {
            statusCard.setBackgroundResource(R.drawable.bg_status_off);
            tvStatus.setText("已停止");
            ivIcon.setImageResource(R.drawable.ic_w_pause);
        }
    }

    /**
     * v1.4.4：状态卡副文字——守护开关与引擎状态一目了然。
     */
    private void refreshEngineStatus() {
        if (!switchOn) {
            // 已停止：拦截真的停了
            tvStatusHint.setText("已停止守护，点此恢复");
            tvStatusHint.setTextColor(Color.parseColor("#EAEAEA"));
            return;
        }
        boolean engineOn = isServiceEnabled();
        if (engineOn) {
            tvStatusHint.setText("守护引擎运行中");
            tvStatusHint.setTextColor(Color.parseColor("#DDEBFF"));
        } else {
            tvStatusHint.setText("⚠ 引擎未启动：升级安装后系统会自动关闭，"
                    + "请到 设置→无障碍 重新开启银盾");
            tvStatusHint.setTextColor(Color.parseColor("#FFE082"));
        }
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) {
            return false;
        }
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String want = getPackageName() + "/com.silvershield.app.SkipAccessibilityService";
        for (AccessibilityServiceInfo info : list) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            String id = si.packageName + "/" + si.name;
            if (id.equals(want)) {
                return true;
            }
        }
        // 兜底：读系统设置串（既匹配简写 pkg/.Cls，也匹配全限定名）
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && (enabled.contains("com.silvershield.app/.SkipAccessibilityService")
                || enabled.contains("com.silvershield.app/com.silvershield.app.SkipAccessibilityService"));
    }

    private void speakGuide() {
        if (!ttsReady) {
            ToastUtil.showCenter(this, "语音引擎未就绪，请稍候或手动阅读");
            return;
        }
        String text = "欢迎使用银盾。请点击设置，在已下载的应用里找到银盾并开启开关。"
                + "开启后，银盾进入守护状态，开屏广告和烦人弹窗会自动跳过。"
                + "本应用只点击广告本身的跳过或关闭按钮，不会删除广告，也不会收集您的隐私。";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide");
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /** 统计内置规则条数（显示在版本号旁，让用户一眼看到规则覆盖量）。 */
    private int countRules() {
        try {
            java.io.InputStream in = getAssets().open("AllRules.json");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String s = bos.toString("UTF-8").trim();
            if (s.startsWith("[")) {
                return new org.json.JSONArray(s).length();
            }
            return new org.json.JSONObject(s).length();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Throwable ignored) {
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
