package dev.zehan.phonebridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 全屏响铃页：亮屏 + 大按钮，按了才停。 */
public class AlarmActivity extends Activity {
    private String hostId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hostId = getIntent().getStringExtra("hostId");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setContentView(buildUi());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hostId = intent.getStringExtra("hostId");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#2a0a14"));
        int p = dp(24);
        root.setPadding(p, p, p, p);

        TextView bell = new TextView(this);
        bell.setText("⏰");
        bell.setTextSize(72);
        bell.setGravity(Gravity.CENTER);
        root.addView(bell);

        TextView title = new TextView(this);
        title.setText("任务跑完了");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#ffffff"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(6));
        root.addView(title);

        Host h = Hosts.byId(this, hostId);
        TextView sub = new TextView(this);
        sub.setText(h == null ? "" : h.name);
        sub.setTextSize(14);
        sub.setTextColor(Color.parseColor("#c9aeb8"));
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        Button stop = new Button(this);
        stop.setText("停止响铃");
        stop.setAllCaps(false);
        stop.setTextSize(19);
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopAlarm();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(64));
        lp.topMargin = dp(26);
        root.addView(stop, lp);
        return root;
    }

    private void stopAlarm() {
        AlarmCenter.get(this).stopAll();
        final Host h = Hosts.byId(this, hostId);
        if (h != null) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Http.post(h.api("/api/alarm/stop"), h.token, "{}");
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
