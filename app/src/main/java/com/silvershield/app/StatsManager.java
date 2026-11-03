package com.silvershield.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拦截统计：把每次成功跳过的事件（包名 + 时间）记录到内部存储，
 * 供「拦截统计」页面按应用聚合、分享给子女。
 *
 * 写入走单线程 Executor，避免并发写文件损坏；读取时整体读回 JSON 数组。
 */
public class StatsManager {

    private static final String FILE = "stats.json";
    private final File file;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public StatsManager(Context ctx) {
        this.file = new File(ctx.getFilesDir(), FILE);
    }

    /** 记录一次拦截（异步写，不阻塞无障碍回调）。 */
    public void record(String packageName) {
        record(packageName, false);
    }

    /**
     * 记录一次拦截（含行为分类，v1.4.0）。
     * @param block true=拦截弹窗（点×关闭），false=跳过开屏/横幅（点跳过）
     */
    public void record(String packageName, boolean block) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                List<JSONObject> list = readAll();
                JSONObject e = new JSONObject();
                e.put("pkg", packageName);
                e.put("ts", System.currentTimeMillis());
                if (block) {
                    e.put("kind", "block");
                }
                list.add(e);
                // 仅保留最近 5000 条，避免无限增长
                if (list.size() > 5000) {
                    list = new ArrayList<>(list.subList(list.size() - 5000, list.size()));
                }
                writeAll(list);
            } catch (Exception ignored) {
                // 统计失败不影响主功能
            }
        });
    }

    /** 清空全部统计记录（异步重置文件，用于用户手动清零）。 */
    public void clearAll() {
        executor.execute(() -> {
            try {
                writeAll(new ArrayList<>());
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 单次读取全部记录到内存（用于页面渲染，避免重复 IO）。
     * v1.5.11：统计页每秒刷新原会触发 4 次整文件读盘 + JSON 解析，
     * 改为页面内只调一次本方法，再在内存里算今日/累计/排行/最近。
     */
    public List<JSONObject> listAll() {
        return readAll();
    }

    public int total(List<JSONObject> all) {
        return all.size();
    }

    /** 返回今天（本地零点之后）的拦截次数。 */
    public int todayTotal(List<JSONObject> all) {
        long midnight = midnightToday();
        int n = 0;
        for (JSONObject e : all) {
            if (e.optLong("ts", 0) >= midnight) {
                n++;
            }
        }
        return n;
    }

    private long midnightToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 返回按应用聚合的计数（降序），最多 topN。 */
    public List<Map.Entry<String, Integer>> topApps(List<JSONObject> all, int topN) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (JSONObject e : all) {
            String pkg = e.optString("pkg", "未知");
            map.put(pkg, map.getOrDefault(pkg, 0) + 1);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
        if (entries.size() > topN) {
            entries = new ArrayList<>(entries.subList(0, topN));
        }
        return entries;
    }

    /** 返回最近 n 条拦截记录（按时间倒序），每条含 pkg 与 ts。 */
    public List<JSONObject> recent(List<JSONObject> all, int n) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    private List<JSONObject> readAll() {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = fis.read(buf);
            if (n <= 0) {
                return new ArrayList<>();
            }
            String s = new String(buf, 0, n, StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(s);
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getJSONObject(i));
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeAll(List<JSONObject> list) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) {
                arr.put(o);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 写入失败忽略
        }
    }
}
