package dev.zehan.phonebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多机连接的核心：前台服务里给每台设备各开一条 SSE，谁有事就报上来。
 *
 *   · 每台一个线程：GET /api/stream（SSE）→ 收到 state 事件就 GET /api/state
 *   · 状态一变化就比对：响铃 / 待批 / 会话从跑到停
 *   · 任何一台断线就 5 秒后重连，不影响其他台
 */
public class LinkService extends Service {
    private static final String TAG = "PBLink";
    public static final String ACTION_RELOAD = "dev.zehan.phonebridge.RELOAD";

    public static final String CH_LINK = "link";
    public static final String CH_APPROVAL = "approval";
    public static final String CH_TURN = "turn";
    private static final int NOTI_SERVICE = 1000;

    /** 给界面看的状态；服务停了也留着，便于显示「离线」 */
    public static class Status {
        public volatile boolean online;
        public volatile String note = "";
        public volatile int sessions;
        public volatile int running;
        public volatile int pending;
        public volatile long at;
        public volatile String name = "";
    }

    public static final Map<String, Status> STATUS = new HashMap<String, Status>();

    public static Status statusOf(String hostId) {
        synchronized (STATUS) {
            Status s = STATUS.get(hostId);
            if (s == null) {
                s = new Status();
                STATUS.put(hostId, s);
            }
            return s;
        }
    }

    /** 任何前台界面都可以调它，保证服务活着（被系统杀掉后能被拉起来） */
    public static void ensure(Context c) {
        try {
            c.startService(new Intent(c, LinkService.class));
        } catch (Exception ignored) {
        }
    }

    private final Map<String, Long> lastDiag = new HashMap<String, Long>();
    private Thread poller;
    private final Map<String, Worker> workers = new HashMap<String, Worker>();
    private final Set<String> notified = new HashSet<String>();
    private final Map<String, Long> runningSince = new HashMap<String, Long>();
    private volatile boolean stopped;
    private Context app;

    private class Worker extends Thread {
        final Host host;
        volatile boolean dead;

        Worker(Host h) {
            super("link-" + h.id);
            this.host = h;
        }

