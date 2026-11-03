package com.silvershield.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 银盾 —— 基于安卓无障碍服务的广告跳过引擎。
 *
 * 工作原理：
 *  1. 监听窗口状态 / 内容变化事件；
 *  2. 对当前前台 App，按其包名 hashCode 查规则，执行 popup_rules 关闭应用内弹窗；
 *  3. 用默认 / 包级关键词在界面上寻找“跳过 / 关闭”按钮并自动点击；
 *  4. 只点击界面上“已存在”的按钮，不破解、不屏蔽、不删除广告。
 *
 * 针对“保护老人”新增：
 *  - 紧急保护：拨号 / 通话 / 相机 / 支付等界面完全不触发跳过，防止误触关闭重要弹窗；
 *  - 拦截统计：每次成功跳过都记入统计，可在「拦截统计」页查看并分享给子女；
 *  - 规则热更新：收到 ACTION_RULES_UPDATED 广播后重新加载合并后的规则。
 */
public class SkipAccessibilityService extends AccessibilityService {

    private volatile RulesManager rulesManager;
    private volatile StatsManager stats;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 后台单线程执行器：规则初始化与界面扫描都在后台做，主线程只保留系统回调（防 ANR / 服务被系统判故障）。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** 每个包最近一次“默认跳过”的时刻，避免同一包短时间内重复触发。 */
    private final Map<String, Long> lastDefaultSkip = new HashMap<>();
    /** 每个包最近一次“广告窗口关闭”的时刻（去抖）。 */
    private final Map<String, Long> lastAdClose = new HashMap<>();
    /** 正在等待延迟重扫的包（去抖用），防止事件密集时反复提交。 */
    private final Set<String> pendingRescan = ConcurrentHashMap.newKeySet();
    /** 每个包最近一次全量扫描的时刻（事件风暴去抖）。 */
    private final Map<String, Long> lastScanTime = new HashMap<>();
    /** 最近一次收到系统事件的时间（供首页判断服务是否在跑，单位毫秒）。 */
    public static volatile long lastActiveTime = 0;
    /** 服务是否已被系统成功绑定（onServiceConnected 触发过）。 */
    public static volatile boolean serviceConnected = false;
    /** 累计收到的事件数（诊断用）。 */
    public static volatile long eventCount = 0;
    /** 最近一次弹出“已跳过”提示的时间，用于节流。 */
    private static long lastToastTime = 0;
    /** 当前服务实例（v1.4.0：供首页状态卡点击“停止拦截”时调用 disableSelf）。 */
    public static volatile SkipAccessibilityService instance = null;
    /** 最近一次动作类型：true=拦截弹窗（点×关闭），false=跳过开屏/横幅（点跳过）。 */
    private static volatile boolean lastActionWasBlock = false;
    /** 通知栏状态渠道 ID（低重要级，静默、不响铃）。 */
    private static final String CHANNEL_ID = "shield_status";
    /**
     * ★ v1.4.4 守护开关：true=用户点了「已停止」，拦截真的全停。
     *   首页点击状态卡时同步修改；服务启动时从本地配置恢复。
     *   所有扫描路径（事件扫描 / 延迟补扫）开头都会检查它。
     */
    public static volatile boolean paused = false;
    /**
     * ★ v1.4.2 扫描去积压：同一时刻队列里最多只放一个待扫描任务。
     *   事件风暴时（弹幕/动画界面每秒可产生上百个 CONTENT_CHANGED 事件），
     *   此前每个事件都提交一次全量扫描，单线程执行器队列无限积压，
     *   真正的扫描被拖后几秒甚至十几秒——“跳过”倒计时早结束了还没扫到，
     *   表现为“突然不好使了、老广告跳不掉”。
     */
    private final java.util.concurrent.atomic.AtomicBoolean scanQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 全局默认开屏跳过关键词，按优先级排列（只保留高置信度词）。
     * ★ 刻意不含 close / 关闭 / × / x 等泛匹配词：它们会误点系统控件或正常弹窗
     *   （模拟器实测：default 里含 close 时会抢先命中系统 "Close app" 节点，
     *   挤掉真正的“跳过”按钮）。弹窗关闭交给各 App 的专属规则（popup_rules）处理。
     */
    private final List<String> defaultSkipKeywords = Arrays.asList(
            "跳过", "跳过广告", "跳过>>", "跳过 >>", "跳過", "skip ad", "skip", "skip ad"
    );

    /**
     * 紧急保护：以下界面完全不触发跳过，避免误触关闭拨号 / 通话 / 相机 / 支付等关键弹窗。
     * 这些是老人最常用的“生命通道”与资金通道，一旦被误关后果严重。
     */
    private final Set<String> PROTECTED_PACKAGES = new HashSet<>(Arrays.asList(
            // 拨号 / 通话
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.huawei.android.dialer", "com.miui.dialer", "com.android.incallui",
            "com.android.server.telecom",
            // 相机
            "com.android.camera", "com.android.camera2", "com.sec.android.app.camera",
            "com.huawei.camera", "com.miui.camera", "com.oppo.camera", "com.vivo.camera",
            // 支付 / 银行（误触关闭付款确认风险高）
            "com.eg.android.AlipayGphone", "cmb.pb", "com.icbc", "com.chinamworld.bocmbci",
            "com.cmbchina", "com.spdb.mobilebank.per"
    ));

