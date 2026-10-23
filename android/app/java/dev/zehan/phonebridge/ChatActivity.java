package dev.zehan.phonebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一台电脑的遥控界面（原生 UI）。对齐手机页：
 * 会话切换 / 新建 / 置顶、模型 / 权限 / 模式 / 工作区、定时任务、用量、
 * 气泡消息（思考过程可折叠）、发送 / 中断、待批准卡片、提问选项、🔔 跑完叫我。
 */
public class ChatActivity extends Activity {
    private static final int COL_BG = 0xFF0B1220;
    private static final int COL_BG2 = 0xFF0F1830;
    private static final int COL_CARD = 0xFF141F36;
    private static final int COL_LINE = 0xFF22314F;
    private static final int COL_TEXT = 0xFFE8EEFC;
    private static final int COL_MUTED = 0xFF8B9BB4;
    private static final int COL_DIM = 0xFF63718A;
    private static final int COL_ACCENT = 0xFF4F8CFF;
    private static final int COL_ACCENT_SOFT = 0xFF1D3358;
    private static final int COL_OK = 0xFF34D399;
    private static final int COL_BAD = 0xFFEF4444;

    private Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Msg> msgs = new ArrayList<Msg>();
    private final Set<Integer> expanded = new HashSet<Integer>();
    private final Map<String, Set<String>> questionSel = new HashMap<String, Set<String>>();

    private ListView list;
    private MsgAdapter adapter;
    private TextView titleView;
    private TextView subView;
    private TextView statusView;
    private TextView alarmChip;
    private TextView stopChip;
    private LinearLayout pendingBox;
    private EditText input;

    private JSONObject state;
    private String targetId = "";
    private String lastSig = "";
    private boolean busy;
    private boolean destroyed;

    private static class Msg {
        String role;
        String text;
        long time;
        String ctx;
    }

