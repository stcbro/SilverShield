package com.silvershield.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 银盾规则模型。
 *
 * 社区规则库中每条规则的 JSON 字符串形如：
 * {"popup_rules":[{"id":"青少年模式","action":"我知道了"}],"click_way_popup":1}
 *
 * 说明：
 *  - popup_rules：关闭应用内弹窗的规则列表。
 *      id     = 触发条件（界面上出现的文案 / 资源ID，支持 +/-/=/& 前缀与模糊匹配）
 *      action = 要点击的目标（文案 / 资源ID / "GLOBAL_ACTION_BACK" 返回键 / "x,y,x2,y2" 坐标）
 *  - keywords / keywords_append：开屏广告跳过关键词（按包名覆盖默认词）。
 *  - 其余字段为内部开关，本应用解析但宽松处理。
 */
public class Rule {

    public static class PopupRule {
        public String id;     // 触发文案 / 资源ID
        public String action; // 点击目标 / 返回键 / 坐标
    }

    public final List<PopupRule> popupRules = new ArrayList<>();
    public int clickWayPopup = 0;
    public int unitePopupRules = 0;
    public int lttService = 0;
    public int searchTimesPopup = 0;
    public int times = 0;
    public int action = 0;

    public final List<String> keywords = new ArrayList<>();
    public final List<String> keywordsAppend = new ArrayList<>();
    public String packageName;
    public final List<String> activityList = new ArrayList<>();
    public int minStartDelay = 0;

    /** 从规则 JSON 字符串解析为 Rule 对象（容错：解析失败返回空规则）。 */
    public static Rule fromJson(String json) {
        Rule r = new Rule();
        if (json == null || json.isEmpty()) {
            return r;
        }
        try {
            JSONObject o = new JSONObject(json);

            if (o.has("popup_rules")) {
                JSONArray arr = o.getJSONArray("popup_rules");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject pr = arr.getJSONObject(i);
                    PopupRule p = new PopupRule();
                    p.id = pr.optString("id", null);
                    p.action = pr.optString("action", null);
                    r.popupRules.add(p);
                }
            }

            r.clickWayPopup = o.optInt("click_way_popup", 0);
            r.unitePopupRules = o.optInt("unite_popup_rules", 0);
            r.lttService = o.optInt("ltt_service", 0);
            r.searchTimesPopup = o.optInt("search_times_popup", 0);
            r.times = o.optInt("times", 0);
            r.action = o.optInt("action", 0);

            if (o.has("keywords")) {
                JSONArray k = o.getJSONArray("keywords");
                for (int i = 0; i < k.length(); i++) {
                    r.keywords.add(k.optString(i));
                }
            }
            if (o.has("keywords_append")) {
                JSONArray k = o.getJSONArray("keywords_append");
                for (int i = 0; i < k.length(); i++) {
                    r.keywordsAppend.add(k.optString(i));
                }
            }
            if (o.has("package_name")) {
                r.packageName = o.optString("package_name");
            }
            if (o.has("activity_list")) {
                JSONArray a = o.getJSONArray("activity_list");
                for (int i = 0; i < a.length(); i++) {
                    r.activityList.add(a.optString(i));
                }
            }
            r.minStartDelay = o.optInt("min_start_delay", 0);
        } catch (Exception ignored) {
            // 单条规则损坏不影响整体
        }
        return r;
    }
}
