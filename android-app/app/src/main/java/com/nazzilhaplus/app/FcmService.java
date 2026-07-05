package com.nazzilhaplus.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class FcmService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "fcm_download";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        String title = "نزلها بلس";
        String body  = "";
        if (remoteMessage.getNotification() != null) {
            String t = remoteMessage.getNotification().getTitle();
            String b = remoteMessage.getNotification().getBody();
            if (t != null) title = t;
            if (b != null) body  = b;
        }
        showNotification(title, body);
    }

    @Override
    public void onNewToken(String token) {
        // Token refreshed — will be re-sent on next ad completion
    }

    private void showNotification(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "إشعارات التحميل", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        nm.notify(2001, builder.build());
    }
}
