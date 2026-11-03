package com.silvershield.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用版本自动检测与升级：
 *  - 联网检查 version.json（版本元数据）；
 *  - 发现新版本后静默下载 APK 到应用私有目录；
 *  - 引导用户一键安装（Android 不允许应用完全静默安装 APK，需用户点一次确认）。
 *
 * 说明：升级包须与已装版本签名一致才能覆盖安装；本项目各版本均由同一构建环境产出。
 */
public final class AppUpdater {

    private static final String TAG = "SilverShieldAppUpdate";

    /** 版本元数据地址：每次发布新版时同步更新仓库根目录的 version.json。 */
    public static final String META_URL =
            "https://gitee.com/stcbro/SilverShield/raw/master/version.json";

    /**
     * 用户推送指针：只有开发者决定"把哪个版本推给用户"时才更新此文件。
     * App 优先读本文件做升级检测，读不到/解析失败时回退 META_URL。
     * 这样日常发布新版本不会自动打扰用户，推送节奏完全由开发者掌握。
     */
    public static final String PUSH_URL =
            "https://gitee.com/stcbro/SilverShield/raw/master/update.json";

    private static final String PREFS = "silvershield";
    public static final String PREF_PENDING_APK = "pending_apk";
    public static final String PREF_PENDING_VER = "pending_ver";
    /** 待安装包对应的 versionCode（用于判断是否已经装过）。 */
    public static final String PREF_PENDING_CODE = "pending_code";
    /** 已跳去"允许安装未知应用"设置，返回后继续安装。 */
    public static final String PREF_PENDING_INSTALL = "pending_install";
    /** 用户点"稍后再说"的版本号与时间，24 小时内同版本不再打扰。 */
    public static final String PREF_SNOOZE_VER = "snooze_ver";
    public static final String PREF_SNOOZE_AT = "snooze_at";

    private AppUpdater() {
    }

