package com.silvershield.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 提交反馈页（v1.5.14）：
 *  - 用户自行填写没跳过的 App 名称（不自动列出守护过的 App）；
 *  - 支持问题描述；
 *  - 自动附带银盾版本、手机型号、系统版本，便于开发者定位并补充规则/修复；
 *  - 通过系统分享或复制把反馈内容发给开发者。
 */
public class FeedbackActivity extends AppCompatActivity {

    private EditText etManual;
    private EditText etDesc;
    private TextView tvDevice;
    private Button btnSend;
    private Button btnCopy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        etManual = findViewById(R.id.etManual);
        etDesc = findViewById(R.id.etDesc);
        tvDevice = findViewById(R.id.tvDevice);
        btnSend = findViewById(R.id.btnSend);
        btnCopy = findViewById(R.id.btnCopy);

        tvDevice.setText(buildDeviceInfo());

        btnSend.setOnClickListener(v -> sendFeedback());
        btnCopy.setOnClickListener(v -> copyFeedback());
    }

    /** 设备/版本信息（自动附带到反馈内容）。 */
    private String buildDeviceInfo() {
        return "银盾版本：v" + BuildConfig.VERSION_NAME
                + "（规则 " + countRules() + " 条）\n"
                + "手机型号：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统版本：Android " + Build.VERSION.RELEASE
                + "（SDK " + Build.VERSION.SDK_INT + "）";
    }

    /** 收集填写 + 描述，生成完整反馈文本。 */
    private String buildFeedbackText() {
        StringBuilder sb = new StringBuilder();
        sb.append("【银盾反馈】App 广告未被自动跳过\n")
                .append(buildDeviceInfo()).append("\n")
                .append("时间：").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date())).append("\n\n");

        sb.append("没跳过的应用：\n");
        String manual = etManual.getText().toString().trim();
        if (!manual.isEmpty()) {
            sb.append("· ").append(manual).append("\n");
        } else {
            sb.append("（用户未填写）\n");
        }

        sb.append("\n问题描述：\n");
        String desc = etDesc.getText().toString().trim();
        sb.append(desc.isEmpty() ? "（未填写）" : desc);
        return sb.toString();
    }

    private void sendFeedback() {
        String text = buildFeedbackText();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "银盾反馈：部分 App 广告未跳过");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "发送反馈"));
    }

    private void copyFeedback() {
        String text = buildFeedbackText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("银盾反馈", text));
            ToastUtil.showCenter(this, "反馈内容已复制");
        }
    }

    /** 统计内置规则条数（来自 MainActivity 同名逻辑）。 */
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
}
