package com.silvershield.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则自动更新：从 DEFAULT_RULES_URL 拉取最新规则库，与内置规则合并后存到内部存储。
 *
 * 设计要点：
 *  - 不提供自定义 URL；更新源固定为项目自身 Gitee 仓库（换仓库时改 DEFAULT_RULES_URL 即可）。
 *  - 以内容 MD5 比对是否变化，避免无谓重写。
 *  - 合并策略：内置规则 + 远程规则，key（包名 hashCode）冲突时远程优先。
 *  - 产出文件 rules_merged.json 供无障碍服务加载（数组格式）。
 *
 * 本类完全使用标准 java.net，不引入额外依赖。
 */
public class UpdateManager {

    /** ★ 已指向作者 Gitee 仓库；换仓库时把下方地址改成自己的 Gitee / GitHub raw 地址即可。 */
    public static final String DEFAULT_RULES_URL =
            "https://gitee.com/stcbro/SilverShield/raw/master/app/src/main/assets/AllRules.json";

    public static final String ACTION_RULES_UPDATED = "com.silvershield.app.ACTION_RULES_UPDATED";

    private static final String MERGED_FILE = "rules_merged.json";
    private static final String HASH_FILE = "rules_hash.txt";

    private final Context ctx;

    public UpdateManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public File getMergedFile() {
        return new File(ctx.getFilesDir(), MERGED_FILE);
    }

    /**
     * 拉取远程规则并与内置规则合并、落盘。
     * @return 远程规则条数（>0 表示本次有新内容写入）；0 表示已是最新；-1 表示网络/解析失败。
     */
    public int checkAndUpdate() {
        try {
            String remote = download(DEFAULT_RULES_URL);
            if (remote == null || remote.trim().isEmpty()) {
                return -1;
            }
            String hash = md5(remote);
            String prevHash = readHash();
            if (hash.equals(prevHash)) {
                return 0; // 已是最新
            }

            Map<String, String> merged = new LinkedHashMap<>();
            // 1) 先放内置规则
            try (InputStream in = ctx.getAssets().open("AllRules.json")) {
                putAll(merged, readStream(in));
            } catch (Exception ignored) {
                // 内置资产缺失时仅用远程
            }
            // 2) 远程覆盖（远程优先）
            putAll(merged, remote);

            String mergedText = toArrayText(merged);
            saveFile(getMergedFile(), mergedText);
            saveHash(hash);

            // 远程规则条数（用于提示）
            JSONArray ra = new JSONArray(remote.trim());
            return ra.length();
        } catch (Exception e) {
            Log.w("SilverShield", "规则更新失败: " + e.getMessage());
            return -1;
        }
    }

    private void putAll(Map<String, String> map, String content) {
        if (content == null) {
            return;
        }
        content = content.trim();
        if (content.isEmpty()) {
            return;
        }
        try {
            if (content.startsWith("[")) {
                JSONArray arr = new JSONArray(content);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    Iterator<String> it = obj.keys();
                    while (it.hasNext()) {
                        String key = it.next();
                        Object val = obj.get(key);
                        if (val != null) {
                            map.put(key, val instanceof String ? (String) val : val.toString());
                        }
                    }
                }
            } else if (content.startsWith("{")) {
                JSONObject obj = new JSONObject(content);
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    Object val = obj.get(key);
                    if (val != null) {
                        map.put(key, val instanceof String ? (String) val : val.toString());
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败跳过该来源
        }
    }

    private String toArrayText(Map<String, String> map) {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : map.entrySet()) {
            JSONObject o = new JSONObject();
            try {
                o.put(e.getKey(), e.getValue());
                arr.put(o);
            } catch (Exception ignored) {
                // 单条写入失败跳过
            }
        }
        return arr.toString();
    }

    private String download(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            return readStream(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readStream(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private void saveFile(File f, String text) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void saveHash(String hash) {
        try (FileOutputStream os = new FileOutputStream(new File(ctx.getFilesDir(), HASH_FILE))) {
            os.write(hash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String readHash() {
        File f = new File(ctx.getFilesDir(), HASH_FILE);
        if (!f.exists()) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = fis.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
