package dev.zehan.phonebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * 「跑完叫我」的本地放大器：网页只能叮一下，这里用 Alarm 音频流循环放 + 长振动，
 * 直到他按停止（或宿主那边撤销）。
 */
public class AlarmCenter {
    private static final String TAG = "PBAlarm";
    public static final String CH_ALARM = "alarm";
    public static final int NOTI_ID = 2001;

    private static AlarmCenter sInst;

    public static synchronized AlarmCenter get(Context ctx) {
        if (sInst == null) sInst = new AlarmCenter(ctx.getApplicationContext());
        return sInst;
    }

    private final Context ctx;
    private MediaPlayer player;
    private Vibrator vib;
    private Handler handler;
    private Runnable vibrateTask;
    private String hostId;
    private String hostName = "";

    private AlarmCenter(Context ctx) {
        this.ctx = ctx;
        ensureChannel(ctx);
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ALARM, "响铃", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("会话跑完时像闹钟一样响");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 700, 300, 700, 300, 700});
        ch.setBypassDnd(true);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(ch);
    }

    public synchronized boolean isRinging() {
        return hostId != null;
    }

    public synchronized String ringingHost() {
        return hostId;
    }

    /** 宿主说这个会话在响 */
    public synchronized void ring(Host h, String label, String reason, long elapsedMs) {
        if (hostId != null && hostId.equals(h.id)) return; // 已经在响，别重启
        if (hostId != null) stopInternal();
        hostId = h.id;
        hostName = h.name == null ? "" : h.name;
        startSound();
        startVibrate();
        showNotification();
        Log.i(TAG, "ring from " + h.name + " label=" + label);
    }

    /** 宿主说这个会话不响了（或它自己停了） */
    public synchronized void stopped(String forHostId) {
        if (hostId == null) return;
        if (forHostId != null && !forHostId.equals(hostId)) return;
        stopInternal();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTI_ID);
    }

    public synchronized void stopAll() {
        String old = hostId;
        stopInternal();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTI_ID);
        if (old != null) Log.i(TAG, "stopped");
    }

    private void stopInternal() {
        hostId = null;
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (handler != null && vibrateTask != null) handler.removeCallbacks(vibrateTask);
        vibrateTask = null;
        if (vib != null) {
            try {
                vib.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private void startSound() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri == null) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(ctx, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.w(TAG, "alarm sound failed: " + e);
        }
    }

    private void startVibrate() {
        vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        long[] pattern = new long[]{0, 700, 300, 700, 300, 700};
        try {
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, 0));
            else vib.vibrate(pattern, 0);
        } catch (Exception ignored) {
        }
    }

    private void showNotification() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent full = new Intent(ctx, AlarmActivity.class);
        full.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        full.putExtra("hostId", hostId);
        PendingIntent pi = PendingIntent.getActivity(ctx, 90, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(ctx, AnswerReceiver.class);
        stop.setAction(AnswerReceiver.ACTION_STOP_ALARM);
        stop.putExtra("hostId", hostId);
        PendingIntent stopPi = PendingIntent.getBroadcast(ctx, 91, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CH_ALARM)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ " + hostName + " · 跑完了")
                .setContentText("点开按停止")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setOngoing(true)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止响铃", stopPi).build());
        try {
            nm.notify(NOTI_ID, b.build());
        } catch (Exception ignored) {
        }
    }
}
