package dev.zehan.phonebridge;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知栏上的「允许 / 拒绝 / 停止响铃」都走这里。 */
public class AnswerReceiver extends BroadcastReceiver {
    public static final String ACTION_ANSWER = "dev.zehan.phonebridge.ANSWER";
    public static final String ACTION_STOP_ALARM = "dev.zehan.phonebridge.STOP_ALARM";

    @Override
    public void onReceive(Context c, Intent i) {
        final String hostId = i.getStringExtra("hostId");
        final String act = i.getAction();

        if (ACTION_STOP_ALARM.equals(act)) {
            AlarmCenter.get(c).stopAll();
            final Host h = Hosts.byId(c, hostId);
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
            return;
        }

        if (!ACTION_ANSWER.equals(act)) return;
        final String id = i.getStringExtra("id");
        final String action = i.getStringExtra("action");
        final Host h = Hosts.byId(c, hostId);
        if (h == null || id == null || action == null) return;

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel((hostId + "|" + id).hashCode());

        new Thread(new Runnable() {
            public void run() {
                try {
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("id", id);
                    body.put("action", action);
                    Http.post(h.api("/api/answer"), h.token, body.toString());
                } catch (Exception ignored) {
                }
            }
        }).start();
    }
}
