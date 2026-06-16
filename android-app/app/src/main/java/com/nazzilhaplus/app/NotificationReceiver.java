package com.nazzilhaplus.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;
import androidx.core.app.NotificationCompat;

public class NotificationReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "daily_reminder";
    static final String DOWNLOAD_CHANNEL_ID = "download_progress";

    private static final String[] MESSAGES = {
        "شفت فيديو عجبك اليوم؟ نزّله بنزلها بلس وخليه عندك! ⬇️",
        "اگلك بس افتح التطبيق هسه وحمّل اللي يعجبك، مجاني وبدون تسجيل 🎵",
        "تيك توك، انستقرام، فيسبوك... كلها تتحمل من مكان واحد 📲",
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

        // reschedule for next day
        schedule(context);
    }

    static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, NotificationReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 19);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pi);
        }
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);

            NotificationChannel reminder = new NotificationChannel(
                    CHANNEL_ID, "تذكير يومي", NotificationManager.IMPORTANCE_DEFAULT);
            reminder.setDescription("تذكير يومي لاستخدام التطبيق");
            nm.createNotificationChannel(reminder);

            NotificationChannel download = new NotificationChannel(
                    DOWNLOAD_CHANNEL_ID, "تحميل الفيديوهات", NotificationManager.IMPORTANCE_LOW);
            download.setDescription("تقدم تحميل الفيديوهات");
            nm.createNotificationChannel(download);
        }
    }
}
