package com.nazzilhaplus.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class NotificationReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "daily_reminder";

    private static final String[] MESSAGES = {
        "شفت فيديو زين اليوم؟ نزّله بنزلها بلس وخليه عندك! ⬇️",
        "گولك بس فتح التطبيق وحمّل اللي يعجبك، مجاني وبدون تسجيل 🎵",
        "تيك توك، انستقرام، فيسبوك... كلها تنحمل من مكان واحد 📲",
        "بدون علامة مائية وبدون دردشة، نزّل وخلص ✨",
        "اشتقنالك! افتح نزلها بلس ونزّل أحلى الفيديوهات 🚀",
        "شفت ريلز حلو؟ لا تخليه يضيع، نزّله هسه! 💾",
        "ثواني بس وفيديوهاتك المفضلة تصير عندك ⚡",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        createChannel(context);

        String message = MESSAGES[(int) (Math.random() * MESSAGES.length)];

        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("نزلها بلس ⬇️")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1001, builder.build());
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "تذكير يومي", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("تذكير يومي لاستخدام التطبيق");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }
}
