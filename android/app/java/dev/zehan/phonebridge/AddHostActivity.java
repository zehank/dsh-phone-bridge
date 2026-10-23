package dev.zehan.phonebridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.UUID;

/** 加一台电脑：填 Tailscale 地址 + 电脑上显示的 6 位配对码。 */
public class AddHostActivity extends Activity {
    private EditText urlBox;
    private EditText codeBox;
    private TextView tip;
    private Button go;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b1220"));
        int p = dp(20);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("添加设备");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#e8eefc"));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("电脑上打开 http://127.0.0.1:8791 的管理页，上面有「手机地址」和 6 位配对码。");
        hint.setTextSize(12.5f);
        hint.setTextColor(Color.parseColor("#8b9bb4"));
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        urlBox = new EditText(this);
        urlBox.setHint("https://xxx.tailxxxx.ts.net");
        urlBox.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setTextColor(Color.parseColor("#e8eefc"));
        urlBox.setHintTextColor(Color.parseColor("#63718a"));
        root.addView(urlBox);

        codeBox = new EditText(this);
        codeBox.setHint("6 位配对码");
        codeBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeBox.setTextSize(22);
        codeBox.setTextColor(Color.parseColor("#e8eefc"));
        codeBox.setHintTextColor(Color.parseColor("#63718a"));
        codeBox.setPadding(dp(4), dp(14), dp(4), dp(14));
        root.addView(codeBox);

        Button scan = new Button(this);
        scan.setText("扫电脑上的二维码");
        scan.setAllCaps(false);
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivityForResult(new Intent(AddHostActivity.this, QrScanActivity.class), 21);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, 0, 0, dp(10));
        root.addView(scan, slp);

        tip = new TextView(this);
        tip.setTextColor(Color.parseColor("#ef4444"));
        tip.setTextSize(13);
        tip.setPadding(0, dp(8), 0, dp(8));
        root.addView(tip);

        go = new Button(this);
        go.setText("连接");
        go.setAllCaps(false);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                connect();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        root.addView(go, lp);
        return root;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 21 || res != RESULT_OK || data == null) return;
        String text = data.getStringExtra("text");
        if (text == null) return;
        applyQr(text);
    }

    /** 二维码里可能是 https://host/#pair=123456、pb://add?u=...&c=...，也可能只是一条裸地址 */
    private void applyQr(String text) {
        String raw = text.trim();
        String url = raw;
        String code = "";
        if (raw.startsWith("pb://")) {
            int q = raw.indexOf('?');
            String qs = q > 0 ? raw.substring(q + 1) : "";
            url = param(qs, "u");
            code = param(qs, "c");
            if (url.isEmpty()) url = param(qs, "url");
        } else {
            int hash = raw.indexOf('#');
            if (hash > 0) {
                code = param(raw.substring(hash + 1), "pair");
                url = raw.substring(0, hash);
            } else if (raw.contains("pair=")) {
                int q = raw.indexOf('?');
                if (q > 0) {
                    code = param(raw.substring(q + 1), "pair");
                    url = raw.substring(0, q);
                }
            }
        }
        urlBox.setText(Host.normalize(url));
        codeBox.setText(code);
        if (code.length() == 6) {
            connect();
        } else {
            tip.setText("扫到了地址，配对码还得在电脑上填一下");
        }
    }

    private static String param(String query, String key) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq > 0 ? part.substring(0, eq) : part;
            if (!k.equals(key)) continue;
            String v = eq > 0 ? part.substring(eq + 1) : "";
            try {
                return java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return "";
    }

    private void connect() {
        final String url = Host.normalize(urlBox.getText().toString());
        final String code = codeBox.getText().toString().trim();
        if (url.isEmpty()) {
            tip.setText("先填地址");
            return;
        }
        if (code.length() != 6) {
            tip.setText("配对码是 6 位数字");
            return;
        }
        tip.setText("");
        go.setEnabled(false);
        go.setText("连接中…");

        new Thread(new Runnable() {
            public void run() {
                String error = null;
                String token = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("code", code);
                    body.put("name", "Android " + android.os.Build.MODEL);
                    String res = Http.post(url + "/api/pair", null, body.toString());
                    JSONObject o = new JSONObject(res);
                    if (!o.optBoolean("ok", false)) error = o.optString("error", "配对失败");
                    else token = o.optString("token", "");
                } catch (Exception e) {
                    error = "连不上：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                final String fToken = token;
                final String fError = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        go.setEnabled(true);
                        go.setText("连接");
                        if (fError != null || fToken == null || fToken.isEmpty()) {
                            tip.setText(fError == null ? "配对码不对" : fError);
                            return;
                        }
                        String name = shortName(url);
                        Host h = new Host(UUID.randomUUID().toString(), name, url, fToken,
                                System.currentTimeMillis());
                        Hosts.upsert(AddHostActivity.this, h);
                        startService(new Intent(AddHostActivity.this, LinkService.class)
                                .setAction(LinkService.ACTION_RELOAD));
                        Toast.makeText(AddHostActivity.this, "已添加 " + name, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
        }).start();
    }

    private static String shortName(String url) {
        String s = url.replace("https://", "").replace("http://", "");
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.isEmpty() ? "电脑" : s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
