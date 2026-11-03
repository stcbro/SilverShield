# 如何贡献规则（CONTRIBUTING）

感谢你为“保护老人”出力！本项目规则分两层：

- **A 层**：社区维护的主流 App 规则（来自公开规则库），通过 `tools/merge_rules.js` 自动合并，无需手工改；
- **B 层**：本项目手写的“老人专属”规则，放在 `tools/b_layer.json`，**这是贡献的重点**。

## 新增 / 修正一条老人专属规则

1. 确认 App 的**真实包名**（务必准确，否则 `hashCode` 不匹配、规则失效）。常见确认方式：在应用商店详情页网址末尾、或 `adb shell pm list packages | grep 关键词` 查看。
2. 在 `tools/b_layer.json` 数组里追加一项：

```json
{
  "pkg": "com.example.app",
  "name": "示例App",
  "note": "说明它为何对老人不友好（开屏长广告 / 诱导下载 / 伪系统通知等）",
  "rule": {
    "popup_rules": [
      { "id": "+立即升级", "action": "+以后再说" },
      { "id": "+开通会员", "action": "+放弃" }
    ],
    "keywords": ["跳过", "跳过广告", "关闭广告"],
    "package_name": "com.example.app",
    "min_start_delay": 0
  }
}
```

字段说明：
- `popup_rules[].id`：界面上出现的**触发文案/资源ID**，前缀 `+` 表示“以此开头”，`-` 结尾，`=` 全等，默认包含；
- `popup_rules[].action`：要点击的目标（文案 / 资源ID / `GLOBAL_ACTION_BACK` 返回键 / `x,y,x2,y2` 坐标）；
- `keywords`：开屏跳过的额外关键词（全局默认已含“跳过/关闭广告”等）。

3. 运行合并脚本生成发布文件：

```bash
node tools/merge_rules.js
```

4. 提交 PR，说明 App 名称、包名、验证机型。**请勿提交任何会误关支付/拨号确认框的规则**（这类界面已在 App 内受“紧急保护”豁免，无需规则）。

## 规则原则

- 只点“跳过 / 关闭”类按钮，不点“领取 / 开通 / 立即体验”等营销按钮；
- 包名必须真实可验证；
- 不针对系统级锁屏广告（超出应用权限，请在投诉渠道反映）。
