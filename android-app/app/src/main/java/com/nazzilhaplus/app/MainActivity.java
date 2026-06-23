package com.nazzilhaplus.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
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
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ─── AdMob IDs ────────────────────────────────────────────────────────────
    private static final String APP_OPEN_AD_ID    = "ca-app-pub-9098461798177099/7874630902";
    private static final String INTERSTITIAL_AD_ID = "ca-app-pub-9098461798177099/7269905917";

    // ─── WebView ───────────────────────────────────────────────────────────────
    private WebView webView;
    private boolean pageLoaded      = false;
    private boolean firstPageDone   = false;
    private boolean isShowingAd     = false;

    // ─── Ads ───────────────────────────────────────────────────────────────────
    private AppOpenAd    appOpenAd;
    private boolean      appOpenLoading = false;
    private InterstitialAd interstitialAd;

    // ─── Download ──────────────────────────────────────────────────────────────
    private static final int STORAGE_PERMISSION_CODE = 100;
    private String pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMimeType;

    // ─── Clipboard domains ─────────────────────────────────────────────────────
    private static final List<String> VIDEO_DOMAINS = Arrays.asList(
        "tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
        "instagram.com", "instagr.am",
        "facebook.com", "fb.watch",
        "pinterest.com", "pin.it",
        "twitter.com", "x.com",
        "youtube.com", "youtu.be",
        "snapchat.com"
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MobileAds.initialize(this, initStatus -> {});

        webView = findViewById(R.id.webview);
        setupWebView();
        webView.loadUrl("https://www.vip-dl.com");

        NotificationReceiver.createChannel(this);
        requestNotificationPermission();
        NotificationReceiver.schedule(this);

        loadAppOpenAd();
        loadInterstitialAd();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageLoaded) injectClipboardUrl();
        if (!isShowingAd && appOpenAd != null && firstPageDone) {
            showAppOpenAd();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WebView setup
    // ══════════════════════════════════════════════════════════════════════════

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
                String url    = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();

                // فتح تيليغرام في التطبيق
                if (url.contains("t.me/") || url.contains("telegram.me/")) {
                    openExternal(url);
                    return true;
                }

                if (url.contains("play.google.com") || url.startsWith("market://")
                        || "whatsapp".equals(scheme) || "tg".equals(scheme)
                        || "instagram".equals(scheme) || "fb".equals(scheme)
                        || "twitter".equals(scheme) || "snapchat".equals(scheme)
                        || "intent".equals(scheme)) {
                    try {
                        openExternal(url);
                    } catch (Exception e) {
                        view.loadUrl(url);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                injectClipboardUrl();

                if (!firstPageDone) {
                    // إعلان App Open عند أول تحميل
                    firstPageDone = true;
                    if (appOpenAd != null) {
                        showAppOpenAd();
                    }
                } else {
                    // إعلان Interstitial عند كل تحميل لاحق
                    if (interstitialAd != null && !isShowingAd) {
                        showInterstitialAd();
                    }
                }
            }
        });

        webView.addJavascriptInterface(new AppBridge(), "AndroidClipboard");

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                pendingUrl              = url;
                pendingUserAgent        = userAgent;
                pendingContentDisposition = contentDisposition;
                pendingMimeType         = mimeType;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
            } else {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clipboard
    // ══════════════════════════════════════════════════════════════════════════

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                return text != null ? text.toString().trim() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isVideoUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        String lower = url.toLowerCase();
        for (String domain : VIDEO_DOMAINS) {
            if (lower.contains(domain)) return true;
        }
        return false;
    }

    private void injectClipboardUrl() {
        String clip = getClipboardText();
        if (!isVideoUrl(clip)) return;
        String safe = clip.replace("\\", "\\\\").replace("'", "\\'")
                         .replace("\n", "").replace("\r", "");
        webView.evaluateJavascript(
            "(function(){" +
            "  var inp=document.getElementById('urlInput');" +
            "  if(inp && inp.value!=='" + safe + "'){" +
            "    inp.value='" + safe + "';" +
            "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
            "  }" +
            "})();",
            null
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AdMob — App Open Ad
    // ══════════════════════════════════════════════════════════════════════════

    private void loadAppOpenAd() {
        if (appOpenLoading) return;
        appOpenLoading = true;
        AppOpenAd.load(this, APP_OPEN_AD_ID, new AdRequest.Builder().build(),
            new AppOpenAd.AppOpenAdLoadCallback() {
                @Override public void onAdLoaded(@NonNull AppOpenAd ad) {
                    appOpenAd     = ad;
                    appOpenLoading = false;
                }
                @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    appOpenAd     = null;
                    appOpenLoading = false;
                }
            });
    }

    private void showAppOpenAd() {
        if (appOpenAd == null || isShowingAd) return;
        isShowingAd = true;
        appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                appOpenAd   = null;
                isShowingAd = false;
                loadAppOpenAd();
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                appOpenAd   = null;
                isShowingAd = false;
                loadAppOpenAd();
            }
        });
        appOpenAd.show(this);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AdMob — Interstitial Ad
    // ══════════════════════════════════════════════════════════════════════════

    private void loadInterstitialAd() {
        InterstitialAd.load(this, INTERSTITIAL_AD_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override public void onAdLoaded(@NonNull InterstitialAd ad) {
                    interstitialAd = ad;
                }
                @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    interstitialAd = null;
                }
            });
    }

    private void showInterstitialAd() {
        if (interstitialAd == null || isShowingAd) return;
        isShowingAd = true;
        interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                interstitialAd = null;
                isShowingAd    = false;
                loadInterstitialAd();
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                interstitialAd = null;
                isShowingAd    = false;
                loadInterstitialAd();
            }
        });
        interstitialAd.show(this);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Download
    // ══════════════════════════════════════════════════════════════════════════

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
        boolean success = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ? downloadViaMediaStore(url, userAgent, filename, notifId, builder, nm, resultUri)
                : downloadViaFileSystem(url, userAgent, filename, notifId, builder, nm, resultUri);

        nm.cancel(notifId);
        if (success) {
            final Uri fu = resultUri[0];
            runOnUiThread(() -> showSuccessDialog(fu));
        } else {
            runOnUiThread(() -> Toast.makeText(this, "❌ فشل التحميل، يرجى المحاولة مجددًا", Toast.LENGTH_LONG).show());
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private boolean downloadViaMediaStore(String url, String userAgent, String filename,
            int notifId, NotificationCompat.Builder builder, NotificationManagerCompat nm, Uri[] resultUri) {
        Uri downloadUri = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long existingSize = 0;
                if (downloadUri == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    v.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NazzilhaPlus");
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    downloadUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (downloadUri == null) return false;
                } else {
                    android.database.Cursor c = getContentResolver().query(
                            downloadUri, new String[]{MediaStore.Downloads.SIZE}, null, null, null);
                    if (c != null) { if (c.moveToFirst()) existingSize = c.getLong(0); c.close(); }
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                if (existingSize > 0) conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
                conn.setConnectTimeout(30_000); conn.setReadTimeout(60_000); conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;
                long totalSize = existingSize + conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(downloadUri, (code==206 && existingSize>0)?"wa":"w")) {
                    if (out == null) break;
                    byte[] buf = new byte[8192]; int read; long dl = existingSize;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read); dl += read;
                        if (totalSize > 0) {
                            int pct = (int)(dl * 100L / totalSize);
                            builder.setProgress(100, pct, false).setContentText(pct + "%");
                            try { nm.notify(notifId, builder.build()); } catch (Exception ignored) {}
                        }
                    }
                }
                ContentValues done = new ContentValues(); done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(downloadUri, done, null, null);
                resultUri[0] = downloadUri; return true;
            } catch (Exception e) {
                if (attempt < 4) try { Thread.sleep(2000L*(attempt+1)); } catch (InterruptedException ignored) {}
            }
        }
        if (downloadUri != null) try { getContentResolver().delete(downloadUri, null, null); } catch (Exception ignored) {}
        return false;
    }

    private boolean downloadViaFileSystem(String url, String userAgent, String filename,
            int notifId, NotificationCompat.Builder builder, NotificationManagerCompat nm, Uri[] resultUri) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NazzilhaPlus");
        dir.mkdirs();
        File out = new File(dir, filename);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long existingSize = out.exists() ? out.length() : 0;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                if (existingSize > 0) conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
                conn.setConnectTimeout(30_000); conn.setReadTimeout(60_000); conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;
                long totalSize = existingSize + conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out, code==206 && existingSize>0)) {
                    byte[] buf = new byte[8192]; int read; long dl = existingSize;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read); dl += read;
                        if (totalSize > 0) {
                            int pct = (int)(dl * 100L / totalSize);
                            builder.setProgress(100, pct, false).setContentText(pct + "%");
                            try { nm.notify(notifId, builder.build()); } catch (Exception ignored) {}
                        }
                    }
                }
                Uri[] scanned = new Uri[]{null};
                Object lock = new Object();
                MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()}, null, (p, u) -> {
                    scanned[0] = u; synchronized(lock){ lock.notifyAll(); }
                });
                synchronized(lock){ try{ lock.wait(3000); } catch(InterruptedException ignored){} }
                resultUri[0] = scanned[0] != null ? scanned[0] : Uri.fromFile(out);
                return true;
            } catch (Exception e) {
                if (attempt < 4) try { Thread.sleep(2000L*(attempt+1)); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    private void showSuccessDialog(Uri fileUri) {
        androidx.appcompat.app.AlertDialog.Builder d =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("✅ اكتمل التحميل")
                        .setMessage("تم حفظ الفيديو في Downloads/NazzilhaPlus")
                        .setNegativeButton("حسناً", null);
        if (fileUri != null) {
            d.setPositiveButton("فتح الفيديو", (dlg, w) -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(fileUri, "video/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }
        d.show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  JavaScript Bridge
    // ══════════════════════════════════════════════════════════════════════════

    private class AppBridge {

        @JavascriptInterface
        public String getClipboard() {
            return getClipboardText();
        }

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

    // ══════════════════════════════════════════════════════════════════════════
    //  Permissions & Back
    // ══════════════════════════════════════════════════════════════════════════

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
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

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