        @Override
        public void run() {
            while (!stopped && !dead) {
                try {
                    stream(host);
                } catch (Exception e) {
                    Status s = statusOf(host.id);
                    s.online = false;
                    s.note = shortError(e);
                    s.at = System.currentTimeMillis();
                    diag(host, "stream-err", shortError(e));
                    pushServiceNotification();
                    sleepQuiet(5000);
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = getApplicationContext();
        AlarmCenter.ensureChannel(app);
        ensureChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_SERVICE, buildServiceNotification());
        boolean reload = intent != null && ACTION_RELOAD.equals(intent.getAction());
        if (reload) stopWorkers();
        sync();
        startPoller();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        stopWorkers();
        super.onDestroy();
    }

    /* ── 连接管理 ─────────────────────────────────────────── */

    private void sync() {
        List<Host> hosts = Hosts.load(this);
        Set<String> live = new HashSet<String>();
        synchronized (workers) {
            for (Host h : hosts) {
                live.add(h.id);
                if (!workers.containsKey(h.id)) {
                    Worker w = new Worker(h);
                    workers.put(h.id, w);
                    w.start();
                }
            }
            List<String> gone = new ArrayList<String>();
            for (String id : workers.keySet()) if (!live.contains(id)) gone.add(id);
            for (String id : gone) {
                Worker w = workers.remove(id);
                if (w != null) w.dead = true;
                synchronized (STATUS) {
                    STATUS.remove(id);
                }
            }
        }
        pushServiceNotification();
    }

    private void stopWorkers() {
        synchronized (workers) {
            for (Worker w : workers.values()) w.dead = true;
            workers.clear();
        }
    }

    /**
     * SSE 是主通道，但长连接被系统掐断时不会立刻报错，批准这类"只在某一刻存在"的请求
     * 就会漏掉。所以每 10 秒无条件拉一次 /api/state 兜底。
     */
    private void startPoller() {
        synchronized (this) {
            if (poller != null && poller.isAlive()) return;
            poller = new Thread(new Runnable() {
                public void run() {
                    while (!stopped) {
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (stopped) return;
                        for (Host h : Hosts.load(LinkService.this)) {
                            try {
                                handleState(h);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }, "link-poll");
            poller.start();
        }
    }

    /** 把「我连上了 / 连不上，原因是…」报给电脑，出问题时能在管理页看到 */
    private void diag(Host h, String stage, String err) {
        synchronized (lastDiag) {
            long now = System.currentTimeMillis();
            Long last = lastDiag.get(h.id + stage);
            if (last != null && now - last < 30000) return;
            lastDiag.put(h.id + stage, now);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("app", "native");
            o.put("stage", stage);
            o.put("v", "0.8.2");
            o.put("url", h.url);
            if (err != null) o.put("err", String.valueOf(err));
            Http.post(h.api("/api/diag"), h.token, o.toString());
        } catch (Exception ignored) {
        }
    }

    /* ── SSE ──────────────────────────────────────────────── */

    private void stream(Host h) throws IOException {
        String url = h.api("/api/stream?t=" + URLEncoder.encode(h.token, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + h.token);
        c.setConnectTimeout(15000);
        c.setReadTimeout(70000);
        int code = c.getResponseCode();
        if (code == 401) {
            Status s = statusOf(h.id);
            s.online = false;
            s.note = "配对失效，重新加一次";
            s.at = System.currentTimeMillis();
            pushServiceNotification();
            throw new IOException("401");
        }
        if (code != 200) throw new IOException("HTTP " + code);

        Status st = statusOf(h.id);
        st.online = true;
        st.note = "";
        st.at = System.currentTimeMillis();
        st.name = h.name;
        diag(h, "stream-ok", null);
        pushServiceNotification();

        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String event = null;
        StringBuilder data = null;
        String line;
        while (!stopped && !isDead(h.id) && (line = r.readLine()) != null) {
            if (line.isEmpty()) {
                if ("state".equals(event)) handleState(h);
                event = null;
                data = null;
                continue;
            }
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data == null) data = new StringBuilder();
                data.append(line.substring(5).trim());
            }
        }
        try {
            c.disconnect();
        } catch (Exception ignored) {
        }
        throw new IOException("stream closed");
    }

    private boolean isDead(String hostId) {
        synchronized (workers) {
            Worker w = workers.get(hostId);
            return w == null || w.dead;
        }
    }

    /* ── 状态比对 ─────────────────────────────────────────── */

    private synchronized void handleState(Host h) {
        String body;
        try {
            body = Http.get(h.api("/api/state?t=" + URLEncoder.encode(h.token, "UTF-8")), h.token);
        } catch (IOException e) {
            Status s = statusOf(h.id);
            s.online = false;
            s.note = shortError(e);
            s.at = System.currentTimeMillis();
            return;
        }
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (Exception e) {
            return;
        }

        Status s = statusOf(h.id);
        s.online = true;
        s.note = "";
        s.name = h.name;
        s.at = System.currentTimeMillis();

        JSONArray sessions = o.optJSONArray("sessions");
        s.sessions = sessions == null ? 0 : sessions.length();

        /* 0. 会话名（批准/提问要能看出来是哪条会话在等你） */
        Map<String, String> sessionLabel = new HashMap<String, String>();
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject one = sessions.optJSONObject(i);
                if (one == null) continue;
                sessionLabel.put(one.optString("id", ""), one.optString("label", ""));
            }
        }

        /* 1. 响铃 */
        JSONObject alarm = o.optJSONObject("alarm");
        boolean ringing = alarm != null && alarm.optBoolean("ringing", false);
        if (ringing) {
            AlarmCenter.get(this).ring(h, alarm.optString("label", h.name),
                    alarm.optString("reason", "done"), alarm.optLong("elapsed", 0));
        } else {
            AlarmCenter.get(this).stopped(h.id);
        }

        /* 2. 待批准 / 待回答 */
        JSONArray pending = o.optJSONArray("pending");
        s.pending = pending == null ? 0 : pending.length();
        Set<String> live = new HashSet<String>();
        if (pending != null) {
            for (int i = 0; i < pending.length(); i++) {
                JSONObject p = pending.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id", "");
                if (id.isEmpty()) continue;
                String key = h.id + "|" + id;
                live.add(key);
                boolean seen;
                synchronized (notified) {
                    seen = notified.contains(key);
                    if (!seen) notified.add(key);
                }
                if (!seen) notifyPending(h, p, key, sessionLabel.get(p.optString("sessionId", "")));
            }
        }
        synchronized (notified) {
            notified.retainAll(live);
        }

        /* 3. 会话「跑完」：从 running 掉下来，且跑了超过 30 秒 */
        int running = 0;
        if (sessions != null) {
            Set<String> liveSessions = new HashSet<String>();
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject one = sessions.optJSONObject(i);
                if (one == null) continue;
                String sid = one.optString("id", "");
                String label = one.optString("label", "");
                boolean run = one.optBoolean("running", false);
                String key = h.id + "|" + sid;
                if (run) {
                    running++;
                    liveSessions.add(key);
                    synchronized (runningSince) {
                        if (!runningSince.containsKey(key)) runningSince.put(key, System.currentTimeMillis());
                    }
                } else {
                    Long t;
                    synchronized (runningSince) {
                        t = runningSince.remove(key);
                    }
                    if (t != null && System.currentTimeMillis() - t > 30000L) {
                        notifyDone(h, label, System.currentTimeMillis() - t);
                    }
                }
            }
            synchronized (runningSince) {
                List<String> drop = new ArrayList<String>();
                for (String k : runningSince.keySet()) if (!liveSessions.contains(k)) drop.add(k);
                for (String k : drop) runningSince.remove(k);
            }
        }
        s.running = running;
        pushServiceNotification();
    }

    /* ── 通知 ─────────────────────────────────────────────── */

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel link = new NotificationChannel(CH_LINK, "连接状态", NotificationManager.IMPORTANCE_LOW);
        link.setShowBadge(false);
        nm.createNotificationChannel(link);
        NotificationChannel ap = new NotificationChannel(CH_APPROVAL, "要你批准", NotificationManager.IMPORTANCE_HIGH);
        ap.enableVibration(true);
        nm.createNotificationChannel(ap);
        NotificationChannel turn = new NotificationChannel(CH_TURN, "会话跑完", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(turn);
    }

    private Notification buildServiceNotification() {
        int online = 0;
        int running = 0;
        int total = 0;
        synchronized (STATUS) {
            for (Status s : STATUS.values()) {
                total++;
                if (s.online) online++;
                running += s.running;
            }
        }
        if (total == 0) total = Hosts.load(this).size();
        int pending = 0;
        synchronized (STATUS) {
            for (Status st : STATUS.values()) pending += st.pending;
        }
        String text = total == 0
                ? "还没有添加设备"
                : (online + "/" + total + " 台在线"
                    + (running > 0 ? " · " + running + " 个跑着" : "")
                    + (pending > 0 ? " · " + pending + " 个等你处理" : ""));

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_LINK)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("手机桥")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void pushServiceNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_SERVICE, buildServiceNotification());
        } catch (Exception ignored) {
        }
    }

    private void notifyPending(Host h, JSONObject p, String key, String sessionLabel) {
        String id = p.optString("id", "");
        String kind = p.optString("kind", "approval");
        String tool = p.optString("tool", "");
        String reason = p.optString("reason", "");
        boolean approval = "approval".equals(kind);

        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("hostId", h.id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, key.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_APPROVAL)
                : new Notification.Builder(this);
        String sess = sessionLabel == null ? "" : sessionLabel;
        b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle((approval ? "要你批准" : "要你回答") + (sess.isEmpty() ? "" : " · " + sess))
                .setContentText((tool + (reason.isEmpty() ? "" : " · " + reason)))
                .setStyle(new Notification.BigTextStyle().bigText(h.name + (sess.isEmpty() ? "" : " · " + sess) + "\n" + tool + "\n" + reason))
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        if (approval) {
            Intent allow = new Intent(this, AnswerReceiver.class);
            allow.setAction(AnswerReceiver.ACTION_ANSWER);
            allow.putExtra("hostId", h.id);
            allow.putExtra("id", id);
            allow.putExtra("action", "allow");
            PendingIntent pAllow = PendingIntent.getBroadcast(this, (key + "a").hashCode(), allow,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent deny = new Intent(this, AnswerReceiver.class);
            deny.setAction(AnswerReceiver.ACTION_ANSWER);
            deny.putExtra("hostId", h.id);
            deny.putExtra("id", id);
            deny.putExtra("action", "deny");
            PendingIntent pDeny = PendingIntent.getBroadcast(this, (key + "d").hashCode(), deny,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            b.addAction(new Notification.Action.Builder(android.R.drawable.checkbox_on_background, "允许一次", pAllow).build());
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_delete, "拒绝", pDeny).build());
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(key.hashCode(), b.build());
    }

    private void notifyDone(Host h, String label, long ms) {
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("hostId", h.id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, (h.id + label).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_TURN)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("跑完了 · " + h.name)
                .setContentText(label + " · " + (ms / 1000) + " 秒")
                .setAutoCancel(true)
                .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((h.id + "|done|" + label).hashCode(), b.build());
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }
}
