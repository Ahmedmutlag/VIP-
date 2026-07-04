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
import android.util.Log;
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
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NazzilhaPlus";

    // ─── WebView ───────────────────────────────────────────────────────────────
    private WebView webView;
    private boolean pageLoaded = false;

    // ─── Rewarded Interstitial Ad ──────────────────────────────────────────────
    private RewardedInterstitialAd rewardedAd;
    private boolean rewardedAdLoading = false;
    private boolean isShowingAd = false;
    private final AtomicReference<String> pendingAdToken = new AtomicReference<>(null);

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

        loadRewardedInterstitialAd();

        // Handle App Link or deep link that launched the app
        handleAdIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAdIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageLoaded) injectClipboardUrl();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  App Link / Deep Link handling
    // ══════════════════════════════════════════════════════════════════════════

    private void handleAdIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String host = data.getHost();
        if (!"www.vip-dl.com".equals(host) && !"vip-dl.com".equals(host)) return;
        String path = data.getPath();
        if (path != null && path.startsWith("/watch-ad/")) {
            String url = data.toString();
            if (webView != null) {
                webView.post(() -> webView.loadUrl(url));
            }
        }
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
            }
        });

        webView.addJavascriptInterface(new AppBridge(), "AndroidClipboard");

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                pendingUrl                = url;
                pendingUserAgent          = userAgent;
                pendingContentDisposition = contentDisposition;
                pendingMimeType           = mimeType;
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
            if (lower.contains("://" + domain) || lower.contains("." + domain)) return true;
        }
        return false;
    }

    private void injectClipboardUrl() {
        String clip = getClipboardText();
        if (!isVideoUrl(clip)) return;
        String safeUrl = JSONObject.quote(clip);
        webView.evaluateJavascript(
            "(function(){" +
            "  var url=" + safeUrl + ";" +
            "  var inp=document.getElementById('urlInput');" +
            "  if(inp && inp.value!==url){" +
            "    inp.value=url;" +
            "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
            "  }" +
            "})();",
            null
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AdMob — Rewarded Interstitial Ad
    // ══════════════════════════════════════════════════════════════════════════

    private void loadRewardedInterstitialAd() {
        if (rewardedAdLoading) return;
        rewardedAdLoading = true;
        RewardedInterstitialAd.load(this,
            getString(R.string.admob_rewarded_interstitial_id),
            new AdRequest.Builder().build(),
            new RewardedInterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull RewardedInterstitialAd ad) {
                    rewardedAd = ad;
                    rewardedAdLoading = false;
                    // Show immediately if watchAd was called while the ad was loading
                    if (pendingAdToken.get() != null && !isShowingAd) {
                        runOnUiThread(() -> showRewardedInterstitialAd());
                    }
                }
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    rewardedAd = null;
                    rewardedAdLoading = false;
                    Log.w(TAG, "Rewarded interstitial failed to load: " + error.getMessage());
                }
            });
    }

    private void showRewardedInterstitialAd() {
        if (rewardedAd == null || isShowingAd) {
            // Ad not ready yet — notify the page
            webView.evaluateJavascript("window.adNotReady && window.adNotReady()", null);
            if (!rewardedAdLoading) loadRewardedInterstitialAd();
            return;
        }
        isShowingAd = true;
        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewardedAd = null;
                isShowingAd = false;
                loadRewardedInterstitialAd();
            }
            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                rewardedAd = null;
                isShowingAd = false;
                loadRewardedInterstitialAd();
                webView.post(() ->
                    webView.evaluateJavascript("window.adNotReady && window.adNotReady()", null));
            }
        });
        rewardedAd.show(this, rewardItem -> {
            // Atomically claim the token so concurrent calls can't double-redeem
            String token = pendingAdToken.getAndSet(null);
            new Thread(() -> callAdRewardApi(token)).start();
        });
    }

    private void callAdRewardApi(String token) {
        if (token == null || token.isEmpty()) return;
        // Show success immediately — user already earned the reward from AdMob
        runOnUiThread(() ->
            webView.evaluateJavascript("window.adWatchedSuccess && window.adWatchedSuccess()", null));
        // Notify backend with retries (server may be waking up from sleep)
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                URL url = new URL("https://vip-dl.com/api/ad-reward/" + token);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(30_000);
                conn.connect();
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) return;
                Log.w(TAG, "ad-reward attempt " + attempt + " returned " + code);
            } catch (Exception e) {
                Log.e(TAG, "callAdRewardApi attempt " + attempt + " failed: " + e.getMessage());
                try { Thread.sleep(3000L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
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
                String mode = (code == 206 && existingSize > 0) ? "wa" : "w";
                try (InputStream in = conn.getInputStream();
                     OutputStream out = getContentResolver().openOutputStream(downloadUri, mode)) {
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
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(downloadUri, done, null, null);
                resultUri[0] = downloadUri;
                return true;
            } catch (Exception e) {
                if (attempt < 4) try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
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
                     FileOutputStream fos = new FileOutputStream(out, code == 206 && existingSize > 0)) {
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
                    scanned[0] = u;
                    synchronized (lock) { lock.notifyAll(); }
                });
                synchronized (lock) { try { lock.wait(3000); } catch (InterruptedException ignored) {} }
                resultUri[0] = scanned[0] != null ? scanned[0] : Uri.fromFile(out);
                return true;
            } catch (Exception e) {
                if (attempt < 4) try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
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

        @JavascriptInterface
        public void watchAd(String token) {
            if (token == null || token.isEmpty()) return;
            pendingAdToken.set(token);
            runOnUiThread(() -> showRewardedInterstitialAd());
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
