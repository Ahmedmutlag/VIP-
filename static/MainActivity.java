package com.nazzilhaplus.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
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

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private static final String SERVER_URL    = "https://www.vip-dl.com";
    private static final String CHANNEL_ID    = "nazzilha_dl";
    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(8000);

    private WebView webView;
    private String  sharedUrl = null;
    private final ExecutorService executor  = Executors.newCachedThreadPool();
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        createNotificationChannel();
        requestNotificationPermission();
        handleIntent(getIntent());
        setupWebView();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause()   { super.onPause();   if (webView != null) webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  if (webView != null) webView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); executor.shutdownNow(); }

    // ─────────────────────────────────────────────────────────────
    //  Intent parsing (Share)
    // ─────────────────────────────────────────────────────────────
    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.isEmpty()) return;
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("https?://[^\\s]+").matcher(text);
        sharedUrl = m.find() ? m.group() : text.trim();
    }

    // ─────────────────────────────────────────────────────────────
    //  WebView
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
                if (sharedUrl != null) {
                    final String u = sharedUrl;
                    sharedUrl = null;
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
    //  JavaScript Bridge
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
         * fileUrl is always our own /api/file/xxx.mp4 URL for the normal flow.
         */
        @JavascriptInterface
        public void downloadFile(String fileUrl, String filename) {
            String videoUrl   = (fileUrl   == null || fileUrl.isEmpty())   ? "" : fileUrl.trim();
            String safeFilename = (filename == null || filename.isEmpty()) ? "video.mp4" : filename.trim();

            // Our server URL → download directly via HttpURLConnection
            if (videoUrl.contains("vip-dl.com") || videoUrl.startsWith("/api/")) {
                String absUrl = videoUrl.startsWith("http") ? videoUrl : SERVER_URL + videoUrl;
                executor.execute(() -> downloadFile_HTTP(absUrl, safeFilename, null));
                return;
            }

            // Non-server URL → try to get a direct CDN URL first
            executor.execute(() -> tryDirectDownload(videoUrl, safeFilename));
        }

        @JavascriptInterface
        public void tryDirectForSharedUrl(String originalUrl) {
            if (originalUrl == null || originalUrl.isEmpty()) return;
            executor.execute(() -> tryDirectDownload(originalUrl, null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Direct URL check → fallback message
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

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("has_direct", false)) {
                    String directUrl = json.optString("url");
                    String fn = json.optString("filename",
                        fallbackFilename != null ? fallbackFilename : "video.mp4");
                    String referer = json.optString("referer", originalUrl);
                    downloadFile_HTTP(directUrl, fn, referer);
                    return;
                }
            }
        } catch (Exception ignored) {}

        uiHandler.post(() ->
            Toast.makeText(MainActivity.this,
                "جاري التحميل عبر السيرفر...", Toast.LENGTH_SHORT).show()
        );
    }

    // ─────────────────────────────────────────────────────────────
    //  Manual HttpURLConnection download (3 retries, progress notif)
    // ─────────────────────────────────────────────────────────────
    private void downloadFile_HTTP(String fileUrl, String displayName, String referer) {
        // Determine file extension from URL path (before query string)
        String urlPath = fileUrl.split("\\?")[0];
        String ext = urlPath.contains(".")
            ? urlPath.substring(urlPath.lastIndexOf('.')) : ".mp4";
        if (ext.length() > 5 || !ext.matches("\\.[a-zA-Z0-9]+")) ext = ".mp4";

        // Build a safe base name without the extension
        String rawBase = displayName.endsWith(ext)
            ? displayName.substring(0, displayName.length() - ext.length())
            : displayName;
        rawBase = rawBase.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (rawBase.isEmpty()) rawBase = "video";

        final String finalName = rawBase + "_" + System.currentTimeMillis() + ext;
        final String displayFilename = displayName;

        int notifId = NOTIF_COUNTER.incrementAndGet();
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        uiHandler.post(() ->
            Toast.makeText(MainActivity.this, "⬇️ بدأ التحميل...", Toast.LENGTH_SHORT).show()
        );

        int maxRetries = 3;
        String lastError = "خطأ غير معروف";

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (attempt > 1) {
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { break; }
            }
            HttpURLConnection conn = null;
            try {
                URL url = new URL(fileUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE +
                    ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                if (referer != null && !referer.isEmpty())
                    conn.setRequestProperty("Referer", referer);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(120_000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200 && code != 206) {
                    lastError = "خطأ في السيرفر: " + code;
                    conn.disconnect();
                    continue;
                }

                long total = conn.getContentLengthLong();

                File outDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "NazzilhaPlus");
                outDir.mkdirs();
                File outFile = new File(outDir, finalName);

                long downloaded  = 0;
                long lastNotifMs = 0;
                long lastBytes   = 0;
                long lastTime    = System.currentTimeMillis();

                try (InputStream in  = new BufferedInputStream(conn.getInputStream(), 65536);
                     FileOutputStream fos = new FileOutputStream(outFile)) {

                    byte[] buf = new byte[65536];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        downloaded += read;

                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastNotifMs >= 1000 && nm != null) {
                            lastNotifMs = nowMs;
                            long dt    = nowMs - lastTime;
                            long speed = dt > 0 ? (downloaded - lastBytes) * 1000 / dt : 0;
                            lastBytes  = downloaded;
                            lastTime   = nowMs;

                            int pct = total > 0 ? (int)(downloaded * 100 / total) : 0;
                            String speedStr    = formatSpeed(speed);
                            String progressStr = total > 0
                                ? formatBytes(downloaded) + " / " + formatBytes(total)
                                : formatBytes(downloaded);

                            NotificationCompat.Builder nb = new NotificationCompat.Builder(
                                MainActivity.this, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.stat_sys_download)
                                .setContentTitle("⬇️ " + displayFilename)
                                .setContentText(progressStr + "  •  " + speedStr)
                                .setProgress(100, pct, total <= 0)
                                .setOngoing(true)
                                .setSilent(true)
                                .setPriority(NotificationCompat.PRIORITY_LOW);
                            nm.notify(notifId, nb.build());
                        }
                    }
                }

                // ── Success ──────────────────────────────────────
                if (nm != null) {
                    nm.cancel(notifId);
                    NotificationCompat.Builder nb = new NotificationCompat.Builder(
                        MainActivity.this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("✅ اكتمل التحميل")
                        .setContentText(displayFilename)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                    nm.notify(notifId + 1, nb.build());
                }

                MediaScannerConnection.scanFile(
                    MainActivity.this,
                    new String[]{outFile.getAbsolutePath()},
                    null, null
                );

                uiHandler.post(() ->
                    Toast.makeText(MainActivity.this,
                        "✅ اكتمل: " + displayFilename, Toast.LENGTH_LONG).show()
                );
                return; // done

            } catch (IOException e) {
                lastError = e.getMessage() != null ? e.getMessage() : "خطأ في الاتصال";
                if (conn != null) conn.disconnect();
            }
        }

        // ── All retries failed ────────────────────────────────────
        final String errMsg = lastError;
        if (nm != null) nm.cancel(notifId);
        uiHandler.post(() ->
            Toast.makeText(MainActivity.this,
                "❌ فشل التحميل: " + errMsg, Toast.LENGTH_LONG).show()
        );
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
                    android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    return item != null && item.getText() != null
                        ? item.getText().toString() : "";
                }
            } catch (Exception ignored) {}
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Notification channel + permission
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Formatting helpers
    // ─────────────────────────────────────────────────────────────
    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes >= 1_073_741_824L) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%d KB", bytes / 1024);
    }

    private static String formatSpeed(long bps) {
        if (bps <= 0) return "—";
        if (bps >= 1_048_576L) return String.format("%.1f MB/s", bps / 1_048_576.0);
        return String.format("%d KB/s", bps / 1024);
    }

    private static String escapeJs(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