    /** 版本元数据（version.json 的字段）。 */
    public static final class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String apkUrl;
        public String notes;
    }

    /** 当前已安装版本号（走 PackageManager，不依赖 BuildConfig）。 */
    public static int currentVersionCode(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 联网拉取版本元数据；失败返回 null。【仅在工作线程调用】
     *  优先读 update.json（开发者推送指针），无效时回退 version.json（最新构建元数据）。 */
    public static UpdateInfo fetchUpdateInfo() {
        UpdateInfo info = fetchFrom(PUSH_URL);
        if (info != null && info.versionCode > 0
                && info.apkUrl != null && !info.apkUrl.isEmpty()) {
            return info;
        }
        return fetchFrom(META_URL);
    }

    /** 从指定地址拉取并解析版本元数据；失败返回 null。【仅在工作线程调用】 */
    private static UpdateInfo fetchFrom(String url) {
        try {
            String body = httpGet(url);
            if (body == null || body.isEmpty()) {
                return null;
            }
            JSONObject o = new JSONObject(body);
            UpdateInfo info = new UpdateInfo();
            info.versionCode = o.optInt("versionCode", -1);
            info.versionName = o.optString("versionName", "");
            info.apkUrl = o.optString("apkUrl", "");
            info.notes = o.optString("notes", "");
            return info;
        } catch (Exception e) {
            Log.w(TAG, "拉取版本信息失败: " + url, e);
            return null;
        }
    }

    /** 是否存在可升级的新版本。 */
    public static boolean hasNewer(Context context, UpdateInfo info) {
        return info != null
                && info.versionCode > currentVersionCode(context)
                && info.apkUrl != null && !info.apkUrl.isEmpty();
    }

    /** 下载 APK 到应用私有目录；成功返回文件，失败返回 null。【仅在工作线程调用】
     *  防损坏三件套：完整包直接复用；先写 .part 临时文件再原子改名；全程加锁防并发。
     *  （此前两个下载任务并发写同一文件，安装器读取时文件被截断，报 ENOENT 解析失败。） */
    public static File downloadApk(Context context, UpdateInfo info) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File out = new File(dir, "update-v" + info.versionName + ".apk");
        // 完整包已存在（上次已下载完）：直接复用，绝不重新下载覆盖正在安装的文件
        if (out.isFile() && out.length() > 10240) {
            return out;
        }
        synchronized (AppUpdater.class) {
            // 清理旧版本残留与本版本残留的半成品
            File[] olds = dir.listFiles((d, n) -> n.startsWith("update-v")
                    && !n.equals(out.getName()));
            if (olds != null) {
                for (File f : olds) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }

            File tmp = new File(dir, out.getName() + ".part");
            InputStream in = null;
            FileOutputStream fos = null;
            try {
                HttpURLConnection conn = openWithRedirects(info.apkUrl);
                if (conn == null) {
                    return null;
                }
                in = conn.getInputStream();
                fos = new FileOutputStream(tmp);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
                if (tmp.length() < 10240) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return null;
                }
                // 原子改名：安装器要么看到完整的旧文件，要么看到完整的新文件
                if (out.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
                if (tmp.renameTo(out)) {
                    return out;
                }
                return tmp; // 改名失败（极少见）也返回完整文件
            } catch (Exception e) {
                Log.w(TAG, "下载 APK 失败", e);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
                try {
                    if (fos != null) fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 记录已下载待安装的升级包（供下次打开时提示安装）。 */
    public static void savePending(Context context, UpdateInfo info, File apk) {
        prefs(context).edit()
                .putString(PREF_PENDING_APK, apk.getAbsolutePath())
                .putString(PREF_PENDING_VER, info.versionName)
                .putInt(PREF_PENDING_CODE, info.versionCode)
                .apply();
    }

    /** 取出待安装 APK（文件真实存在才有效，否则 null）。 */
    public static File getPendingApk(Context context) {
        String path = prefs(context).getString(PREF_PENDING_APK, null);
        if (path == null) {
            return null;
        }
        File f = new File(path);
        return (f.isFile() && f.length() > 10240) ? f : null;
    }

    public static String getPendingVersion(Context context) {
        return prefs(context).getString(PREF_PENDING_VER, "");
    }

    /**
     * 待安装包是否仍是"比当前已装版本更新"的版本。
     * 兼容旧数据：老版本未记录 versionCode，则按版本号字符串比对。
     */
    public static boolean isPendingNewer(Context context) {
        int code = prefs(context).getInt(PREF_PENDING_CODE, -1);
        if (code >= 0) {
            return code > currentVersionCode(context);
        }
        String ver = getPendingVersion(context);
        return !ver.isEmpty() && !ver.equals(currentVersionName(context));
    }

    /** 当前已安装版本名（异常时返回空串）。 */
    public static String currentVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 用户点了"稍后再说"：记录版本与时间。 */
    public static void snooze(Context context, String versionName) {
        prefs(context).edit()
                .putString(PREF_SNOOZE_VER, versionName)
                .putLong(PREF_SNOOZE_AT, System.currentTimeMillis())
                .apply();
    }

    /** 是否处于"稍后再说"免打扰期（同版本 24 小时内）。 */
    public static boolean isSnoozed(Context context, String versionName) {
        SharedPreferences p = prefs(context);
        long at = p.getLong(PREF_SNOOZE_AT, 0);
        String ver = p.getString(PREF_SNOOZE_VER, "");
        return ver.equals(versionName)
                && System.currentTimeMillis() - at < 24 * 60 * 60 * 1000L;
    }

    /** 清除待安装状态与已下载文件。 */
    public static void clearPending(Context context) {
        File f = getPendingApk(context);
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        prefs(context).edit()
                .remove(PREF_PENDING_APK)
                .remove(PREF_PENDING_VER)
                .remove(PREF_PENDING_CODE)
                .remove(PREF_PENDING_INSTALL)
                .apply();
    }

    /**
     * 发起安装。若系统禁止"安装未知应用"，先跳授权设置并记录状态，
     * 用户返回后由 MainActivity.onResume 自动继续安装。
     * 若调起安装器失败（部分 ROM 拦截），降级打开浏览器下载页手动安装。
     */
    public static void installApk(Activity activity, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                prefs(activity).edit().putBoolean(PREF_PENDING_INSTALL, true).apply();
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(s);
                Toast.makeText(activity, "请允许银盾安装应用，返回后将自动继续安装", Toast.LENGTH_LONG).show();
                return;
            }
            // 小米/红米手机：安装未备案应用会出现「应用备案信息校验」页，要求手动输入包名。
            // 提前把包名亮出来，用户照抄输入即可（不提示的话长辈根本无法完成安装）。
            String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
            if (maker.contains("xiaomi") || maker.contains("redmi")) {
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("安装小提示")
                        .setMessage("接下来安装时，若手机出现「应用备案信息校验」页面要求输入包名，"
                                + "请输入下面这行小写字母：\n\n"
                                + activity.getPackageName()
                                + "\n\n输入后点「确认」即可继续安装。")
                        .setPositiveButton("知道了，继续安装",
                                (d, w) -> launchInstaller(activity, apk))
                        .setNegativeButton("稍后再说", null)
                        .show();
                return;
            }
            launchInstaller(activity, apk);
        } catch (Exception e) {
            Log.w(TAG, "唤起安装失败，降级打开下载页", e);
            // 降级：打开 Gitee 下载页，用户下载后手动安装（系统文件管理器安装不受此限制）
            try {
                Intent web = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://gitee.com/stcbro/SilverShield/releases"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(web);
                Toast.makeText(activity, "已打开下载页，请下载最新版后点击安装", Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(activity, "唤起安装失败，请重试或联系开发者", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 真正调起系统安装器。 */
    private static void launchInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    "com.silvershield.app.fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "唤起安装器失败", e);
            Toast.makeText(activity, "唤起安装失败，请重试", Toast.LENGTH_LONG).show();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String httpGet(String urlStr) {
        try {
            HttpURLConnection conn = openWithRedirects(urlStr);
            if (conn == null) {
                return null;
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET 失败: " + urlStr, e);
            return null;
        }
    }

    /** 打开连接并手动跟随重定向（Gitee raw 会 302 跳到 giteeusercontent 域名）。 */
    private static HttpURLConnection openWithRedirects(String urlStr) {
        try {
            String u = urlStr;
            for (int i = 0; i < 5; i++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "SilverShield-Updater");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        return null;
                    }
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code == 200) {
                    return conn;
                }
                conn.disconnect();
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "连接失败: " + urlStr, e);
        }
        return null;
    }
}