    /* ── 生命周期 ─────────────────────────────────────────── */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        host = Hosts.byId(this, getIntent().getStringExtra("hostId"));
        if (host == null) {
            finish();
            return;
        }
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinkService.ensure(this);
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        super.onDestroy();
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            fetch();
            if (!destroyed) ui.postDelayed(this, 2000);
        }
    };

    /* ── UI ───────────────────────────────────────────────── */

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(COL_BG2);
        bar.setPadding(dp(6), dp(10), dp(10), dp(10));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(COL_MUTED);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(8), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleView = new TextView(this);
        titleView.setText(host.name);
        titleView.setTextSize(16);
        titleView.setTextColor(COL_TEXT);
        titleView.setSingleLine(true);
        titles.addView(titleView);
        subView = new TextView(this);
        subView.setText("连接中…");
        subView.setTextSize(11.5f);
        subView.setTextColor(COL_DIM);
        subView.setSingleLine(true);
        titles.addView(subView);
        titles.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ChatActivity.this, SessionsActivity.class)
                        .putExtra("hostId", host.id));
            }
        });
        bar.addView(titles);

        alarmChip = chip("🔔");
        alarmChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleAlarm();
            }
        });
        bar.addView(alarmChip);

        stopChip = chip("中断");
        stopChip.setVisibility(View.GONE);
        stopChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopTurn();
            }
        });
        bar.addView(stopChip);

        TextView more = chip("⋯");
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openMenu();
            }
        });
        bar.addView(more);
        root.addView(bar);

        pendingBox = new LinearLayout(this);
        pendingBox.setOrientation(LinearLayout.VERTICAL);
        pendingBox.setPadding(dp(12), dp(8), dp(12), 0);
        pendingBox.setVisibility(View.GONE);
        root.addView(pendingBox);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(COL_BG);
        list.setPadding(dp(12), dp(6), dp(12), dp(6));
        list.setClipToPadding(false);
        adapter = new MsgAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setTextSize(11.5f);
        statusView.setTextColor(COL_DIM);
        statusView.setPadding(dp(14), 0, dp(14), dp(4));
        statusView.setText(" ");
        root.addView(statusView);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.BOTTOM);
        bottom.setBackgroundColor(COL_BG2);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(12));

        input = new EditText(this);
        input.setHint("给 DSH 下命令…");
        input.setTextColor(COL_TEXT);
        input.setHintTextColor(COL_DIM);
        input.setTextSize(15);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setColor(0xFF1A2742);
        inBg.setCornerRadius(dp(12));
        inBg.setStroke(1, COL_LINE);
        input.setBackground(inBg);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottom.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setAllCaps(false);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                send();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        bottom.addView(sendBtn, blp);
        root.addView(bottom);

        return root;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(COL_MUTED);
        GradientDrawable g = new GradientDrawable();
        g.setColor(COL_ACCENT_SOFT);
        g.setCornerRadius(dp(18));
        g.setStroke(1, 0xFF2A4C86);
        t.setBackground(g);
        t.setPadding(dp(9), dp(6), dp(9), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /* ── 拉状态 ───────────────────────────────────────────── */

    private void fetch() {
        if (busy || destroyed) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                JSONObject o = null;
                String err = null;
                try {
                    o = new JSONObject(Http.get(host.api("/api/state?t=" + enc(host.token)), host.token));
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final JSONObject fo = o;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (destroyed) return;
                        if (fo != null) applyState(fo);
                        else {
                            statusView.setText("离线 · " + fe);
                            subView.setText("连不上");
                            subView.setTextColor(COL_BAD);
                        }
                    }
                });
            }
        }).start();
    }

    private void applyState(JSONObject o) {
        state = o;
        targetId = o.optString("target", "");

        String label = "";
        boolean running = false;
        JSONArray sessions = o.optJSONArray("sessions");
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                if (s == null) continue;
                if (targetId.equals(s.optString("id"))) {
                    label = s.optString("label");
                    running = s.optBoolean("running");
                }
            }
        }
        titleView.setText(label.isEmpty() ? host.name : label);
        subView.setText((running ? "运行中 · " : "空闲 · ") + (sessions == null ? 0 : sessions.length()) + " 个会话");
        subView.setTextColor(running ? COL_ACCENT : COL_DIM);
        stopChip.setVisibility(running ? View.VISIBLE : View.GONE);

        JSONObject alarm = o.optJSONObject("alarm");
        boolean ringing = alarm != null && alarm.optBoolean("ringing");
        boolean armed = alarm != null && alarm.optBoolean("armed");
        String armedFor = alarm == null ? "" : alarm.optString("sessionId", "");
        if (ringing) {
            alarmChip.setText("⏰");
            alarmChip.setTextColor(0xFFFFD7DE);
        } else if (armed && armedFor.equals(targetId)) {
            alarmChip.setText("🔔✓");
            alarmChip.setTextColor(COL_OK);
        } else {
            alarmChip.setText("🔔");
            alarmChip.setTextColor(COL_MUTED);
        }

        renderPending(o.optJSONArray("pending"));

        JSONArray arr = o.optJSONArray("messages");
        int count = arr == null ? 0 : arr.length();
        long lastSeq = 0;
        if (count > 0) {
            JSONObject last = arr.optJSONObject(count - 1);
            if (last != null) lastSeq = last.optLong("seq", 0);
        }
        String sig = targetId + ":" + count + ":" + lastSeq;
        if (!sig.equals(lastSig)) {
            lastSig = sig;
            msgs.clear();
            for (int i = 0; i < count; i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                Msg x = new Msg();
                x.role = m.optString("role", "assistant");
                x.text = m.optString("text", "");
                x.time = m.optLong("time", 0);
                x.ctx = m.optString("ctx", "");
                if (x.text.isEmpty() && x.ctx.isEmpty()) continue;
                msgs.add(x);
            }
            expanded.clear();
            adapter.notifyDataSetChanged();
            if (count > 0) list.setSelection(adapter.getCount() - 1);
        }

        JSONObject stats = o.optJSONObject("stats");
        if (stats != null) {
            statusView.setText(stats.optInt("turns") + " 轮 · " + stats.optInt("steps") + " 步 · 入 "
                    + fmtTok(stats.optLong("input")) + " / 出 " + fmtTok(stats.optLong("output")));
        } else {
            statusView.setText(" ");
        }
    }

    private static String fmtTok(long v) {
        if (v >= 1000000) return String.format(Locale.US, "%.2fM", v / 1000000.0);
        if (v >= 1000) return String.format(Locale.US, "%.1fk", v / 1000.0);
        return String.valueOf(v);
    }

    /* ── 菜单 ─────────────────────────────────────────────── */

    private void openMenu() {
        final String[] items = {"切换 / 置顶会话", "新建会话", "模型", "权限", "模式", "工作区", "定时任务", "用量"};
        new AlertDialog.Builder(this)
                .setTitle(host.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        switch (i) {
                            case 0:
                                startActivity(new Intent(ChatActivity.this, SessionsActivity.class)
                                        .putExtra("hostId", host.id));
                                break;
                            case 1:
                                newSession();
                                break;
                            case 2:
                                pickModel();
                                break;
                            case 3:
                                pickPermission();
                                break;
                            case 4:
                                pickPreset();
                                break;
                            case 5:
                                pickWorkspace();
                                break;
                            case 6:
                                showSchedules();
                                break;
                            case 7:
                                showStats();
                                break;
                        }
                    }
                })
                .show();
    }

    private void newSession() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject r = new JSONObject(Http.post(host.api("/api/session"), host.token, "{}"));
                    if (!r.optBoolean("ok", false)) toast("新建失败：" + r.optString("error", ""));
                    else toast("已新建会话");
                } catch (Exception e) {
                    toast("新建失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        lastSig = "";
                        fetch();
                    }
                });
            }
        }).start();
    }

    private void pickModel() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject r = new JSONObject(Http.get(host.api("/api/models"), host.token));
                    if (!r.optBoolean("ok", false)) {
                        toast("取模型失败：" + r.optString("error", ""));
                        return;
                    }
                    JSONArray groups = r.optJSONArray("groups");
                    final List<String> labels = new ArrayList<String>();
                    final List<String[]> refs = new ArrayList<String[]>();
                    if (groups != null) {
                        for (int i = 0; i < groups.length(); i++) {
                            JSONObject g = groups.optJSONObject(i);
                            if (g == null) continue;
                            JSONArray models = g.optJSONArray("models");
                            if (models == null) continue;
                            for (int j = 0; j < models.length(); j++) {
                                JSONObject m = models.optJSONObject(j);
                                if (m == null) continue;
                                labels.add(g.optString("name", g.optString("id")) + " · " + m.optString("name", m.optString("id")));
                                refs.add(new String[]{g.optString("id"), m.optString("id")});
                            }
                        }
                    }
                    if (labels.isEmpty()) {
                        toast("没有可选模型");
                        return;
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(ChatActivity.this)
                                    .setTitle("模型")
                                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int i) {
                                            String[] ref = refs.get(i);
                                            postJson("/api/model", "{\"provider\":" + JSONObject.quote(ref[0])
                                                    + ",\"model\":" + JSONObject.quote(ref[1]) + "}", "切换模型");
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    toast("取模型失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void pickPermission() {
        JSONObject settings = state == null ? null : state.optJSONObject("settings");
        JSONObject perm = settings == null ? null : settings.optJSONObject("permission");
        if (perm == null) {
            toast("这台电脑没有权限预设");
            return;
        }
        JSONArray opts = perm.optJSONArray("options");
        final String[] names = new String[opts == null ? 0 : opts.length()];
        final String[] values = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            JSONObject o = opts.optJSONObject(i);
            names[i] = o == null ? "" : o.optString("name", o.optString("value"));
            values[i] = o == null ? "" : o.optString("value");
        }
        new AlertDialog.Builder(this)
                .setTitle("权限（当前 " + perm.optString("current", "?") + "）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        postJson("/api/permission", "{\"preset\":" + JSONObject.quote(values[i]) + "}", "切换权限");
                    }
                })
                .show();
    }

    private void pickPreset() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject r = new JSONObject(Http.get(host.api("/api/presets"), host.token));
                    if (!r.optBoolean("ok", false)) {
                        toast("取模式失败：" + r.optString("error", ""));
                        return;
                    }
                    JSONArray arr = r.optJSONArray("presets");
                    final List<String> labels = new ArrayList<String>();
                    final List<String> ids = new ArrayList<String>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject p = arr.optJSONObject(i);
                            if (p == null) continue;
                            labels.add(p.optString("name", p.optString("id")));
                            ids.add(p.optString("id"));
                        }
                    }
                    if (labels.isEmpty()) {
                        toast("没有可选模式");
                        return;
                    }
                    final String current = r.optString("current", "");
                    ui.post(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(ChatActivity.this)
                                    .setTitle("模式（当前 " + (current.isEmpty() ? "默认" : current) + "）")
                                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int i) {
                                            postJson("/api/preset", "{\"id\":" + JSONObject.quote(ids.get(i)) + "}", "切换模式");
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    toast("取模式失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void pickWorkspace() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject r = new JSONObject(Http.get(host.api("/api/workspaces"), host.token));
                    JSONArray arr = r.optJSONArray("list");
                    final List<String> labels = new ArrayList<String>();
                    final List<String> paths = new ArrayList<String>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject w = arr.optJSONObject(i);
                            if (w == null) continue;
                            labels.add(w.optString("title", w.optString("path")));
                            paths.add(w.optString("path"));
                        }
                    }
                    if (labels.isEmpty()) {
                        toast("没有可选工作区");
                        return;
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(ChatActivity.this)
                                    .setTitle("工作区（当前 " + r.optString("cwd", "") + "）")
                                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int i) {
                                            postJson("/api/cwd", "{\"path\":" + JSONObject.quote(paths.get(i)) + "}", "切换工作区");
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    toast("取工作区失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void showSchedules() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject r = new JSONObject(Http.get(host.api("/api/schedules"), host.token));
                    JSONArray arr = r.optJSONArray("list");
                    final StringBuilder sb = new StringBuilder();
                    if (arr == null || arr.length() == 0) sb.append("这台电脑上没有定时任务");
                    else {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            sb.append("· ").append(s.optString("title", "?")).append("\n   ")
                                    .append(s.optString("kind", "")).append("  ")
                                    .append(s.optString("prompt", "")).append("\n\n");
                        }
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(ChatActivity.this)
                                    .setTitle("定时任务")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("好", null)
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    toast("取定时任务失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void showStats() {
        JSONObject stats = state == null ? null : state.optJSONObject("stats");
        if (stats == null) {
            toast("还没有用量数据");
            return;
        }
        String text = stats.optInt("turns") + " 轮\n"
                + stats.optInt("steps") + " 步\n"
                + "输入 " + fmtTok(stats.optLong("input")) + "\n"
                + "输出 " + fmtTok(stats.optLong("output")) + "\n"
                + "缓存读 " + fmtTok(stats.optLong("cacheRead")) + "\n"
                + "缓存命中率 " + Math.round(stats.optDouble("cacheHitRate", 0) * 100) + "%\n"
                + "上下文 " + (stats.optInt("contextWindow") > 0
                ? Math.round(stats.optDouble("contextUsed", 0) * 100) + "%"
                : fmtTok(stats.optLong("lastInput")));
        new AlertDialog.Builder(this).setTitle("用量").setMessage(text).setPositiveButton("好", null).show();
    }

    private void postJson(final String path, final String body, final String what) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject r = new JSONObject(Http.post(host.api(path), host.token, body));
                    if (!r.optBoolean("ok", false)) toast(what + "失败：" + r.optString("error", ""));
                    else toast(what + "已生效");
                } catch (Exception e) {
                    toast(what + "失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        fetch();
                    }
                });
            }
        }).start();
    }

    /* ── 待批准 / 待回答 ──────────────────────────────────── */

    private void renderPending(JSONArray pending) {
        pendingBox.removeAllViews();
        if (pending == null || pending.length() == 0) {
            pendingBox.setVisibility(View.GONE);
            return;
        }
        pendingBox.setVisibility(View.VISIBLE);
        Map<String, String> labels = new HashMap<String, String>();
        JSONArray sessions = state == null ? null : state.optJSONArray("sessions");
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject one = sessions.optJSONObject(i);
                if (one == null) continue;
                labels.put(one.optString("id", ""), one.optString("label", ""));
            }
        }
        for (int i = 0; i < pending.length(); i++) {
            JSONObject p = pending.optJSONObject(i);
            if (p == null) continue;
            String sid = p.optString("sessionId", "");
            String label = labels.get(sid);
            if ("approval".equals(p.optString("kind"))) approvalCard(p, label);
            else questionCard(p, label);
        }
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(COL_CARD);
        g.setCornerRadius(dp(12));
        g.setStroke(1, 0xFF5A3B12);
        box.setBackground(g);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private void approvalCard(final JSONObject p, String sessionLabel) {
        LinearLayout box = card();
        TextView k = new TextView(this);
        k.setText("要你批准" + (sessionLabel == null || sessionLabel.isEmpty() ? "" : " · " + sessionLabel));
        k.setTextSize(11.5f);
        k.setTextColor(0xFFF59E0B);
        box.addView(k);
        TextView t = new TextView(this);
        t.setText(p.optString("tool", "工具"));
        t.setTextSize(15);
        t.setTextColor(COL_TEXT);
        t.setPadding(0, dp(2), 0, 0);
        box.addView(t);
        String reason = p.optString("reason", "");
        if (!reason.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(reason);
            d.setTextSize(12.5f);
            d.setTextColor(COL_MUTED);
            d.setPadding(0, dp(3), 0, 0);
            box.addView(d);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setAllCaps(false);
        deny.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                answerApproval(p.optString("id"), "deny");
            }
        });
        row.addView(deny, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button allow = new Button(this);
        allow.setText("允许一次");
        allow.setAllCaps(false);
        allow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                answerApproval(p.optString("id"), "allow");
            }
        });
        row.addView(allow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        pendingBox.addView(box);
    }

    private void questionCard(final JSONObject p, String sessionLabel) {
        LinearLayout box = card();
        TextView k = new TextView(this);
        k.setText("要你回答" + (sessionLabel == null || sessionLabel.isEmpty() ? "" : " · " + sessionLabel));
        k.setTextSize(11.5f);
        k.setTextColor(COL_ACCENT);
        box.addView(k);

        JSONArray qs = p.optJSONArray("questions");
        if (qs == null) {
            pendingBox.addView(box);
            return;
        }
        for (int qi = 0; qi < qs.length(); qi++) {
            final JSONObject q = qs.optJSONObject(qi);
            if (q == null) continue;
            TextView qt = new TextView(this);
            qt.setText(q.optString("question", ""));
            qt.setTextSize(15);
            qt.setTextColor(COL_TEXT);
            qt.setPadding(0, dp(4), 0, dp(4));
            box.addView(qt);
            JSONArray opts = q.optJSONArray("options");
            if (opts == null) continue;
            for (int oi = 0; oi < opts.length(); oi++) {
                JSONObject o = opts.optJSONObject(oi);
                if (o == null) continue;
                final String label = o.optString("label", "");
                Button b = new Button(this);
                b.setText(label);
                b.setAllCaps(false);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        selectOption(q, label);
                    }
                });
                box.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        Button submit = new Button(this);
        submit.setText("提交");
        submit.setAllCaps(false);
        submit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submitAnswers(p);
            }
        });
        box.addView(submit);
        pendingBox.addView(box);
    }

    private void selectOption(JSONObject q, String label) {
        String qid = q.optString("id", "");
        boolean multi = q.optBoolean("multiSelect");
        Set<String> sel = questionSel.get(qid);
        if (sel == null) {
            sel = new HashSet<String>();
            questionSel.put(qid, sel);
        }
        if (!multi) sel.clear();
        if (sel.contains(label)) sel.remove(label);
        else sel.add(label);
        toast("已选 " + sel);
    }

    private void submitAnswers(final JSONObject p) {
        JSONArray qs = p.optJSONArray("questions");
        final JSONArray answers = new JSONArray();
        if (qs != null) {
            for (int i = 0; i < qs.length(); i++) {
                JSONObject q = qs.optJSONObject(i);
                if (q == null) continue;
                Set<String> sel = questionSel.get(q.optString("id", ""));
                JSONArray selected = new JSONArray();
                if (sel != null) for (String s : sel) selected.put(s);
                try {
                    JSONObject one = new JSONObject();
                    one.put("id", q.optString("id", ""));
                    one.put("selected", selected);
                    answers.put(one);
                } catch (Exception ignored) {
                }
            }
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("id", p.optString("id"));
                    body.put("answers", answers);
                    Http.post(host.api("/api/answer"), host.token, body.toString());
                } catch (Exception e) {
                    toast("提交失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        questionSel.clear();
                        fetch();
                    }
                });
            }
        }).start();
    }

    /* ── 动作 ─────────────────────────────────────────────── */

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("text", text);
                    if (!targetId.isEmpty()) b.put("sessionId", targetId);
                    JSONObject r = new JSONObject(Http.post(host.api("/api/say"), host.token, b.toString()));
                    if (!r.optBoolean("ok", true)) toast("没发出去：" + r.optString("error", ""));
                } catch (Exception e) {
                    toast("发送失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        fetch();
                    }
                });
            }
        }).start();
    }

    private void stopTurn() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("sessionId", targetId);
                    Http.post(host.api("/api/stop"), host.token, b.toString());
                } catch (Exception ignored) {
                }
                ui.post(new Runnable() {
                    public void run() {
                        fetch();
                    }
                });
            }
        }).start();
    }

    private void answerApproval(final String id, final String action) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("id", id);
                    b.put("action", action);
                    Http.post(host.api("/api/answer"), host.token, b.toString());
                } catch (Exception e) {
                    toast("失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        fetch();
                    }
                });
            }
        }).start();
    }

    private void toggleAlarm() {
        final boolean ringing = state != null && state.optJSONObject("alarm") != null
                && state.optJSONObject("alarm").optBoolean("ringing");
        final boolean armed = state != null && state.optJSONObject("alarm") != null
                && state.optJSONObject("alarm").optBoolean("armed");
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (ringing) {
                        Http.post(host.api("/api/alarm/stop"), host.token, "{}");
                    } else if (armed) {
                        Http.post(host.api("/api/alarm/disarm"), host.token, "{}");
                    } else {
                        JSONObject b = new JSONObject();
                        b.put("sessionId", targetId);
                        Http.post(host.api("/api/alarm/arm"), host.token, b.toString());
                    }
                } catch (Exception e) {
                    toast("失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        fetch();
                    }
                });
            }
        }).start();
    }

    /* ── 工具 ─────────────────────────────────────────────── */

    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(ChatActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String hhmm(long t) {
        if (t <= 0) return "";
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t));
    }

    /* ── 消息气泡（含思考过程折叠） ───────────────────────── */

    private class MsgAdapter extends BaseAdapter {
        public int getCount() {
            return msgs.size();
        }

        public Object getItem(int i) {
            return msgs.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(final int i, View reuse, ViewGroup parent) {
            final Msg m = msgs.get(i);
            LinearLayout row = new LinearLayout(ChatActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            boolean mine = "user".equals(m.role);
            row.setGravity(mine ? Gravity.END : Gravity.START);

            if (!mine && m.ctx != null && !m.ctx.isEmpty()) {
                TextView toggle = new TextView(ChatActivity.this);
                toggle.setText(expanded.contains(i) ? "收起思考过程" : "思考过程");
                toggle.setTextSize(11.5f);
                toggle.setTextColor(COL_DIM);
                toggle.setPadding(dp(6), dp(2), dp(6), dp(2));
                toggle.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (expanded.contains(i)) expanded.remove(i);
                        else expanded.add(i);
                        notifyDataSetChanged();
                    }
                });
                row.addView(toggle);

                if (expanded.contains(i)) {
                    TextView box = new TextView(ChatActivity.this);
                    box.setText(m.ctx);
                    box.setTextSize(11.5f);
                    box.setTextColor(COL_DIM);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(COL_CARD);
                    bg.setCornerRadius(dp(10));
                    bg.setStroke(1, COL_LINE);
                    box.setBackground(bg);
                    box.setPadding(dp(10), dp(8), dp(10), dp(8));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(4);
                    lp.rightMargin = dp(28);
                    row.addView(box, lp);
                }
            }

            TextView bubble = new TextView(ChatActivity.this);
            bubble.setText(m.text);
            bubble.setTextSize(14.5f);
            bubble.setTextColor(COL_TEXT);
            bubble.setTextIsSelectable(true);
            bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            if (mine) {
                g.setColor(COL_ACCENT_SOFT);
                g.setStroke(1, 0xFF2A4C86);
            } else {
                g.setColor(COL_CARD);
                g.setStroke(1, COL_LINE);
            }
            bubble.setBackground(g);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (mine) blp.leftMargin = dp(28);
            else {
                blp.rightMargin = dp(28);
                blp.gravity = Gravity.START;
            }
            row.addView(bubble, blp);

            TextView time = new TextView(ChatActivity.this);
            time.setText(hhmm(m.time));
            time.setTextSize(10);
            time.setTextColor(COL_DIM);
            time.setGravity(mine ? Gravity.END : Gravity.START);
            row.addView(time);
            return row;
        }
    }
}
