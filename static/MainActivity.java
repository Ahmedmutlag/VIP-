package com.nazzilhaplus.app;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String SERVER_URL = "https://www.vip-dl.com";
    private static final String CHANNEL_ID  = "nazzilha_dl";
    private static final int    NOTIF_BASE  = 7000;

    private WebView webView;
    private String  sharedUrl  = null;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────
    //  onCreate
    // ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createNotificationChannel();
        handleIntent(getIntent());
        setupWebView();
    }

    // ─────────────────────────────────────────────────────────────
    //  onNewIntent  (app already running, Share Intent arrives)
    // ─────────────────────────────────────────────────────────────
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ─────────────────────────────────────────────────────────────
    //  Intent parsing
    // ─────────────────────────────────────────────────────────────
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.isEmpty()) return;

        // Extract the first HTTP URL from the shared text
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("https?://[^\\s]+").matcher(text);
        sharedUrl = m.find() ? m.group() : text.trim();
    }

    // ─────────────────────────────────────────────────────────────
    //  WebView setup
    // ─────────────────────────────────────────────────────────────
    private void setupWebView() {
        webView = findViewById(R.id.webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE +
            "; " + Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36 NazzilhaPlus/1.0"
        );

        webView.addJavascriptInterface(new ClipboardBridge(this), "AndroidClipboard");
        webView.addJavascriptInterface(new AppBridge(),             "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("intent://") || url.startsWith("market://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject the shared URL once the page is ready
                if (sharedUrl != null) {
                    final String u = sharedUrl;
                    sharedUrl = null;  // clear so it doesn't re-inject on back/forward
                    uiHandler.postDelayed(() ->
                        view.evaluateJavascript(
                            "if(window.receiveSharedUrl) window.receiveSharedUrl(" +
                            escapeJs(u) + ");", null),
                        800
                    );
                }
            }
        });

        webView.loadUrl(SERVER_URL);
    }

    // ─────────────────────────────────────────────────────────────
    //  AppBridge  — methods callable from JavaScript
    // ─────────────────────────────────────────────────────────────
    private class AppBridge {

        @JavascriptInterface
        public void openPlayStore() {
            uiHandler.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
                }
            });
        }

        /**
         * Called by the website after a successful server-side download.
         * Tries the fast path (direct CDN URL) first; falls back to server URL.
         */
        @JavascriptInterface
        public void downloadFile(String fileUrl, String filename) {
            String videoUrl = (fileUrl == null || fileUrl.isEmpty()) ? "" : fileUrl.trim();
            String safeFilename = (filename == null || filename.isEmpty()) ? "video.mp4" : filename.trim();

            // If it's already our server URL, skip direct-URL check and download from server
            if (videoUrl.contains("vip-dl.com") || videoUrl.startsWith("/api/")) {
                String absUrl = videoUrl.startsWith("http") ? videoUrl : SERVER_URL + videoUrl;
                enqueueDownload(absUrl, safeFilename, null, null);
                return;
            }

            // Otherwise check if there's a direct CDN URL via our API
            executor.execute(() -> {
                String currentPageUrl = videoUrl; // use as original URL hint
                tryDirectDownload(currentPageUrl, safeFilename);
            });
        }

        /**
         * Called by receiveSharedUrl (Share Intent flow).
         * Checks for direct URL first; if none, lets WebView handle the normal flow.
         */
        @JavascriptInterface
        public void tryDirectForSharedUrl(String originalUrl) {
            if (originalUrl == null || originalUrl.isEmpty()) return;
            executor.execute(() -> tryDirectDownload(originalUrl, null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Direct URL logic
    // ─────────────────────────────────────────────────────────────
    private void tryDirectDownload(String originalUrl, String fallbackFilename) {
        try {
            URL apiUrl = new URL(SERVER_URL + "/api/direct-url");
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);

            String body = "{\"url\":\"" + originalUrl.replace("\"", "\\\"") + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("has_direct", false)) {
                    String directUrl = json.optString("url");
                    String filename  = json.optString("filename", fallbackFilename != null ? fallbackFilename : "video.mp4");
                    String referer   = json.optString("referer", originalUrl);
                    enqueueDownload(directUrl, filename, referer, null);
                    return;
                }
            }
        } catch (Exception ignored) {}

        // Direct URL not available — fallback already handled by the website's server-side flow
        uiHandler.post(() ->
            Toast.makeText(MainActivity.this,
                "جاري التحميل عبر السيرفر...", Toast.LENGTH_SHORT).show()
        );
    }

    // ─────────────────────────────────────────────────────────────
    //  DownloadManager enqueue + progress notification
    // ─────────────────────────────────────────────────────────────
    private void enqueueDownload(String fileUrl, String filename, String referer, String cookie) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;

        String safeFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(fileUrl));
        req.setTitle("نزلها بلس — " + safeFilename);
        req.setDescription("جاري التحميل...");
        req.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS, "NazzilhaPlus/" + safeFilename);
        req.allowScanningByMediaScanner();

        req.addRequestHeader("User-Agent",
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE +
            ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        if (referer != null && !referer.isEmpty())
            req.addRequestHeader("Referer", referer);
        if (cookie != null && !cookie.isEmpty())
            req.addRequestHeader("Cookie", cookie);

        long downloadId = dm.enqueue(req);

        // Show toast
        uiHandler.post(() ->
            Toast.makeText(MainActivity.this,
                "⬇️ بدأ التحميل...", Toast.LENGTH_SHORT).show()
        );

        // Track progress and update notification with speed
        executor.execute(() -> trackDownloadProgress(dm, downloadId, safeFilename));
    }

    private void trackDownloadProgress(DownloadManager dm, long downloadId, String filename) {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        int notifId = (int)(NOTIF_BASE + (downloadId % 1000));
        long lastBytes = 0;
        long lastTime  = System.currentTimeMillis();
        boolean running = true;

        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(downloadId);
            try (Cursor c = dm.query(q)) {
                if (c == null || !c.moveToFirst()) break;

                int statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int totalIdx  = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int doneIdx   = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);

                int    status    = statusIdx  >= 0 ? c.getInt(statusIdx)  : -1;
                long   total     = totalIdx   >= 0 ? c.getLong(totalIdx)  : -1;
                long   downloaded = doneIdx   >= 0 ? c.getLong(doneIdx)   : 0;

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    running = false;
                    nm.cancel(notifId);
                    showDoneNotification(nm, notifId, filename);
                    uiHandler.post(() ->
                        Toast.makeText(MainActivity.this,
                            "✅ اكتمل تحميل: " + filename, Toast.LENGTH_LONG).show()
                    );
                    break;
                }

                if (status == DownloadManager.STATUS_FAILED) {
                    running = false;
                    nm.cancel(notifId);
                    uiHandler.post(() ->
                        Toast.makeText(MainActivity.this,
                            "❌ فشل التحميل", Toast.LENGTH_SHORT).show()
                    );
                    break;
                }

                if (status != DownloadManager.STATUS_RUNNING &&
                        status != DownloadManager.STATUS_PAUSED) break;

                // Calculate speed
                long now   = System.currentTimeMillis();
                long dt    = now - lastTime;
                long speed = dt > 0 ? (downloaded - lastBytes) * 1000 / dt : 0;
                lastBytes  = downloaded;
                lastTime   = now;

                int    pct         = (total > 0) ? (int)(downloaded * 100 / total) : 0;
                String speedStr    = formatSpeed(speed);
                String progressStr = total > 0
                    ? formatBytes(downloaded) + " / " + formatBytes(total)
                    : formatBytes(downloaded);

                NotificationCompat.Builder nb = new NotificationCompat.Builder(
                    MainActivity.this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("⬇️ " + filename)
                    .setContentText(progressStr + "  •  " + speedStr)
                    .setProgress(100, pct, total <= 0)
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);

                nm.notify(notifId, nb.build());
            }
        }
    }

    private void showDoneNotification(NotificationManager nm, int notifId, String filename) {
        NotificationCompat.Builder nb = new NotificationCompat.Builder(
            MainActivity.this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ اكتمل التحميل")
            .setContentText(filename)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify(notifId + 1, nb.build());
    }

    // ─────────────────────────────────────────────────────────────
    //  ClipboardBridge
    // ─────────────────────────────────────────────────────────────
    private static class ClipboardBridge {
        private final Context ctx;
        ClipboardBridge(Context c) { this.ctx = c; }

        @JavascriptInterface
        public String getText() {
            try {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    android.content.ClipData.Item item =
                        cm.getPrimaryClip().getItemAt(0);
                    return item != null && item.getText() != null
                        ? item.getText().toString() : "";
                }
            } catch (Exception ignored) {}
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "نزلها بلس — التحميل",
            NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("إشعارات تقدم التحميل");
        ch.setSound(null, null);
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes >= 1_073_741_824L) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%d KB", bytes / 1024);
    }

    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "—";
        if (bytesPerSec >= 1_048_576L) return String.format("%.1f MB/s", bytesPerSec / 1_048_576.0);
        return String.format("%d KB/s", bytesPerSec / 1024);
    }

    private static String escapeJs(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause()  { super.onPause();  if (webView != null) webView.onPause(); }
    @Override
    protected void onResume() { super.onResume(); if (webView != null) webView.onResume(); }
    @Override
    protected void onDestroy() { super.onDestroy(); executor.shutdownNow(); }
}
