package com.nazzilhaplus.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationManagerCompat;

public class ClipboardActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManagerCompat.from(ctx).cancel(ClipboardMonitorService.NOTIF_CLIP);
        if (ClipboardMonitorService.ACTION_DOWNLOAD.equals(intent.getAction())) {
            String url = intent.getStringExtra(ClipboardMonitorService.EXTRA_URL);
            if (url == null || url.isEmpty()) return;
            Intent open = new Intent(ctx, MainActivity.class);
            open.setAction(Intent.ACTION_SEND);
            open.setType("text/plain");
            open.putExtra(Intent.EXTRA_TEXT, url);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(open);
        }
    }
}
