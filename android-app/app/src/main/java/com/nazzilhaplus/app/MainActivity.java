package com.nazzilhaplus.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.core.content.ContextCompat;

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
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
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

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(filename);
        request.setDescription("نزلها بلس");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DCIM, "NazzilhaPlus/" + filename);
        request.addRequestHeader("User-Agent", userAgent);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) request.addRequestHeader("Cookie", cookie);

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        Toast.makeText(this, "⬇️ جاري التحميل...", Toast.LENGTH_SHORT).show();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;

                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);
                if (cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        String localUri = cursor.getString(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        String path = Uri.parse(localUri).getPath();
                        MediaScannerConnection.scanFile(context, new String[]{path}, null,
                                (p, uri) -> runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "✅ تم الحفظ في المعرض!", Toast.LENGTH_LONG).show()));
                    } else {
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this,
                                        "❌ فشل التحميل", Toast.LENGTH_SHORT).show());
                    }
                }
                cursor.close();
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            }
        };
        registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
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
