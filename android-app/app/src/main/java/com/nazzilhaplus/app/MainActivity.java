package com.nazzilhaplus.app;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NazzilhaPlus";
    private static final String API_BASE = "https://www.vip-dl.com";
    private static final int FREE_DAILY_LIMIT = 2;
    private static final int STORAGE_PERM_CODE = 100;

    // ── Views ────────────────────────────────────────────────────────────────
    private EditText urlInput;
    private Button pasteBtn, fetchBtn;
    private ProgressBar loadingSpinner;
    private TextView errorBox;
    private LinearLayout resultCard, formatsContainer, progressSection, hintCard;
    private ImageView thumbnail;
    private TextView platformBadge, videoTitle, progressPercent;
    private ProgressBar downloadProgress;
    private AdView bannerAdView;

    // ── Ads ──────────────────────────────────────────────────────────────────
    private RewardedInterstitialAd rewardedAd;
    private boolean rewardedAdLoading = false;
    private boolean isShowingAd = false;

    // ── Download state ───────────────────────────────────────────────────────
    private String pendingDlUrl;
    private String pendingDlFilename;
    private boolean downloadPending = false;

    private static final List<String> VIDEO_DOMAINS = Arrays.asList(
        "tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
        "instagram.com", "instagr.am",
        "facebook.com", "fb.watch",
        "pinterest.com", "pin.it",
        "twitter.com", "x.com",
        "snapchat.com", "youtube.com", "youtu.be",
        "dailymotion.com", "vimeo.com"
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();
        showDisclaimerIfNeeded();
        NotificationReceiver.createChannel(this);
        requestNotificationPermission();
        NotificationReceiver.schedule(this);

        MobileAds.initialize(this, status -> {
            bannerAdView.loadAd(new AdRequest.Builder().build());
        });
        loadRewardedAd();

        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token != null)
                getPrefs().edit().putString("fcm_token", token).apply();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoFillClipboard();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  View binding & listeners
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        urlInput        = findViewById(R.id.urlInput);
        pasteBtn        = findViewById(R.id.pasteBtn);
        fetchBtn        = findViewById(R.id.fetchBtn);
        loadingSpinner  = findViewById(R.id.loadingSpinner);
        errorBox        = findViewById(R.id.errorBox);
        resultCard      = findViewById(R.id.resultCard);
        formatsContainer= findViewById(R.id.formatsContainer);
        progressSection = findViewById(R.id.progressSection);
        hintCard        = findViewById(R.id.hintCard);
        thumbnail       = findViewById(R.id.thumbnail);
        platformBadge   = findViewById(R.id.platformBadge);
        videoTitle      = findViewById(R.id.videoTitle);
        progressPercent = findViewById(R.id.progressPercent);
        downloadProgress= findViewById(R.id.downloadProgress);
        bannerAdView    = findViewById(R.id.bannerAd);
    }

    private void setupListeners() {
        pasteBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (!clip.isEmpty()) {
                urlInput.setText(clip);
                urlInput.setSelection(clip.length());
            } else {
                Toast.makeText(this, "الحافظة فارغة", Toast.LENGTH_SHORT).show();
            }
        });

        fetchBtn.setOnClickListener(v -> fetchInfo());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clipboard
    // ══════════════════════════════════════════════════════════════════════════

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                return t != null ? t.toString().trim() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isVideoUrl(String url) {
        if (url == null || url.length() < 10) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        String lower = url.toLowerCase();
        for (String d : VIDEO_DOMAINS) {
            if (lower.contains(d)) return true;
        }
        return false;
    }

    private void autoFillClipboard() {
        String clip = getClipboardText();
        String current = urlInput.getText().toString().trim();
        if (isVideoUrl(clip) && !clip.equals(current)) {
            urlInput.setText(clip);
            urlInput.setSelection(clip.length());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Disclaimer
    // ══════════════════════════════════════════════════════════════════════════

    private void showDisclaimerIfNeeded() {
        SharedPreferences p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (p.getBoolean("disclaimer_accepted", false)) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("سياسة الاستخدام")
            .setMessage(
                "هذا التطبيق مخصص للاستخدام الشخصي فقط.\n\n" +
                "يتحمل المستخدم المسؤولية الكاملة عن المحتوى الذي يحفظه، " +
                "ويجب التأكد من امتلاك الحقوق اللازمة.\n\n" +
                "باستخدامك هذا التطبيق فأنت توافق على ذلك."
            )
            .setPositiveButton("أوافق", (d, w) ->
                p.edit().putBoolean("disclaimer_accepted", true).apply())
            .setNegativeButton("رفض", (d, w) -> finishAffinity())
            .setCancelable(false)
            .show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Fetch video info
    // ══════════════════════════════════════════════════════════════════════════

    private void fetchInfo() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            showError("أدخل رابطاً صحيحاً");
            return;
        }
        hideError();
        hideResult();
        setLoading(true);

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(API_BASE + "/api/resolve").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                String body = "{\"url\":" + JSONObject.quote(url) + "}";
                conn.getOutputStream().write(body.getBytes("UTF-8"));

                int code = conn.getResponseCode();
                if (code != 200) {
                    runOnUiThread(() -> {
                        setLoading(false);
                        showError("فشل تحليل الرابط (خطأ " + code + ")");
                    });
                    conn.disconnect();
                    return;
                }

                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                conn.disconnect();

                JSONObject resp = new JSONObject(sb.toString());
                if (resp.has("error")) {
                    String msg = resp.optString("error", "رابط غير مدعوم");
                    runOnUiThread(() -> { setLoading(false); showError(msg); });
                    return;
                }

                runOnUiThread(() -> {
                    setLoading(false);
                    displayResult(resp);
                });
            } catch (Exception e) {
                Log.e(TAG, "fetchInfo: " + e.getMessage());
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("فشل الاتصال بالخادم. تأكد من الإنترنت.");
                });
            }
        }).start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Display result
    // ══════════════════════════════════════════════════════════════════════════

    private void displayResult(JSONObject data) {
        hintCard.setVisibility(View.GONE);

        String title    = data.optString("title", "بدون عنوان");
        String platform = data.optString("platform", "");
        String thumbUrl = data.optString("thumbnail", "");
        JSONArray fmts  = data.optJSONArray("formats");

        videoTitle.setText(title);
        platformBadge.setText(platform.isEmpty() ? "فيديو" : platform.toUpperCase());

        resultCard.setVisibility(View.VISIBLE);

        // Load thumbnail in background
        if (!thumbUrl.isEmpty()) {
            String finalThumbUrl = thumbUrl;
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(finalThumbUrl).openConnection();
                    c.setConnectTimeout(10_000);
                    c.setReadTimeout(10_000);
                    c.connect();
                    Bitmap bm = BitmapFactory.decodeStream(c.getInputStream());
                    c.disconnect();
                    if (bm != null) runOnUiThread(() -> thumbnail.setImageBitmap(bm));
                } catch (Exception ignored) {}
            }).start();
        }

        // Build format buttons
        formatsContainer.removeAllViews();
        if (fmts == null || fmts.length() == 0) {
            showError("لا توجد صيغ متاحة لهذا الرابط");
            resultCard.setVisibility(View.GONE);
            return;
        }

        for (int i = 0; i < fmts.length(); i++) {
            try {
                JSONObject fmt = fmts.getJSONObject(i);
                String label   = fmt.optString("label", "تحميل");
                String dlUrl   = fmt.optString("url", "");
                String ext     = fmt.optString("ext", "mp4");
                String type    = fmt.optString("type", "video");
                if (dlUrl.isEmpty()) continue;

                String emoji   = "audio".equals(type) ? "🎵" : "🎬";
                String btnText = emoji + " " + label;
                String filename= sanitizeFilename(title) + "." + ext;

                Button btn = new Button(this);
                btn.setText(btnText);
                btn.setTextColor(0xFF1F1F2E);
                btn.setBackgroundColor(0xFFF5F3FF);
                btn.setTextSize(14f);
                btn.setPadding(24, 16, 24, 16);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, 10);
                btn.setLayoutParams(lp);

                final String finalDlUrl  = dlUrl;
                final String finalName   = filename;
                btn.setOnClickListener(v -> onFormatPicked(finalDlUrl, finalName));
                formatsContainer.addView(btn);
            } catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Format picked → quota check → ad → download
    // ══════════════════════════════════════════════════════════════════════════

    private void onFormatPicked(String dlUrl, String filename) {
        pendingDlUrl      = dlUrl;
        pendingDlFilename = filename;
        downloadPending   = true;

        if (canDownloadFree()) {
            incrementDownloadCount();
            beginDownload();
        } else {
            // Free limit reached — must watch ad
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("تجاوزت الحد اليومي")
                .setMessage("استنفذت " + FREE_DAILY_LIMIT + " تحميلات مجانية اليوم.\nشاهد إعلاناً قصيراً للمتابعة.")
                .setPositiveButton("شاهد الإعلان", (d, w) -> showRewardedAd())
                .setNegativeButton("إلغاء", null)
                .show();
        }
    }

    // ── daily quota ──────────────────────────────────────────────────────────

    private boolean canDownloadFree() {
        String today = todayKey();
        SharedPreferences p = getPrefs();
        if (!today.equals(p.getString("dl_date", ""))) return true;
        return p.getInt("dl_count", 0) < FREE_DAILY_LIMIT;
    }

    private void incrementDownloadCount() {
        String today = todayKey();
        SharedPreferences p = getPrefs();
        String saved = p.getString("dl_date", "");
        int count = today.equals(saved) ? p.getInt("dl_count", 0) : 0;
        p.edit().putString("dl_date", today).putInt("dl_count", count + 1).apply();
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AdMob rewarded interstitial
    // ══════════════════════════════════════════════════════════════════════════

    private void loadRewardedAd() {
        if (rewardedAdLoading) return;
        rewardedAdLoading = true;
        RewardedInterstitialAd.load(this,
            getString(R.string.admob_rewarded_interstitial_id),
            new AdRequest.Builder().build(),
            new RewardedInterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull RewardedInterstitialAd ad) {
                    rewardedAd        = ad;
                    rewardedAdLoading = false;
                }
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError e) {
                    rewardedAd        = null;
                    rewardedAdLoading = false;
                    Log.w(TAG, "Rewarded ad failed: " + e.getMessage());
                }
            });
    }

    private void showRewardedAd() {
        if (rewardedAd == null || isShowingAd) {
            Toast.makeText(this, "الإعلان غير جاهز، جرّب مجدداً", Toast.LENGTH_SHORT).show();
            if (!rewardedAdLoading) loadRewardedAd();
            return;
        }
        isShowingAd = true;
        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewardedAd  = null;
                isShowingAd = false;
                loadRewardedAd();
            }
            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                rewardedAd  = null;
                isShowingAd = false;
                loadRewardedAd();
            }
        });
        rewardedAd.show(this, reward -> {
            // User earned reward → start download
            runOnUiThread(this::beginDownload);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Download
    // ══════════════════════════════════════════════════════════════════════════

    private void beginDownload() {
        if (!downloadPending || pendingDlUrl == null) return;
        downloadPending = false;
        String url  = pendingDlUrl;
        String name = pendingDlFilename != null ? pendingDlFilename : "video.mp4";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERM_CODE);
            return;
        }
        showProgressSection(true);
        new Thread(() -> doDownload(url, name)).start();
    }

    private void doDownload(String url, String filename) {
        int notifId = filename.hashCode();
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, NotificationReceiver.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(filename)
            .setContentText("جاري التحميل...")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        try { nm.notify(notifId, nb.build()); } catch (Exception ignored) {}

        Uri[] resultUri = {null};
        boolean ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? downloadMediaStore(url, filename, notifId, nb, nm, resultUri)
            : downloadFileSystem(url, filename, notifId, nb, nm, resultUri);

        nm.cancel(notifId);
        if (ok) {
            Uri fu = resultUri[0];
            runOnUiThread(() -> {
                showProgressSection(false);
                showSuccessDialog(fu);
            });
        } else {
            runOnUiThread(() -> {
                showProgressSection(false);
                showError("فشل التحميل، حاول مجدداً");
            });
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private boolean downloadMediaStore(String url, String filename, int notifId,
            NotificationCompat.Builder nb, NotificationManagerCompat nm, Uri[] out) {
        Uri dlUri = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long existing = 0;
                if (dlUri == null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mimeFor(filename));
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NazzilhaPlus");
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    dlUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (dlUri == null) return false;
                } else {
                    android.database.Cursor c = getContentResolver().query(
                        dlUri, new String[]{MediaStore.Downloads.SIZE}, null, null, null);
                    if (c != null) { if (c.moveToFirst()) existing = c.getLong(0); c.close(); }
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
                conn.setConnectTimeout(30_000); conn.setReadTimeout(60_000); conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;
                long total = existing + conn.getContentLengthLong();
                String mode = (code == 206 && existing > 0) ? "wa" : "w";
                try (InputStream in = conn.getInputStream();
                     OutputStream os = getContentResolver().openOutputStream(dlUri, mode)) {
                    if (os == null) break;
                    byte[] buf = new byte[8192]; int read; long done = existing;
                    while ((read = in.read(buf)) != -1) {
                        os.write(buf, 0, read); done += read;
                        if (total > 0) {
                            int pct = (int)(done * 100L / total);
                            runOnUiThread(() -> updateProgress(pct));
                            nb.setProgress(100, pct, false).setContentText(pct + "%");
                            try { nm.notify(notifId, nb.build()); } catch (Exception ignored) {}
                        }
                    }
                }
                ContentValues cv2 = new ContentValues();
                cv2.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(dlUri, cv2, null, null);
                out[0] = dlUri;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "downloadMediaStore attempt " + attempt + ": " + e.getMessage());
                if (attempt < 4) try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ig) {}
            }
        }
        if (dlUri != null) try { getContentResolver().delete(dlUri, null, null); } catch (Exception ig) {}
        return false;
    }

    private boolean downloadFileSystem(String url, String filename, int notifId,
            NotificationCompat.Builder nb, NotificationManagerCompat nm, Uri[] out) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NazzilhaPlus");
        dir.mkdirs();
        File file = new File(dir, filename);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long existing = file.exists() ? file.length() : 0;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
                conn.setConnectTimeout(30_000); conn.setReadTimeout(60_000); conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;
                long total = existing + conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(file, code == 206 && existing > 0)) {
                    byte[] buf = new byte[8192]; int read; long done = existing;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read); done += read;
                        if (total > 0) {
                            int pct = (int)(done * 100L / total);
                            runOnUiThread(() -> updateProgress(pct));
                            nb.setProgress(100, pct, false).setContentText(pct + "%");
                            try { nm.notify(notifId, nb.build()); } catch (Exception ig) {}
                        }
                    }
                }
                Uri[] scanned = {null};
                Object lock = new Object();
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, (p, u) -> {
                    scanned[0] = u;
                    synchronized (lock) { lock.notifyAll(); }
                });
                synchronized (lock) { try { lock.wait(3000); } catch (InterruptedException ig) {} }
                out[0] = scanned[0] != null ? scanned[0] : Uri.fromFile(file);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "downloadFileSystem attempt " + attempt + ": " + e.getMessage());
                if (attempt < 4) try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ig) {}
            }
        }
        return false;
    }

    private void showSuccessDialog(Uri fileUri) {
        androidx.appcompat.app.AlertDialog.Builder d =
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("✅ اكتمل التحميل")
                .setMessage("تم الحفظ في Downloads/NazzilhaPlus")
                .setNegativeButton("حسناً", null);
        if (fileUri != null) {
            d.setPositiveButton("فتح الملف", (dlg, w) -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(fileUri, "video/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }
        d.show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UI helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void setLoading(boolean on) {
        loadingSpinner.setVisibility(on ? View.VISIBLE : View.GONE);
        fetchBtn.setEnabled(!on);
        fetchBtn.setAlpha(on ? 0.6f : 1f);
    }

    private void showError(String msg) {
        errorBox.setText("⚠ " + msg);
        errorBox.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorBox.setVisibility(View.GONE);
    }

    private void hideResult() {
        resultCard.setVisibility(View.GONE);
        progressSection.setVisibility(View.GONE);
        hintCard.setVisibility(View.VISIBLE);
    }

    private void showProgressSection(boolean on) {
        progressSection.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            downloadProgress.setProgress(0);
            progressPercent.setText("0%");
        }
    }

    private void updateProgress(int pct) {
        downloadProgress.setProgress(pct);
        progressPercent.setText(pct + "%");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == STORAGE_PERM_CODE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginDownload();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Utilities
    // ══════════════════════════════════════════════════════════════════════════

    private SharedPreferences getPrefs() {
        return getSharedPreferences("nazzilha_prefs", MODE_PRIVATE);
    }

    private String sanitizeFilename(String title) {
        if (title == null || title.isEmpty()) return "video";
        return title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String mimeFor(String filename) {
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        if (filename.endsWith(".m4a")) return "audio/mp4";
        return "video/mp4";
    }
}
