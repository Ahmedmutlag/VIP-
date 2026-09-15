package com.nazzilhaplus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class ClipboardMonitorService extends Service {

    static final String CHANNEL_MONITOR = "clip_monitor";
    static final String CHANNEL_CLIP    = "clip_alert";
    static final String ACTION_DOWNLOAD = "com.nazzilhaplus.app.CLIP_DOWNLOAD";
    static final String ACTION_DISMISS  = "com.nazzilhaplus.app.CLIP_DISMISS";
    static final String EXTRA_URL       = "clip_url";
    private static final int NOTIF_SVC  = 9010;
    static final int NOTIF_CLIP         = 9011;

    private String lastShownUrl = "";
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTIF_SVC, buildSvcNotif());
        watchClipboard();
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && clipListener != null)
                cm.removePrimaryClipChangedListener(clipListener);
        } catch (Exception ignored) {}
    }

    private void watchClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        clipListener = () -> {
            try {
                if (!cm.hasPrimaryClip()) return;

                // Android 10+ blocks background clipboard reads; try anyway
                String url = null;
                android.content.ClipData clip = null;
                try { clip = cm.getPrimaryClip(); } catch (Exception ignored) {}
                if (clip != null) {
                    android.content.ClipData.Item item = clip.getItemAt(0);
                    if (item != null && item.getText() != null)
                        url = item.getText().toString().trim();
                }

                if (url != null) {
                    // Could read clipboard (Android 9- or foreground)
                    if (isVideoUrl(url) && !url.equals(lastShownUrl)) {
                        lastShownUrl = url;
                        showClipNotification(url);
                    }
                } else {
                    // Android 10+: can't read content in background → show tap-to-open notification
                    showGenericClipNotification();
                }
            } catch (Exception ignored) {}
        };
        cm.addPrimaryClipChangedListener(clipListener);
    }

    private void showGenericClipNotification() {
        // Tap → open MainActivity which reads clipboard from foreground
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.putExtra("from_clip_monitor", true);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
            openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dimIntent = new Intent(this, ClipboardActionReceiver.class);
        dimIntent.setAction(ACTION_DISMISS);
        PendingIntent dimPi = PendingIntent.getBroadcast(this, 0,
            dimIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_CLIP)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📋 رابط في الحافظة؟")
            .setContentText("اضغط لتحميله فوراً")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(R.mipmap.ic_launcher, "✕ تجاهل", dimPi);

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_CLIP, nb.build());
        } catch (Exception ignored) {}
    }

    private void showClipNotification(String url) {
        String shortUrl = url.length() > 60 ? url.substring(0, 57) + "..." : url;

        Intent dlIntent = new Intent(this, ClipboardActionReceiver.class);
        dlIntent.setAction(ACTION_DOWNLOAD);
        dlIntent.putExtra(EXTRA_URL, url);
        PendingIntent dlPi = PendingIntent.getBroadcast(this, url.hashCode(),
            dlIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dimIntent = new Intent(this, ClipboardActionReceiver.class);
        dimIntent.setAction(ACTION_DISMISS);
        PendingIntent dimPi = PendingIntent.getBroadcast(this, 0,
            dimIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setAction(Intent.ACTION_SEND);
        openIntent.setType("text/plain");
        openIntent.putExtra(Intent.EXTRA_TEXT, url);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, url.hashCode() + 1,
            openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_CLIP)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📥 رابط فيديو — نزّله الآن؟")
            .setContentText(shortUrl)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(shortUrl))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(R.mipmap.ic_launcher, "⬇️ تحميل الآن", dlPi)
            .addAction(R.mipmap.ic_launcher, "✕ تجاهل", dimPi);

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_CLIP, nb.build());
        } catch (Exception ignored) {}
    }

    private Notification buildSvcNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("نزّلها+ ينتظر روابط الفيديو")
            .setContentText("انسخ أي رابط وسيظهر لك إشعار تحميل فوري")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_MONITOR, "مراقبة الحافظة", NotificationManager.IMPORTANCE_MIN));
            NotificationChannel alertCh = new NotificationChannel(
                CHANNEL_CLIP, "إشعارات الروابط", NotificationManager.IMPORTANCE_HIGH);
            alertCh.enableLights(true);
            alertCh.enableVibration(true);
            nm.createNotificationChannel(alertCh);
        }
    }

    private boolean isVideoUrl(String url) {
        if (url == null || !url.startsWith("http")) return false;
        String low = url.toLowerCase();
        for (String d : new String[]{"tiktok.com","instagram.com","youtube.com","youtu.be",
            "facebook.com","twitter.com","x.com","snapchat.com","pinterest.com",
            "vm.tiktok.com","vt.tiktok.com","fb.watch","dailymotion.com","vimeo.com"})
            if (low.contains(d)) return true;
        return false;
    }

    static void start(Context ctx) {
        if (!isEnabled(ctx)) return;
        Intent i = new Intent(ctx, ClipboardMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(i);
        else
            ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ClipboardMonitorService.class));
    }

    static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences("nazzilha_prefs", MODE_PRIVATE)
            .getBoolean("clip_monitor_enabled", false);
    }
}
