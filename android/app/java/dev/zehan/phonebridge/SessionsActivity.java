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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 会话列表：顺序 = 电脑上的顺序（置顶优先，其余按最近活动），
 * 支持新建会话、长按置顶/取消置顶、点一下切过去。
 */
public class SessionsActivity extends Activity {
    private static final int COL_BG = 0xFF0B1220;
    private static final int COL_BG2 = 0xFF0F1830;
    private static final int COL_CARD = 0xFF141F36;
    private static final int COL_LINE = 0xFF22314F;
    private static final int COL_TEXT = 0xFFE8EEFC;
    private static final int COL_MUTED = 0xFF8B9BB4;
    private static final int COL_DIM = 0xFF63718A;
    private static final int COL_ACCENT = 0xFF4F8CFF;
    private static final int COL_OK = 0xFF34D399;

    private Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<Row>();
    private final List<String> pinned = new ArrayList<String>();
    private ListView list;
    private RowAdapter adapter;
    private TextView head;
    private boolean busy;
    private boolean destroyed;

    private static class Row {
        String id;
        String label;
        boolean running;
        boolean cold;
        long at;
        boolean pinned;
    }

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
            if (!destroyed) ui.postDelayed(this, 2500);
        }
    };

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
        TextView t = new TextView(this);
        t.setText("会话");
        t.setTextSize(16);
        t.setTextColor(COL_TEXT);
        titles.addView(t);
        head = new TextView(this);
        head.setText(host.name);
        head.setTextSize(11.5f);
        head.setTextColor(COL_DIM);
        titles.addView(head);
        bar.addView(titles);

        TextView add = new TextView(this);
        add.setText("＋ 新建");
        add.setTextSize(13);
        add.setTextColor(COL_TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1D3358);
        g.setCornerRadius(dp(18));
        g.setStroke(1, 0xFF2A4C86);
        add.setBackground(g);
        add.setPadding(dp(12), dp(7), dp(12), dp(7));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                newSession();
            }
        });
        bar.addView(add);
        root.addView(bar);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(COL_BG);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                switchTo(rows.get(i).id);
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                askPin(rows.get(i));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView tip = new TextView(this);
        tip.setText("长按可以置顶 / 取消置顶");
        tip.setTextSize(11);
        tip.setTextColor(COL_DIM);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(6), 0, dp(10));
        root.addView(tip);
        return root;
    }

    private void fetch() {
        if (busy || destroyed) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                JSONObject o = null;
                try {
                    o = new JSONObject(Http.get(host.api("/api/state?t=" + enc(host.token)), host.token));
                } catch (Exception ignored) {
                }
                final JSONObject fo = o;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (destroyed || fo == null) return;
                        apply(fo);
                    }
                });
            }
        }).start();
    }

    private void apply(JSONObject o) {
        JSONArray ps = o.optJSONArray("pinned");
        pinned.clear();
        if (ps != null) for (int i = 0; i < ps.length(); i++) pinned.add(ps.optString(i));

        String target = o.optString("target", "");
        JSONArray arr = o.optJSONArray("sessions");
        rows.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                Row r = new Row();
                r.id = s.optString("id");
                r.label = s.optString("label");
                r.running = s.optBoolean("running");
                r.cold = s.optBoolean("cold");
                r.at = s.optLong("lastActivity", 0);
                r.pinned = pinned.contains(r.id);
                rows.add(r);
            }
        }
        head.setText(host.name + " · " + rows.size() + " 个会话" + (target.isEmpty() ? "" : " · 当前有选中"));
        adapter.notifyDataSetChanged();
    }

    private void askPin(final Row r) {
        final boolean on = !r.pinned;
        new AlertDialog.Builder(this)
                .setTitle(r.label)
                .setItems(new String[]{on ? "置顶" : "取消置顶"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        pin(r.id, on);
                    }
                })
                .show();
    }

    private void pin(final String id, final boolean on) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("sessionId", id);
                    b.put("pinned", on);
                    Http.post(host.api("/api/pin"), host.token, b.toString());
                } catch (Exception e) {
                    toast("置顶失败：" + e.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        fetch();
                    }
                });
            }
        }).start();
    }

    private void switchTo(final String id) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("sessionId", id);
                    Http.post(host.api("/api/target"), host.token, b.toString());
                } catch (Exception ignored) {
                }
                ui.post(new Runnable() {
                    public void run() {
                        finish();
                    }
                });
            }
        }).start();
    }

    private void newSession() {
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    JSONObject r = new JSONObject(Http.post(host.api("/api/session"), host.token, "{}"));
                    if (!r.optBoolean("ok", false)) err = r.optString("error", "新建失败");
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fe != null) toast("新建失败：" + fe);
                        else finish();
                    }
                });
            }
        }).start();
    }

    private void toast(final String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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

    private static String when(long t) {
        if (t <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int i) {
            return rows.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View reuse, ViewGroup parent) {
            Row r = rows.get(i);
            LinearLayout box = new LinearLayout(SessionsActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable g = new GradientDrawable();
            g.setColor(COL_CARD);
            g.setCornerRadius(dp(12));
            g.setStroke(1, r.running ? 0xFF2A4C86 : COL_LINE);
            box.setBackground(g);
            box.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(10), dp(4), dp(10), dp(4));
            box.setLayoutParams(lp);

            TextView name = new TextView(SessionsActivity.this);
            name.setText((r.pinned ? "📌 " : "") + (r.label == null || r.label.isEmpty() ? "未命名会话" : r.label));
            name.setTextSize(15);
            name.setTextColor(COL_TEXT);
            name.setSingleLine(true);
            box.addView(name);

            TextView meta = new TextView(SessionsActivity.this);
            String state = r.running ? "运行中" : (r.cold ? "历史" : "空闲");
            meta.setText(state + (r.at > 0 ? " · " + when(r.at) : ""));
            meta.setTextSize(11.5f);
            meta.setTextColor(r.running ? COL_ACCENT : (r.cold ? COL_DIM : COL_OK));
            meta.setPadding(0, dp(2), 0, 0);
            box.addView(meta);
            return box;
        }
    }
}
