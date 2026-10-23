package dev.zehan.phonebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 入口：这台手机上连着的所有电脑。点一台就进它的遥控界面。 */
public class MainActivity extends Activity {
    private final List<Host> hosts = new ArrayList<Host>();
    private ListView list;
    private TextView empty;
    private TextView banner;
    private HostAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            adapter.notifyDataSetChanged();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        askBattery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        LinkService.ensure(this);
        checkAlerts();
        ui.postDelayed(tick, 1500);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b1220"));

        TextView title = new TextView(this);
        title.setText("手机桥");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#e8eefc"));
        title.setPadding(dp(18), dp(22), dp(18), dp(2));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("一台手机上同时遥控多台电脑");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#8b9bb4"));
        sub.setPadding(dp(18), 0, dp(18), dp(10));
        root.addView(sub);

        banner = new TextView(this);
        banner.setTextSize(12.5f);
        banner.setTextColor(Color.parseColor("#ffd7de"));
        banner.setBackgroundColor(Color.parseColor("#3b1220"));
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openNotificationSettings();
            }
        });
        root.addView(banner);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(Color.parseColor("#0b1220"));
        adapter = new HostAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Host h = hosts.get(pos);
                Intent i = new Intent(MainActivity.this, ChatActivity.class);
                i.putExtra("hostId", h.id);
                startActivity(i);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                askActions(hosts.get(pos));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("还没有添加设备。\n在电脑上打开管理页看手机地址，\n然后点下面的按钮。");
        empty.setTextSize(13);
        empty.setTextColor(Color.parseColor("#63718a"));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), 0, dp(24), dp(10));
        root.addView(empty);

        Button add = new Button(this);
        add.setText("添加设备");
        add.setAllCaps(false);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AddHostActivity.class));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(14), dp(6), dp(14), dp(20));
        root.addView(add, lp);
        return root;
    }

    /** 国产 ROM 会把后台服务顺手杀掉，那样批准/响铃就全断了。只问一次。 */
    private void askBattery() {
        android.content.SharedPreferences sp = getSharedPreferences("pb", MODE_PRIVATE);
        if (sp.getBoolean("askedBattery", false)) return;
        sp.edit().putBoolean("askedBattery", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("别让系统杀掉手机桥")
                .setMessage("批准和响铃要靠后台常驻。请把手机桥的电池策略设为「不受限制 / 允许后台活动」，并在系统设置里允许「自启动」。否则锁屏一段时间后通知会收不到。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                })
                .setNegativeButton("以后", null)
                .show();
    }

    /** 通知被关掉的话，批准/响铃全都收不到 —— 这里直接告诉他并给一个跳转 */
    private void checkAlerts() {
        boolean enabled = true;
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            enabled = nm != null && nm.areNotificationsEnabled();
        } catch (Exception ignored) {
        }
        if (enabled) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        banner.setText("通知被关掉了：批准、跑完、响铃都收不到。点这里去打开。");
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
        }
    }

    private void reload() {
        hosts.clear();
        hosts.addAll(Hosts.load(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(hosts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void askActions(final Host h) {
        final String[] items = {"测试连接", "重连后台服务", "删除这台电脑"};
        new AlertDialog.Builder(this)
                .setTitle(h.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        if (i == 0) testConn(h);
                        else if (i == 1) {
                            startService(new Intent(MainActivity.this, LinkService.class)
                                    .setAction(LinkService.ACTION_RELOAD));
                            Toast.makeText(MainActivity.this, "已重连", Toast.LENGTH_SHORT).show();
                        } else askRemove(h);
                    }
                })
                .show();
    }

    /** 点一下就知道到底是「连不上」还是「连上了但没通知」，比猜快 */
    private void testConn(final Host h) {
        if (banner != null) banner.setText("正在连 " + h.url + " …");
        new Thread(new Runnable() {
            public void run() {
                String text;
                try {
                    String body = Http.get(h.api("/api/state?t=" + java.net.URLEncoder.encode(h.token, "UTF-8")), h.token);
                    org.json.JSONObject o = new org.json.JSONObject(body);
                    int n = o.optJSONArray("sessions") == null ? 0 : o.optJSONArray("sessions").length();
                    int p = o.optJSONArray("pending") == null ? 0 : o.optJSONArray("pending").length();
                    text = "连上了\n\n" + h.url + "\n版本 " + o.optString("build", "?")
                            + " · " + n + " 个会话 · " + p + " 个待处理";
                } catch (Exception e) {
                    text = "连不上\n\n" + h.url + "\n\n" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                            + "\n\n检查：手机 Tailscale 开着吗？电脑上的手机桥在跑吗？";
                }
                final String t = text;
                runOnUiThread(new Runnable() {
                    public void run() {
                        checkAlerts();
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(h.name)
                                .setMessage(t)
                                .setPositiveButton("好", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void askRemove(final Host h) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + h.name + "？")
                .setMessage(h.url)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Hosts.remove(MainActivity.this, h.id);
                        reload();
                        startService(new Intent(MainActivity.this, LinkService.class)
                                .setAction(LinkService.ACTION_RELOAD));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class HostAdapter extends BaseAdapter {
        public int getCount() {
            return hosts.size();
        }

        public Object getItem(int i) {
            return hosts.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View reuse, ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView meta;
            if (reuse == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(18), dp(13), dp(18), dp(13));

                name = new TextView(MainActivity.this);
                name.setTextSize(16);
                name.setTextColor(Color.parseColor("#e8eefc"));
                row.addView(name);

                meta = new TextView(MainActivity.this);
                meta.setTextSize(12);
                meta.setTextColor(Color.parseColor("#8b9bb4"));
                meta.setPadding(0, dp(3), 0, 0);
                row.addView(meta);

                row.setTag(new TextView[]{name, meta});
            } else {
                row = (LinearLayout) reuse;
                TextView[] tags = (TextView[]) row.getTag();
                name = tags[0];
                meta = tags[1];
            }

            Host h = hosts.get(i);
            LinkService.Status st = LinkService.statusOf(h.id);
            name.setText(h.name);
            String text;
            if (st.online) {
                text = "在线 · " + st.sessions + " 个会话";
                if (st.running > 0) text += " · " + st.running + " 跑着";
                if (st.pending > 0) text += " · " + st.pending + " 个等你处理";
            } else if (st.note != null && !st.note.isEmpty()) {
                text = "离线 · " + st.note;
            } else {
                text = "连接中…";
            }
            meta.setText(text + "\n" + h.url.replace("https://", ""));
            meta.setTextColor(Color.parseColor(st.online ? "#34d399" : "#8b9bb4"));
            return row;
        }
    }
}
