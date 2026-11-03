package com.silvershield.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 规则管理器：加载规则库并以「包名 hashCode」为键提供查询。
 *
 * 同时兼容两种格式：
 *  1) 社区规则库格式（数组）： [ {"<hashCode>":"<规则JSON字符串>"}, ... ]
 *  2) 扁平对象格式：           { "<hashCode>": { <规则对象> }, ... }
 *
 * key 必须是 Java String.hashCode() 的结果（与 JVM 完全一致）。
 */
public class RulesManager {

    // key = 包名 hashCode 的字符串，value = 规则 JSON 字符串
    private final Map<String, String> rules = new HashMap<>();
    // v1.5.11：解析后的 Rule 对象缓存。每次扫描都按包名取规则，事件风暴下一屏可能
    // 解析同一规则 7 次以上，且服务常驻——缓存后命中直接返回，去掉持续的无谓 CPU。
    private final Map<String, Rule> ruleCache = new HashMap<>();

    public void clear() {
        rules.clear();
        ruleCache.clear();
    }

    /** 从 assets / 文件流加载规则库（可多次调用，后加载的 key 覆盖先加载的）。 */
    public void load(InputStream is) throws IOException {
        if (is == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        String content = sb.toString().trim();
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
                            putRule(key, val instanceof String ? (String) val : val.toString());
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
                        rules.put(key, val instanceof String ? (String) val : val.toString());
                    }
                }
            }
        } catch (Exception ignored) {
            // 格式异常时静默忽略，保证服务可用
        }
    }

    public int size() {
        return rules.size();
    }

    /** 写入原始规则字符串，并解析缓存为 Rule 对象（供 getRule 直接返回）。 */
    private void putRule(String key, String raw) {
        rules.put(key, raw);
        ruleCache.put(key, Rule.fromJson(raw));
    }

    /** 根据当前前台包名取规则；找不到返回 null。v1.5.11：直接返回缓存对象，不再每次重新解析。 */
    public Rule getRule(String packageName) {
        if (packageName == null) {
            return null;
        }
        String key = String.valueOf(hashCode(packageName));
        return ruleCache.get(key);
    }

    /**
     * 与 Java / Android 完全一致的 String.hashCode()。
     * 使用 int 溢出（31*h+c），结果与 JVM 相同，从而保证 key 与规则文件一致。
     */
    public static int hashCode(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }
}
