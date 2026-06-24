package com.nazzilhaplus.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int STORAGE_PERMISSION_CODE = 100;
    private String pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        setupWebView();
        webView.loadUrl("https://www.vip-dl.com");
        NotificationReceiver.createChannel(this);
        requestNotificationPermission();
        NotificationReceiver.schedule(this);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();
                if (url.contains("play.google.com") || url.startsWith("market://")
                        || "whatsapp".equals(scheme) || "tg".equals(scheme)
                        || "instagram".equals(scheme) || "fb".equals(scheme)
                        || "twitter".equals(scheme) || "snapchat".equals(scheme)
                        || "intent".equals(scheme)) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        view.loadUrl(url);
                    }
                    return true;
                }
                return false;
            }
        });

        webView.addJavascriptInterface(new AppBridge(), "AndroidApp");

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                pendingUrl = url;
                pendingUserAgent = userAgent;
                pendingContentDisposition = contentDisposition;
                pendingMimeType = mimeType;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
            } else {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
        Toast.makeText(this, "⬇️ جاري التحميل...", Toast.LENGTH_SHORT).show();
        new Thread(() -> downloadWithRetry(url, userAgent, filename)).start();
    }

    private void downloadWithRetry(String url, String userAgent, String filename) {
        int notifId = filename.hashCode();
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationReceiver.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(filename)
                .setContentText("جاري التحميل...")
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try { nm.notify(notifId, builder.build()); } catch (Exception ignored) {}

        Uri[] resultUri = new Uri[]{null};
        boolean success;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            success = downloadViaMediaStore(url, userAgent, filename, notifId, builder, nm, resultUri);
        } else {
            success = downloadViaFileSystem(url, userAgent, filename, notifId, builder, nm, resultUri);
        }

        nm.cancel(notifId);

        if (success) {
            final Uri fileUri = resultUri[0];
            runOnUiThread(() -> showSuccessDialog(fileUri));
        } else {
            runOnUiThread(() -> Toast.makeText(this, "❌ فشل التحميل، يرجى المحاولة مجددًا", Toast.LENGTH_LONG).show());
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private boolean downloadViaMediaStore(String url, String userAgent, String filename,
            int notifId, NotificationCompat.Builder builder, NotificationManagerCompat nm, Uri[] resultUri) {
        Uri downloadUri = null;
        int maxRetries = 5;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                long existingSize = 0;

                if (downloadUri == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
                    values.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/NazzilhaPlus");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    downloadUri = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (downloadUri == null) return false;
                } else {
                    android.database.Cursor c = getContentResolver().query(
                            downloadUri, new String[]{MediaStore.Downloads.SIZE}, null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) existingSize = c.getLong(0);
                        c.close();
                    }
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                if (existingSize > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
                }
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;

                long totalSize = existingSize + conn.getContentLengthLong();
                String mode = (code == 206 && existingSize > 0) ? "wa" : "w";

                try (InputStream in = conn.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(downloadUri, mode)) {
                    if (out == null) break;
                    byte[] buffer = new byte[8192];
                    int read;
                    long downloaded = existingSize;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (totalSize > 0) {
                            int pct = (int) (downloaded * 100L / totalSize);
                            builder.setProgress(100, pct, false).setContentText(pct + "%");
                            try { nm.notify(notifId, builder.build()); } catch (Exception ignored) {}
                        }
                    }
                }

                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(downloadUri, done, null, null);
                resultUri[0] = downloadUri;
                return true;

            } catch (Exception e) {
                if (attempt < maxRetries - 1) {
                    try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
                }
            }
        }

        if (downloadUri != null) {
            try { getContentResolver().delete(downloadUri, null, null); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean downloadViaFileSystem(String url, String userAgent, String filename,
            int notifId, NotificationCompat.Builder builder, NotificationManagerCompat nm, Uri[] resultUri) {
        File outputDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NazzilhaPlus");
        outputDir.mkdirs();
        File outputFile = new File(outputDir, filename);
        int maxRetries = 5;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                long existingSize = outputFile.exists() ? outputFile.length() : 0;

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                if (existingSize > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
                }
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;

                long totalSize = existingSize + conn.getContentLengthLong();
                boolean append = (code == 206 && existingSize > 0);

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outputFile, append)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long downloaded = existingSize;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (totalSize > 0) {
                            int pct = (int) (downloaded * 100L / totalSize);
                            builder.setProgress(100, pct, false).setContentText(pct + "%");
                            try { nm.notify(notifId, builder.build()); } catch (Exception ignored) {}
                        }
                    }
                }

                final String[] paths = {outputFile.getAbsolutePath()};
                Uri[] scanned = new Uri[]{null};
                Object lock = new Object();
                MediaScannerConnection.scanFile(this, paths, null, (path, uri) -> {
                    scanned[0] = uri;
                    synchronized (lock) { lock.notifyAll(); }
                });
                synchronized (lock) {
                    try { lock.wait(3000); } catch (InterruptedException ignored) {}
                }
                resultUri[0] = scanned[0] != null ? scanned[0] : Uri.fromFile(outputFile);
                return true;

            } catch (Exception e) {
                if (attempt < maxRetries - 1) {
                    try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    private void showSuccessDialog(Uri fileUri) {
        androidx.appcompat.app.AlertDialog.Builder dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("✅ اكتمل التحميل")
                        .setMessage("تم حفظ الفيديو في Downloads/NazzilhaPlus")
                        .setNegativeButton("حسناً", null);

        if (fileUri != null) {
            dialog.setPositiveButton("فتح الفيديو", (d, w) -> {
                Intent open = new Intent(Intent.ACTION_VIEW);
                open.setDataAndType(fileUri, "video/*");
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(open); } catch (Exception ignored) {}
            });
        }
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && pendingUrl != null) {
            startDownload(pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMimeType);
            pendingUrl = null;
        }
    }

    private class AppBridge {
        @JavascriptInterface
        public void openPlayStore() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.nazzilhaplus.app")));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.nazzilhaplus.app")));
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