    /**
     * 永不扫描的包：本应用自身（界面上有“跳过时显示提示”等字样，会自己点自己），
     * 以及系统 UI / 桌面 / 设置等——这些界面不可能有开屏广告，扫描只会造成误点与统计虚增。
     */
    private final Set<String> NO_SCAN_PACKAGES = new HashSet<>(Arrays.asList(
            "com.silvershield.app",
            "com.android.systemui", "com.miui.systemui", "com.miui.home",
            "com.android.settings", "com.android.quicksearchbox", "com.miui.screenshot",
            "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
            "com.mi.android.globallauncher", "com.android.inputmethod.latin",
            "com.baidu.input", "com.iflytek.inputmethod", "com.sohu.inputmethod.sogou",
            "com.tencent.mobileqq:tools", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.miui.securitycenter",
            // 各家系统安装器：安装确认 / 安全检测页有“跳过”按钮，绝不能点（实测被连点 224 次）
            "com.miui.packageinstaller", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.lenovo.ideafone.packageinstaller",
            "com.sec.android.app.packageinstaller", "com.huawei.systemmanager",
            // v1.4.8：笔记 / 备忘录类应用——正文由用户随意编写，极易出现“跳过 / 关闭”
            // 等字样，被误判为广告按钮后遭到连点（小米笔记实测被误点 20+ 次）
            "com.miui.notes", "com.miui.note", "com.android.notes", "com.sec.android.app.notes",
            "com.samsung.android.memo", "com.huawei.notepad", "com.hihonor.notepad",
            "com.coloros.note", "com.oppo.note", "com.vivo.notes", "com.heytap.note",
            "com.example.android.notepad"
    ));

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadRules();
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // 第一时间记录“已连接”，供首页诊断区分“未绑定”与“绑定后收不到事件”
        serviceConnected = true;
        instance = this;
        createNotificationChannel();
        postStatusNotification();
        // ★ 初始化全部移到后台线程：此前在主线程同步解析 331 条规则 + 读文件，
        //   慢设备上会拖住 handleBindService，被系统判“执行服务超时”(ANR) 后放弃绑定，
        //   表现为“无障碍开关已开但服务永远起不来”。绑定回调本身必须秒回。
        final SkipAccessibilityService self = this;
        worker.execute(() -> {
            try {
                // v1.4.4：恢复上次的守护开关状态（用户点了「已停止」就真的不拦截）
                paused = !getSharedPreferences("silvershield", MODE_PRIVATE)
                        .getBoolean("switch_on", true);
                rulesManager = new RulesManager();
                stats = new StatsManager(self);
                reloadRules();
            } catch (Throwable t) {
                // 任何初始化异常都不能让服务崩溃：降级为仅默认跳过关键词模式
                try {
                    rulesManager = new RulesManager();
                    reloadRules();
                } catch (Throwable ignored) {
                }
            }
            try {
                IntentFilter f = new IntentFilter(UpdateManager.ACTION_RULES_UPDATED);
                // Android 14+（targetSdk 34）强制要求注册广播时声明 RECEIVER_EXPORTED / NOT_EXPORTED，
                // 否则直接抛 SecurityException 导致服务崩溃（系统显示“此服务出现故障”）。必须按版本分支注册。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(updateReceiver, f);
                }
            } catch (Throwable ignored) {
            }
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldEvent", "服务初始化完成，规则就绪");
            }
        });
    }

    /** 创建低重要级状态渠道（静默、不响铃）。 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "银盾守护状态", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("显示银盾是否在守护，并可一键开关");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** 在通知栏展示当前守护状态，并提供「暂停/恢复守护」按钮。 */
    private void postStatusNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        boolean running = !paused;
        String title = running ? "银盾 · 守护中" : "银盾 · 已停止";
        String text = running ? "正在自动跳过广告，点此可停止"
                : "守护已停止，点此可恢复";

        // 点通知主体 → 打开首页
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE);

        // 通知按钮 → 切换守护开关
        Intent toggle = new Intent(this, ShieldToggleReceiver.class);
        toggle.setAction(ShieldToggleReceiver.ACTION_TOGGLE);
        PendingIntent actionIntent = PendingIntent.getBroadcast(this, 2, toggle,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notif;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_shield_notif)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(0xFF2196F3)
                    .setContentIntent(contentIntent)
                    // v1.5.5：用户要求「可划掉」的普通常驻——不用前台服务也不锁死通知，
                    // 划掉只是收起快捷开关，守护引擎照常运行，下次状态变化会重新出现
                    .setOngoing(false)
                    .addAction(0, running ? "停止守护" : "恢复守护", actionIntent)
                    .build();
        } else {
            // API 24/25 没有 channel 版构造器
            notif = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_shield_notif)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setOngoing(false)
                    .build();
        }
        nm.notify(1, notif);
    }

    /** 移除状态通知（关闭无障碍时）。 */
    private void cancelStatusNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(1);
        }
    }

    /**
     * ★ 切换守护开关（供首页状态卡与通知栏按钮共用）：
     *   - 写入本地持久化（下次服务启动自动恢复）；
     *   - 立即同步 paused 标志（停止/恢复拦截）；
     *   - 刷新通知栏状态。
     */
    public void setPaused(boolean pause) {
        paused = pause;
        getSharedPreferences("silvershield", MODE_PRIVATE)
                .edit().putBoolean("switch_on", !pause).apply();
        postStatusNotification();
    }

    /** 重新加载规则：先内置资产，再叠加本地合并文件（远程优先）。 */
    private void reloadRules() {
        if (rulesManager == null) {
            rulesManager = new RulesManager();
        }
        rulesManager.clear();
        try {
            rulesManager.load(getAssets().open("AllRules.json"));
        } catch (Exception ignored) {
            // 资产缺失时降级为仅默认跳过
        }
        try {
            UpdateManager um = new UpdateManager(this);
            java.io.File merged = um.getMergedFile();
            if (merged.exists()) {
                rulesManager.load(new FileInputStream(merged));
            }
        } catch (Exception ignored) {
            // 合并文件缺失则用内置
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            // ★ v1.4.4：守护开关已停止 → 事件直接忽略，拦截真的停
            if (paused) {
                return;
            }
            eventCount++;
            int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                return;
            }

            // 记录心跳：只要收到相关窗口事件，就说明服务进程还活着（用于首页自检）
            lastActiveTime = System.currentTimeMillis();

            CharSequence pkgCs = event.getPackageName();
            String pkg = pkgCs != null ? pkgCs.toString() : "";
            // 临时诊断日志：确认事件链路（仅 DEBUG 构建输出，正式版静默，避免刷屏）
            if (BuildConfig.DEBUG && eventCount <= 10) {
                Log.i("SilverShieldEvent", "收到事件 #" + eventCount + " type=" + type + " pkg=" + pkg);
            }

            // ★ 扫描策略（v1.3.0）：不依赖事件来源包名。
            //   实测 MIUI 上开屏广告的事件来源经常是广告 SDK / 系统窗口，与焦点窗口
            //   包名不一致，若要求两者相等会导致扫描被整体跳过（拦截完全失效）。
            //   改为直接扫描"当前焦点窗口"，误扫风险由 NO_SCAN / PROTECTED 黑名单控制。
            // ★ 扫描（遍历节点树 + 规则匹配）放后台线程，避免拖住主线程导致系统 ANR。
            //   ★ v1.4.2：去积压——队列里已有待扫描任务时直接跳过本次提交，
            //   反正那个任务会扫到“当时”的最新界面，积压的旧扫描没有意义。
            if (scanQueued.compareAndSet(false, true)) {
                worker.execute(() -> {
                    scanQueued.set(false);
                    safeScanFocused();
                });
            }
            // ★ v1.3.6 修复：立即安排延迟补扫。此前 scheduleRescan 是死代码（从未被调用），
            //   真实广告的“跳过”按钮经常在开屏 1~2 秒后才渲染，首轮扫描扑空后
            //   再也没有人管——这是“模拟器里能跳、真机上漏拦”的主要原因。
            if (!pkg.isEmpty() && !NO_SCAN_PACKAGES.contains(pkg)
                    && !PROTECTED_PACKAGES.contains(pkg)) {
                scheduleRescan(pkg);
            }
        } catch (Throwable ignored) {
            // 事件处理中任何异常都不能向系统抛出，否则服务被判“出现故障”并停止。
            // 心跳照常记录，保证首页自检不误报。
            lastActiveTime = System.currentTimeMillis();
        }
    }

    /** 扫描当前所有可访问窗口：活动窗口（开屏广告）+ 独立弹窗窗口（应用内弹窗广告）。 */
    private void scanFocusedWindow() {
        boolean anyClick = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldSkip", "scan: root=null（活动窗口未就绪）");
            }
        } else {
            try {
                anyClick = scanWindowRoot(root) > 0;
            } finally {
                root.recycle();
            }
        }
        // ★ 应用内弹窗广告（Dialog / PopupWindow）渲染在独立窗口里，
        //   活动窗口的节点树中根本没有“跳过 / 关闭”按钮——必须逐个窗口扫描。
        //   自家 / 系统 / 桌面 / 输入法 / 关键应用由包名黑名单统一排除。
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                // ★ 注意：这里不能按 isActive()/isFocused() 过滤！
                //   模拟器实测：当系统 ANR 弹窗等系统窗口占据焦点时，
                //   后排真实广告窗口 isActive()==false，过滤后广告永远扫不到。
                //   失效节点（后台窗口坐标为 0）的误点风险由 performClick 的
                //   “0 边界放弃点击”防护兜底。
                if (w == null) {
                    continue;
                }
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr == null) {
                    continue;
                }
                try {
                    if (scanWindowRoot(wr) > 0) {
                        anyClick = true;
                    }
                } finally {
                    wr.recycle();
                }
            }
        } catch (Throwable ignored) {
        }
        if (anyClick) {
            // Toast 必须在主线程弹（后台线程直接弹会崩溃）
            mainHandler.post(this::maybeShowSkipToast);
        }
    }

    /** 扫描一个窗口的节点树：包名过滤 + 去抖 + 规则/关键词匹配；有点击则记入统计。返回点击数。 */
    private int scanWindowRoot(AccessibilityNodeInfo root) {
        CharSequence rootPkgCs = root.getPackageName();
        String pkg = rootPkgCs != null ? rootPkgCs.toString() : "";
        // 防护：本应用与系统界面（自家界面有“跳过时显示提示”字样，会自己点自己）、
        // 桌面 / 输入法 / 安装器等系统界面，以及拨号 / 相机 / 支付等关键应用，永不扫描。
        if (pkg.isEmpty() || NO_SCAN_PACKAGES.contains(pkg) || PROTECTED_PACKAGES.contains(pkg)) {
            return 0;
        }
        // 同一应用 300ms 内不重复全量扫描（事件风暴去抖）
        long now = System.currentTimeMillis();
        Long last = lastScanTime.get(pkg);
        if (last != null && now - last < 300) {
            return 0;
        }
        lastScanTime.put(pkg, now);
        int clicks = 0;
        Rule rule = rulesManager != null ? rulesManager.getRule(pkg) : null;
        // 临时诊断：扫描入口与规则命中情况（仅 DEBUG 构建输出，正式版静默）
        if (BuildConfig.DEBUG) {
            Log.i("SilverShieldSkip", "scanWindowRoot pkg=" + pkg
                    + " rule=" + (rule != null ? "有" : "无")
                    + " popupRules=" + (rule != null ? rule.popupRules.size() : 0)
                    + " keywords=" + (rule != null ? rule.keywords.size() : 0)
                    + " mergedSize=" + (rulesManager != null ? rulesManager.size() : -1));
        }
        if (rule != null) {
            clicks += applyPopupRules(root, rule);
            clicks += applyKeywordSkip(root, rule);
        }
        clicks += applyDefaultSkip(root, pkg);
        clicks += applySkipById(root, pkg);
        clicks += applyAdClose(root, pkg);
        if (clicks > 0) {
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldSkip", "scan " + pkg + " clicks=" + clicks);
            }
            if (stats != null) {
                stats.record(pkg, lastActionWasBlock);
            }
        }
        return clicks;
    }

    // ------------------------------------------------------------------
    // 2b. 按控件资源 ID 识别跳过按钮（v1.3.6 新增）
    // ------------------------------------------------------------------
    /**
     * 很多广告 SDK 的“跳过”是自绘控件，文字抓不到（或渲染成倒计时数字），
     * 但资源 ID 几乎都带 skip：如 com.byted.sdk:id/skip、tt_splash_skip、skip_btn。
     * 按 viewIdResourceName 兜底识别。
     */
    private int applySkipById(AccessibilityNodeInfo root, String pkg) {
        long now = System.currentTimeMillis();
        Long last = lastDefaultSkip.get(pkg);
        if (last != null && now - last < 1500) {
            return 0;
        }
        AccessibilityNodeInfo target = dfsSkipId(root, root);
        if (target == null) {
            return 0;
        }
        try {
            performClick(target);
            lastDefaultSkip.put(pkg, now);
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldSkip", "按ID命中skip控件");
            }
            return 1;
        } finally {
            target.recycle();
        }
    }

    private AccessibilityNodeInfo dfsSkipId(AccessibilityNodeInfo node, AccessibilityNodeInfo root) {
        if (node == null) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) {
                continue;
            }
            AccessibilityNodeInfo found = dfsSkipId(c, root);
            if (found != null) {
                return found;
            }
            c.recycle();
        }
        if (node != root) {
            String id = node.getViewIdResourceName();
            if (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("skip")) {
                // 与文字匹配同样排除“已完成态”，防止跳过后对着“已跳过”按钮反复点击
                String text = norm(node.getText() != null ? node.getText().toString() : "");
                String desc = norm(node.getContentDescription() != null
                        ? node.getContentDescription().toString() : "");
                boolean done = text.contains("已跳过") || text.contains("已关闭")
                        || desc.contains("已跳过") || desc.contains("已关闭");
                android.graphics.Rect r = new android.graphics.Rect();
                node.getBoundsInScreen(r);
                if (!done && r.width() > 0 && r.height() > 0) {
                    return node;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 2c. 弹窗 / banner 广告的“× 关闭”处理（v1.3.6 新增）
    // ------------------------------------------------------------------
    /**
     * 应用内弹窗广告（京东 / 拼多多式）没有“跳过”按钮，只有一个 × 关闭角标。
     * 之前因“close 泛匹配误点系统弹窗”把关闭词整体删掉，导致这类广告完全拦不住。
     * v1.3.6 改为两步确认，兼顾安全与覆盖：
     *  ① 先确认窗口确实是广告窗口（含“立即查看 / 查看详情 / 摇一摇”等广告 CTA 词）；
     *  ② 再在窗口内找关闭控件：文案/描述为 关闭 / 关闭广告 / ×，或资源 ID 含 close，
     *     且控件足够小（宽高均不超过屏幕 20%，排除误点大块按钮）。
     * 两步都满足才点击——正常 App 的更新弹窗、隐私弹窗没有广告 CTA 词，永远不会被误关。
     */
    private static final String[] AD_CTA_MARKERS = {
            "立即查看", "查看详情", "了解详情", "了解更多", "立即抢购", "立即下载",
            "立即打开", "立即体验", "查看更多", "摇一摇", "摇动手机", "免费领取",
            "点击领取", "进入小程序", "下载app", "广告",
            // v1.3.8：易视云等监控类 App 弹窗/横幅广告的新话术（用户实测截图）
            "点击按钮有惊喜", "跳转至详情页", "扭动或点击", "扭一扭", "点击查看详情",
            // v1.3.9：易视云弹窗广告实际话术（"摇动跳转网页或第三方应用"，此前漏匹配）
            "摇动跳转", "跳转网页", "跳转三方", "第三方应用", "今日推荐", "精选内容",
            "跳转详情页或第三方"
    };

    /** 资源 ID 里出现这些字样 → 基本可断定是广告 SDK 渲染的窗口（按 ID 识别广告窗口）。 */
    private static final String[] AD_ID_MARKERS = {
            "anythink", "mraid", "ad_logo", "ad_tag", "tt_splash", "ksad", "gdt_ad",
            // v1.5.13：百度系开屏广告 SDK 特征（百度地图/百度开屏广告窗口的容器 id 常含 splash / mobads）
            "splash", "mobads"
    };

    private int applyAdClose(AccessibilityNodeInfo root, String pkg) {
        long now = System.currentTimeMillis();
        Long last = lastAdClose.get(pkg);
        if (last != null && now - last < 800) {
            return 0;
        }
        AdCloseScan r = dfsAdClose(root, root, false);
        if (!r.hasMarker || r.closeNode == null) {
            if (r.closeNode != null) {
                r.closeNode.recycle(); // 有疑似关闭控件但无广告特征：不放行，回收节点
            }
            return 0;
        }
        try {
            performClick(r.closeNode, true);
            lastAdClose.put(pkg, now);
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldSkip", "广告窗口内点击关闭按钮");
            }
            return 1;
        } finally {
            r.closeNode.recycle();
        }
    }

    private static class AdCloseScan {
        boolean hasMarker;
        AccessibilityNodeInfo closeNode;
    }

    private AdCloseScan dfsAdClose(AccessibilityNodeInfo node, AccessibilityNodeInfo root,
                                   boolean inherited) {
        AdCloseScan result = new AdCloseScan();
        if (node == null) {
            return result;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) {
                continue;
            }
            AdCloseScan sub = dfsAdClose(c, root, inherited || result.hasMarker);
            if (sub.closeNode != null) {
                // ★ 注意：sub.closeNode 可能就是 c 本身（或 c 的后代）。
                //   此前在这里无条件 recycle(c)，把刚找到的“×”节点也一并回收了，
                //   导致随后点击时文字/坐标全为 0（v1.3.6 模拟器实测踩坑）。
                result.closeNode = sub.closeNode;
            }
            if (sub.hasMarker) {
                result.hasMarker = true;
            }
            if (result.closeNode == null) {
                c.recycle();
            }
            if (result.closeNode != null && result.hasMarker) {
                return result;
            }
        }
        if (node != root) {
            String text = norm(node.getText() != null ? node.getText().toString() : "");
            String desc = norm(node.getContentDescription() != null
                    ? node.getContentDescription().toString() : "");
            String both = text + "|" + desc;
            for (String m : AD_CTA_MARKERS) {
                if (both.contains(norm(m))) {
                    result.hasMarker = true;
                    break;
                }
            }
            // v1.3.9：广告 SDK 自绘控件常无文字，但资源 ID 带 SDK 特征（anythink/mraid 等），
            // 按资源 ID 判定广告窗口，配合 × 关闭可覆盖纯图片广告。
            String id = node.getViewIdResourceName() != null
                    ? node.getViewIdResourceName().toLowerCase(java.util.Locale.ROOT) : "";
            if (!result.hasMarker) {
                for (String m : AD_ID_MARKERS) {
                    if (id.contains(m)) {
                        result.hasMarker = true;
                        break;
                    }
                }
            }
            if (result.closeNode == null) {
                boolean closeText = text.equals("x") || text.equals("×") || text.equals("✕")
                        || desc.equals("x") || desc.equals("×") || desc.equals("✕")
                        || text.contains("关闭") || desc.contains("关闭");
                boolean closeId = id.contains("close") || id.contains("iv_close")
                        || id.contains("btn_close") || id.contains("closebtn");
                if ((closeText || closeId) && isSmallNode(node)) {
                    result.closeNode = node;
                }
            }
        }
        return result;
    }

    /** 关闭角标都是小控件：宽高均不超过屏幕 20% 才认定，防止误点大块“关闭”按钮。 */
    private boolean isSmallNode(AccessibilityNodeInfo node) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) {
                return false;
            }
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            return r.width() <= dm.widthPixels * 0.2f
                    && r.height() <= dm.heightPixels * 0.2f;
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳过成功后弹出轻量提示（4 秒节流；按行为分类文案，v1.4.0）。 */
    private void maybeShowSkipToast() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastToastTime < 4000) {
                return;
            }
            lastToastTime = now;
            // 按行为分类：点“跳过”按钮 → 跳过广告成功；点广告 × 关闭弹窗/横幅 → 关闭广告成功
            Toast.makeText(this, lastActionWasBlock ? "银盾关闭广告成功" : "银盾跳过广告成功",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            // 提示失败不影响主功能
        }
    }

    /**
     * 去抖后提交多次延迟补扫：350 / 900 / 1600 / 2500 / 3500 / 5000ms。
     * 真实开屏广告（京东 / 平安等）的“跳过”按钮往往在窗口出现 1~2 秒后才渲染，
     * 且倒计时长达 3~5 秒，必须覆盖整个倒计时区间。每次补扫都按“当时的焦点窗口”扫描。
     */
    private void scheduleRescan(String pkg) {
        if (pendingRescan.contains(pkg)) {
            return;
        }
        pendingRescan.add(pkg);
        long[] delays = {350, 900, 1600, 2500, 3500, 5000};
        for (int i = 0; i < delays.length; i++) {
            final boolean last = (i == delays.length - 1);
            mainHandler.postDelayed(() -> {
                if (last) {
                    pendingRescan.remove(pkg);
                }
                // 补扫同样走去积压：队列里已有扫描任务时无需重复提交
                if (scanQueued.compareAndSet(false, true)) {
                    worker.execute(() -> {
                        scanQueued.set(false);
                        safeScanFocused();
                    });
                }
            }, delays[i]);
        }
    }

    /** 扫描并跳过的安全包装：任何异常都不外抛，防止服务被判故障。 */
    private void safeScanFocused() {
        // ★ v1.4.4：守护开关已停止 → 已排队的补扫任务也不执行
        if (paused) {
            return;
        }
        try {
            scanFocusedWindow();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        // 无障碍服务被中断时无需特殊处理
    }

    @Override
    public void onDestroy() {
        serviceConnected = false;
        instance = null;
        cancelStatusNotification();
        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 1. 应用内弹窗规则（popup_rules）
    // ------------------------------------------------------------------
    private int applyPopupRules(AccessibilityNodeInfo root, Rule rule) {
        int clicks = 0;
        for (Rule.PopupRule pr : rule.popupRules) {
            if (pr.id == null || pr.action == null) {
                continue;
            }
            AccessibilityNodeInfo trigger = findFirst(root, pr.id);
            if (trigger == null) {
                continue;
            }
            trigger.recycle();
            AccessibilityNodeInfo target = resolveAction(root, pr.action);
            if (target != null) {
                performClick(target, "CLICK_AD_CLOSE".equalsIgnoreCase(pr.action));
                target.recycle();
                clicks++;
            }
        }
        return clicks;
    }

    // ------------------------------------------------------------------
    // 2. 关键词跳过（默认 + 包级 keywords 覆盖）
    // ------------------------------------------------------------------
    private int applyKeywordSkip(AccessibilityNodeInfo root, Rule rule) {
        List<String> kws = new ArrayList<>();
        kws.addAll(rule.keywords);
        kws.addAll(rule.keywordsAppend);
        if (kws.isEmpty()) {
            return 0;
        }
        for (String kw : kws) {
            AccessibilityNodeInfo target = findClickableByText(root, kw, false);
            if (target != null) {
                performClick(target);
                target.recycle();
                return 1;
            }
        }
        return 0;
    }

    private int applyDefaultSkip(AccessibilityNodeInfo root, String pkg) {
        long now = System.currentTimeMillis();
        Long last = lastDefaultSkip.get(pkg);
        // v1.4.8：冷却 500ms → 2000ms。真实广告点一次就够，冷却太短时
        // 界面没变化会反复补点（小米笔记误伤时 1 秒内连点 4~5 次）
        if (last != null && now - last < 2000) {
            return 0; // 同一包短时间内不重复触发，避免抖动
        }
        int clicks = 0;
        // 按优先级遍历，一轮扫描最多点掉 2 个目标（如“跳过”按钮 + 广告角标关闭）
        for (String kw : defaultSkipKeywords) {
            AccessibilityNodeInfo target = findClickableByText(root, kw, false, true);
            if (target != null) {
                performClick(target);
                target.recycle();
                clicks++;
                if (clicks >= 2) {
                    break;
                }
            }
        }
        if (clicks > 0) {
            lastDefaultSkip.put(pkg, now);
        }
        return clicks;
    }

    // ------------------------------------------------------------------
    // 3. 解析 action：返回键 / 坐标 / 按 id 或文案点击
    // ------------------------------------------------------------------
    private AccessibilityNodeInfo resolveAction(AccessibilityNodeInfo root, String action) {
        if ("GLOBAL_ACTION_BACK".equalsIgnoreCase(action)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return null;
        }
        // v1.3.9：特殊动作——在当前窗口内找广告关闭控件（×/关闭）并点击。
        // 规则库热更新可用：{"id":"摇动跳转","action":"CLICK_AD_CLOSE"}
        if ("CLICK_AD_CLOSE".equalsIgnoreCase(action)) {
            AdCloseScan r = dfsAdClose(root, root, false);
            if (r.closeNode != null && r.hasMarker) {
                performClick(r.closeNode, true);
                r.closeNode.recycle();
                if (BuildConfig.DEBUG) {
                    Log.i("SilverShieldSkip", "规则动作CLICK_AD_CLOSE命中");
                }
                return null;
            }
            if (r.closeNode != null) {
                r.closeNode.recycle();
            }
            return null;
        }
        if (action.matches("^-?\\d+,\\s*-?\\d+,\\s*-?\\d+,\\s*-?\\d+$")) {
            String[] p = action.split(",");
            try {
                int x1 = Integer.parseInt(p[0].trim());
                int y1 = Integer.parseInt(p[1].trim());
                int x2 = Integer.parseInt(p[2].trim());
                int y2 = Integer.parseInt(p[3].trim());
                clickAt((x1 + x2) / 2f, (y1 + y2) / 2f);
            } catch (Exception ignored) {
                // 坐标解析失败则忽略
            }
            return null;
        }
        return findFirst(root, action);
    }

    // ------------------------------------------------------------------
    // 4. 节点匹配
    // ------------------------------------------------------------------

    /** 纯 ASCII（字母/数字/下划线/点/横杠）视为资源 ID 匹配，否则按文案匹配。 */
    private boolean isAsciiIdentifier(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** 在整棵 UI 树中查找第一个满足 query 的节点（不返回 root 本身）。 */
    private AccessibilityNodeInfo findFirst(AccessibilityNodeInfo root, String query) {
        if (root == null || query == null || query.isEmpty()) {
            return null;
        }
        boolean asResId = isAsciiIdentifier(query);
        String[] ands = query.split("&");
        return dfsMatch(root, ands, asResId, root);
    }

    private AccessibilityNodeInfo dfsMatch(AccessibilityNodeInfo node, String[] ands,
                                           boolean asResId, AccessibilityNodeInfo root) {
        if (node == null) {
            return null;
        }
        AccessibilityNodeInfo found = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) {
                continue;
            }
            found = dfsMatch(c, ands, asResId, root);
            if (found != null) {
                break;
            }
            c.recycle();
        }
        if (found == null && node != root && matchesAll(node, ands, asResId)) {
            return node;
        }
        return found;
    }

    private boolean matchesAll(AccessibilityNodeInfo n, String[] ands, boolean asResId) {
        String text = n.getText() != null ? n.getText().toString() : "";
        String resId = n.getViewIdResourceName() != null ? n.getViewIdResourceName() : "";
        for (String a : ands) {
            if (!matchOne(text, resId, a, asResId)) {
                return false;
            }
        }
        // 已完成态排除：popup 规则的 action 如“跳过”会命中“已跳过”文字，
        // 导致跳过成功后对着同一按钮反复补点（模拟器实测踩坑）
        String norm = norm(text);
        if (norm.contains("已跳过") || norm.contains("已被跳过") || norm.contains("自动跳过")
                || norm.contains("已关闭")) {
            return false;
        }
        return true;
    }

    /** 单条件匹配，支持 +开头 / -结尾 / =全等 / 默认包含。 */
    private boolean matchOne(String text, String resId, String part, boolean asResId) {
        if (part.isEmpty()) {
            return false;
        }
        char p = part.charAt(0);
        String s;
        boolean starts = false, ends = false, exact = false;
        if (p == '+') {
            starts = true;
            s = part.substring(1);
        } else if (p == '-') {
            ends = true;
            s = part.substring(1);
        } else if (p == '=') {
            exact = true;
            s = part.substring(1);
        } else {
            s = part;
        }
        if (s.isEmpty()) {
            return false;
        }
        if (asResId) {
            return resId.contains(s);
        }
        if (exact) {
            return text.equals(s);
        }
        if (starts) {
            return text.startsWith(s);
        }
        if (ends) {
            return text.endsWith(s);
        }
        return text.contains(s);
    }

    /** 查找第一个可点击且文案命中关键词的节点（不返回 root 本身）。 */
    private AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo root, String kw, boolean exact) {
        return findClickableByText(root, kw, exact, false);
    }

    /**
     * @param strict 严格模式（v1.4.8 防误伤）：默认跳过路径必须开启。
     *   只认“小而短”的跳过按钮——真实广告跳过按钮是小控件、文字很短；
     *   而用户内容（笔记正文、文章、聊天记录）里也可能出现“跳过”等字样，
     *   是大段长文本，直接排除，防止在用户内容上乱点（小米笔记实测被连点 20+ 次）。
     *   各 App 的专属规则关键词是人工核对的，不需要严格模式。
     */
    private AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo root, String kw,
                                                      boolean exact, boolean strict) {
        return dfsText(root, kw, exact, root, strict);
    }

    private AccessibilityNodeInfo dfsText(AccessibilityNodeInfo node, String kw, boolean exact,
                                          AccessibilityNodeInfo root, boolean strict) {
        if (node == null) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) {
                continue;
            }
            AccessibilityNodeInfo found = dfsText(c, kw, exact, root, strict);
            if (found != null) {
                return found;
            }
            c.recycle();
        }
        if (node != root) {
            String text = node.getText() != null ? node.getText().toString() : "";
            String desc = node.getContentDescription() != null
                    ? node.getContentDescription().toString() : "";
            // 大小写不敏感（兼容英文 Skip / SKIP 类广告按钮）；
            // 去掉全部空白后匹配（部分广告把“跳过”渲染成“跳 过”或“跳过　3s”，原样匹配会漏）
            String t = norm(text);
            String d = norm(desc);
            String k = norm(kw);
            boolean hit = exact
                    ? (t.equals(k) || d.equals(k))
                    : (t.contains(k) || d.contains(k));
            // 已完成态排除：“已跳过 / 已被跳过 / 已自动跳过 / 已关闭”等文字仍含关键词，
            // 避免对着同一按钮或结果提示文字反复点击
            if (hit && (t.contains("已跳过") || t.contains("已被跳过") || t.contains("自动跳过")
                    || t.contains("已关闭") || d.contains("已跳过"))) {
                hit = false;
            }
            // ★ v1.4.8 严格模式防误伤（默认跳过路径专用）：
            if (hit && strict) {
                // ① 输入框永不匹配：笔记 / 备忘录正文是 EditText，里面出现什么字都不能点
                CharSequence cls = node.getClassName();
                if ((cls != null && cls.toString().contains("EditText")) || node.isEditable()) {
                    hit = false;
                }
                // ② 命中文本必须短：真实跳过按钮文字 ≤ 12 字；
                //    笔记正文 / 文章段落是长文本，直接排除
                if (hit) {
                    String matched = t.contains(k) ? t : d;
                    if (matched.length() > 12) {
                        hit = false;
                    }
                }
                // ③ 控件必须小：跳过按钮宽不超过半屏、高不超过 20% 屏高；
                //    笔记正文是占满整屏的大块文本
                if (hit) {
                    android.graphics.Rect r0 = new android.graphics.Rect();
                    node.getBoundsInScreen(r0);
                    android.util.DisplayMetrics dm0 = getResources().getDisplayMetrics();
                    if (r0.width() > dm0.widthPixels * 0.5f
                            || r0.height() > dm0.heightPixels * 0.2f) {
                        hit = false;
                    }
                }
            }
            if (hit) {
                android.graphics.Rect r = new android.graphics.Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    // 返回命中的文字节点本身；真正点击由 performClick 决定
                    // （按钮级可点击节点→正常点击；只有整屏容器可点→改为坐标点击文字处）
                    return node;
                }
            }
        }
        return null;
    }

    /** 归一化：转小写并去掉全部空白（含全角空格），用于跨渲染样式稳定的文案匹配。 */
    private String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "")
                .replace("\u00a0", "")
                .replace("\u3000", "");
    }
    //    很多开屏广告的跳过按钮用 OnTouchListener 实现，不响应无障碍的
    //    ACTION_CLICK，只认真实触摸，因此需用手势点按。
    // ------------------------------------------------------------------
    // 5. 点击：优先在命中文字处做“真实手指点按”（手势），ACTION_CLICK 兜底。
    //    很多开屏广告的跳过按钮用 OnTouchListener 实现，不响应无障碍的
    //    ACTION_CLICK，只认真实触摸，因此需用手势点按。
    // ------------------------------------------------------------------
    private void performClick(AccessibilityNodeInfo node) {
        performClick(node, false);
    }

    /** @param block true=拦截弹窗（点×关闭），false=跳过开屏/横幅（点跳过按钮）。 */
    private void performClick(AccessibilityNodeInfo node, boolean block) {
        if (node == null) {
            return;
        }
        lastActionWasBlock = block;
        // ① 首选：在命中文字（如“跳过 1”）的屏幕位置做真实点按
        android.graphics.Rect nr = new android.graphics.Rect();
        node.getBoundsInScreen(nr);
        // 诊断日志仅 DEBUG 输出：坐标 + 控件文案属可被同机其他 App/adb 读取的使用行为信息
        if (BuildConfig.DEBUG) {
            Log.i("SilverShieldSkip", "performClick 目标=" + node.getText()
                    + " id=" + node.getViewIdResourceName()
                    + " 坐标=" + nr.exactCenterX() + "," + nr.exactCenterY());
        }
        if (nr.width() <= 0 || nr.height() <= 0) {
            // 窗口已关闭 / 节点已失效（边界为 0）：此时点按只会点到 (0,0)，必须放弃
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldSkip", "目标节点已失效（0 边界），放弃点击");
            }
            return;
        }
        if (nr.width() > 0 && nr.height() > 0
                && clickAt(nr.exactCenterX(), nr.exactCenterY())) {
            if (BuildConfig.DEBUG) {
                Log.i("SilverShieldSkip", "手势点按已派发");
            }
            return;
        }
        // ② 手势失败（系统不支持/权限缺失）时，回退到无障碍点击：
        //    向上找最近的“按钮级”可点击节点；整屏容器可点则不点（避免误进广告）
        AccessibilityNodeInfo clickTarget = null;
        List<AccessibilityNodeInfo> toRecycle = new ArrayList<>();
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            if (cur.isClickable()) {
                android.graphics.Rect r = new android.graphics.Rect();
                cur.getBoundsInScreen(r);
                long area = (long) r.width() * r.height();
                if (area <= Math.max(1L, (long) nr.width() * nr.height()) * 25L) {
                    clickTarget = cur;
                }
                break;
            }
            AccessibilityNodeInfo p = cur.getParent();
            if (cur != node) {
                toRecycle.add(cur);
            }
            cur = p;
        }
        if (clickTarget != null) {
            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (clickTarget != node) {
                clickTarget.recycle();
            }
        }
        for (AccessibilityNodeInfo r : toRecycle) {
            r.recycle();
        }
    }

    /** 通过手势在屏幕坐标处模拟手指点按；成功返回 true。 */
    private boolean clickAt(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(path, 0, 60));
            return dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }
}
