package com.nazzilhaplus.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NazzilhaPlus";
    private static final String BACKEND = "https://www.vip-dl.com";
    private static final String PREFS = "nazzilha_prefs";
    private static final int FREE_DAILY_LIMIT = 2;

    // ── UI fields ──────────────────────────────────────────────────────────────
    private EditText urlInput;
    private Button pasteBtn, analyzeBtn, btnVideo, btnAudio, downloadBtn, watchAdBtn, subscribeBtn;
    private LinearLayout formatSection, qualitySection, progressSection;
    private ProgressBar loadingBar, downloadProgress;
    private TextView statusText, videoTitle, progressText, downloadCounter;
    private AdView bannerAdView;

    // ── AdMob ──────────────────────────────────────────────────────────────────
    private RewardedInterstitialAd rewardedAd;
    private boolean rewardedAdLoading = false;

    // ── State ──────────────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private JSONArray resolvedFormats;
    private String selectedType = "video";
    private int selectedFormatIndex = 0;
    private boolean adEarned = false;

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindViews();
        setupClickListeners();

        // Initialise AdMob then load ads
        MobileAds.initialize(this, initStatus -> {
            bannerAdView.loadAd(new AdRequest.Builder().build());
            loadRewardedInterstitialAd();
        });

        updateDownloadCounter();
        autoDetectClipboard();
        showDisclaimerIfNeeded();

        // FCM token — kept for downstream push notifications
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) return;
            String fcmToken = task.getResult();
            if (fcmToken != null) {
                prefs.edit().putString("fcm_token", fcmToken).apply();
            }
        });

        NotificationReceiver.createChannel(this);
        requestNotificationPermission();
        NotificationReceiver.schedule(this);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  View binding
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        urlInput        = findViewById(R.id.urlInput);
        pasteBtn        = findViewById(R.id.pasteBtn);
        analyzeBtn      = findViewById(R.id.analyzeBtn);
        statusText      = findViewById(R.id.statusText);
        loadingBar      = findViewById(R.id.loadingBar);
        formatSection   = findViewById(R.id.formatSection);
        videoTitle      = findViewById(R.id.videoTitle);
        btnVideo        = findViewById(R.id.btnVideo);
        btnAudio        = findViewById(R.id.btnAudio);
        qualitySection  = findViewById(R.id.qualitySection);
        downloadBtn     = findViewById(R.id.downloadBtn);
        progressSection = findViewById(R.id.progressSection);
        downloadProgress = findViewById(R.id.downloadProgress);
        progressText    = findViewById(R.id.progressText);
        downloadCounter = findViewById(R.id.downloadCounter);
        watchAdBtn      = findViewById(R.id.watchAdBtn);
        subscribeBtn    = findViewById(R.id.subscribeBtn);
        bannerAdView    = findViewById(R.id.bannerAd);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Click listeners
    // ══════════════════════════════════════════════════════════════════════════

    private void setupClickListeners() {

        pasteBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (!clip.isEmpty()) {
                urlInput.setText(clip);
                urlInput.setSelection(urlInput.getText().length());
            }
        });

        analyzeBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                showStatus("⚠️ أدخل رابط الفيديو أولاً", true);
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                showStatus("⚠️ الرابط يجب أن يبدأ بـ https://", true);
                return;
            }
            resolveUrl(url);
        });

        btnVideo.setOnClickListener(v -> {
            selectedType = "video";
            qualitySection.setVisibility(View.VISIBLE);
            downloadBtn.setVisibility(View.VISIBLE);
            btnVideo.setAlpha(1.0f);
            btnAudio.setAlpha(0.5f);
        });

        btnAudio.setOnClickListener(v -> {
            selectedType = "audio";
            qualitySection.setVisibility(View.GONE);
            downloadBtn.setVisibility(View.VISIBLE);
            btnVideo.setAlpha(0.5f);
            btnAudio.setAlpha(1.0f);
        });

        downloadBtn.setOnClickListener(v -> startDownloadFlow());

        watchAdBtn.setOnClickListener(v -> showRewardedAd());

        subscribeBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(BACKEND + "/subscribe"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception ignored) {}
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clipboard auto-detect
    // ══════════════════════════════════════════════════════════════════════════

    private void autoDetectClipboard() {
        try {
            String clip = getClipboardText();
            if (isVideoUrl(clip)) {
                urlInput.setText(clip);
                showStatus("✅ تم اكتشاف رابط", true);
            }
        } catch (Exception e) {
            Log.e(TAG, "autoDetectClipboard: " + e.getMessage());
        }
    }

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
        String[] domains = {
            "tiktok.com", "instagram.com", "facebook.com", "fb.watch",
            "twitter.com", "x.com", "pinterest.com", "pin.it", "snapchat.com"
        };
        for (String d : domains) {
            if (lower.contains(d)) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  URL resolution  (POST /api/resolve)
    // ══════════════════════════════════════════════════════════════════════════

    private void resolveUrl(String url) {
        // Reset UI
        formatSection.setVisibility(View.GONE);
        downloadBtn.setVisibility(View.GONE);
        qualitySection.setVisibility(View.GONE);
        progressSection.setVisibility(View.GONE);
        watchAdBtn.setVisibility(View.GONE);
        loadingBar.setVisibility(View.VISIBLE);
        showStatus("⏳ جاري التحليل...", true);
        analyzeBtn.setEnabled(false);

        executor.execute(() -> {
            try {
                URL apiUrl = new URL(BACKEND + "/api/resolve");
                HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(30_000);
                conn.setDoOutput(true);

                String body = "{\"url\":" + JSONObject.quote(url) + "}";
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                if (code != 200) {
                    String errMsg = "❌ فشل التحليل";
                    try {
                        JSONObject errObj = new JSONObject(sb.toString());
                        String e = errObj.optString("error", "");
                        if (!e.isEmpty()) errMsg = "❌ " + e;
                    } catch (Exception ignored) {}
                    final String msg = errMsg;
                    mainHandler.post(() -> {
                        loadingBar.setVisibility(View.GONE);
                        showStatus(msg, true);
                        analyzeBtn.setEnabled(true);
                    });
                    return;
                }

                JSONObject result = new JSONObject(sb.toString());
                final JSONArray formats = result.optJSONArray("formats");
                final String title = result.optString("title", "");

                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    analyzeBtn.setEnabled(true);

                    if (formats == null || formats.length() == 0) {
                        showStatus("❌ لم يتم العثور على صيغ للتحميل", true);
                        return;
                    }

                    resolvedFormats = formats;
                    showStatus("✅ تم التحليل بنجاح", true);
                    videoTitle.setText(title.isEmpty() ? "فيديو" : title);
                    formatSection.setVisibility(View.VISIBLE);

                    // Default to video mode
                    selectedType = "video";
                    selectedFormatIndex = 0;
                    btnVideo.setAlpha(1.0f);
                    btnAudio.setAlpha(0.5f);

                    buildQualityButtons();
                    qualitySection.setVisibility(View.VISIBLE);
                    downloadBtn.setVisibility(View.VISIBLE);
                });

            } catch (Exception e) {
                Log.e(TAG, "resolveUrl error: " + e.getMessage());
                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    showStatus("❌ خطأ في الاتصال، تحقق من الإنترنت", true);
                    analyzeBtn.setEnabled(true);
                });
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Quality button builder
    // ══════════════════════════════════════════════════════════════════════════

    private void buildQualityButtons() {
        qualitySection.removeAllViews();
        if (resolvedFormats == null) return;

        int firstVideoIndex = -1;

        for (int i = 0; i < resolvedFormats.length(); i++) {
            try {
                JSONObject fmt = resolvedFormats.getJSONObject(i);
                if (!"video".equals(fmt.optString("type"))) continue;
                if (firstVideoIndex < 0) firstVideoIndex = i;

                String label = fmt.optString("label", "");
                if (label.isEmpty()) label = fmt.optString("id", "فيديو");

                final int finalIndex = i;
                final String finalLabel = label;

                Button btn = new Button(this);
                btn.setText(finalLabel);
                btn.setTextColor(0xFFFFFFFF);
                btn.setBackgroundColor(0xFF7c3aed);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(8, 4, 8, 4);
                btn.setLayoutParams(lp);
                btn.setAlpha(0.55f);

                btn.setOnClickListener(v -> {
                    selectedFormatIndex = finalIndex;
                    // Reset all to dim, highlight this one
                    for (int j = 0; j < qualitySection.getChildCount(); j++) {
                        qualitySection.getChildAt(j).setAlpha(0.55f);
                    }
                    btn.setAlpha(1.0f);
                });

                qualitySection.addView(btn);
            } catch (Exception e) {
                Log.e(TAG, "buildQualityButtons: " + e.getMessage());
            }
        }

        if (firstVideoIndex >= 0) {
            selectedFormatIndex = firstVideoIndex;
        }

        // Highlight the first quality button
        if (qualitySection.getChildCount() > 0) {
            qualitySection.getChildAt(0).setAlpha(1.0f);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Download flow
    // ══════════════════════════════════════════════════════════════════════════

    private void startDownloadFlow() {
        if (isPremium() || adEarned) {
            adEarned = false;
            doDownload();
            return;
        }

        int dlCount = getTodayDownloads();
        if (dlCount < FREE_DAILY_LIMIT) {
            doDownload();
        } else {
            showStatus("⚠️ استنفذت تحميلاتك المجانية اليوم (" + FREE_DAILY_LIMIT + ")", true);
            watchAdBtn.setVisibility(View.VISIBLE);
        }
    }

    private void doDownload() {
        if (resolvedFormats == null || resolvedFormats.length() == 0) {
            showStatus("❌ لا توجد صيغ متاحة للتحميل", true);
            return;
        }

        String downloadUrl = null;
        String ext = "mp4";
        String title = videoTitle.getText().toString();

        try {
            if ("audio".equals(selectedType)) {
                // Find the first audio format
                for (int i = 0; i < resolvedFormats.length(); i++) {
                    JSONObject fmt = resolvedFormats.getJSONObject(i);
                    if ("audio".equals(fmt.optString("type"))) {
                        downloadUrl = fmt.optString("url", "");
                        ext = fmt.optString("ext", "mp3");
                        break;
                    }
                }
                // If no dedicated audio, fall back to last format
                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    JSONObject fmt = resolvedFormats.getJSONObject(resolvedFormats.length() - 1);
                    downloadUrl = fmt.optString("url", "");
                    ext = fmt.optString("ext", "mp3");
                }
            } else {
                JSONObject fmt = resolvedFormats.getJSONObject(selectedFormatIndex);
                downloadUrl = fmt.optString("url", "");
                ext = fmt.optString("ext", "mp4");
            }
        } catch (Exception e) {
            Log.e(TAG, "doDownload – format pick: " + e.getMessage());
            showStatus("❌ خطأ في اختيار الصيغة", true);
            return;
        }

        if (downloadUrl == null || downloadUrl.isEmpty()) {
            showStatus("❌ رابط التحميل غير متوفر لهذه الصيغة", true);
            return;
        }

        // Sanitize filename: allow Arabic, Latin, digits, spaces, dash, underscore
        String safeName = title.replaceAll("[^\\u0600-\\u06FFa-zA-Z0-9 _\\-]", "").trim();
        if (safeName.isEmpty()) safeName = "video_" + System.currentTimeMillis();
        String filename = safeName + "." + ext;

        incrementTodayDownloads();
        updateDownloadCounter();

        progressSection.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(0);
        progressText.setText("0%");

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            showStatus("❌ خدمة التحميل غير متاحة", true);
            return;
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl));
        req.setTitle(filename);
        req.setDescription("NazzilhaPlus – جاري التحميل...");
        req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "NazzilhaPlus/" + filename);

        long dlId;
        try {
            dlId = dm.enqueue(req);
        } catch (Exception e) {
            Log.e(TAG, "DownloadManager.enqueue: " + e.getMessage());
            showStatus("❌ فشل بدء التحميل", true);
            return;
        }

        trackDownloadProgress(dm, dlId);
    }

    private void trackDownloadProgress(DownloadManager dm, long dlId) {
        executor.execute(() -> {
            boolean running = true;
            while (running) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(dlId);
                Cursor cursor = null;
                try {
                    cursor = dm.query(query);
                    if (cursor == null || !cursor.moveToFirst()) {
                        running = false;
                        break;
                    }

                    int statusCol     = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalCol      = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);

                    int  status     = statusCol     >= 0 ? cursor.getInt(statusCol)  : -1;
                    long downloaded = downloadedCol >= 0 ? cursor.getLong(downloadedCol) : 0;
                    long total      = totalCol      >= 0 ? cursor.getLong(totalCol)   : 0;

                    final int pct = (total > 0) ? (int) (downloaded * 100L / total) : 0;

                    switch (status) {
                        case DownloadManager.STATUS_RUNNING:
                        case DownloadManager.STATUS_PENDING: {
                            mainHandler.post(() -> {
                                downloadProgress.setProgress(pct);
                                progressText.setText(pct + "%");
                            });
                            break;
                        }
                        case DownloadManager.STATUS_SUCCESSFUL: {
                            mainHandler.post(() -> {
                                downloadProgress.setProgress(100);
                                progressText.setText("✅ اكتمل التحميل!");
                                showStatus("✅ تم الحفظ في Downloads/NazzilhaPlus", true);
                            });
                            running = false;
                            break;
                        }
                        case DownloadManager.STATUS_FAILED: {
                            mainHandler.post(() -> {
                                progressText.setText("❌ فشل التحميل");
                                showStatus("❌ فشل التحميل، يرجى المحاولة مجدداً", true);
                            });
                            running = false;
                            break;
                        }
                        default:
                            // STATUS_PAUSED or other — keep polling
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "trackDownloadProgress: " + e.getMessage());
                    running = false;
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AdMob — Rewarded Interstitial
    // ══════════════════════════════════════════════════════════════════════════

    private void loadRewardedInterstitialAd() {
        if (rewardedAdLoading) return;
        rewardedAdLoading = true;
        RewardedInterstitialAd.load(
                this,
                getString(R.string.admob_rewarded_interstitial_id),
                new AdRequest.Builder().build(),
                new RewardedInterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedInterstitialAd ad) {
                        rewardedAd = ad;
                        rewardedAdLoading = false;
                        Log.d(TAG, "Rewarded interstitial loaded");
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        rewardedAd = null;
                        rewardedAdLoading = false;
                        Log.w(TAG, "Rewarded interstitial failed: " + error.getMessage());
                    }
                });
    }

    private void showRewardedAd() {
        if (rewardedAd == null) {
            Toast.makeText(this, "⏳ الإعلان غير جاهز بعد، حاول مرة أخرى", Toast.LENGTH_SHORT).show();
            if (!rewardedAdLoading) loadRewardedInterstitialAd();
            return;
        }

        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewardedAd = null;
                loadRewardedInterstitialAd();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                rewardedAd = null;
                loadRewardedInterstitialAd();
                Toast.makeText(MainActivity.this, "❌ فشل تشغيل الإعلان", Toast.LENGTH_SHORT).show();
            }
        });

        rewardedAd.show(this, rewardItem -> {
            // User earned the reward
            adEarned = true;
            mainHandler.post(() -> {
                watchAdBtn.setVisibility(View.GONE);
                showStatus("✅ تم اكتساب تحميل إضافي! اضغط تحميل.", true);
                doDownload();
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Download quota helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private int getTodayDownloads() {
        String saved = prefs.getString("dl_date", "");
        if (!todayStr().equals(saved)) return 0;
        return prefs.getInt("dl_count", 0);
    }

    private void incrementTodayDownloads() {
        String today = todayStr();
        String saved = prefs.getString("dl_date", "");
        int count = today.equals(saved) ? prefs.getInt("dl_count", 0) : 0;
        prefs.edit()
                .putString("dl_date", today)
                .putInt("dl_count", count + 1)
                .apply();
    }

    private void updateDownloadCounter() {
        if (isPremium()) {
            downloadCounter.setText("💎 مشترك — تحميلات غير محدودة");
        } else {
            int count = getTodayDownloads();
            downloadCounter.setText(
                    "التحميلات المجانية اليوم: " + count + " / " + FREE_DAILY_LIMIT);
        }
    }

    private boolean isPremium() {
        long expires = prefs.getLong("premium_expires", 0L);
        return expires > System.currentTimeMillis();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Disclaimer (first launch)
    // ══════════════════════════════════════════════════════════════════════════

    private void showDisclaimerIfNeeded() {
        if (prefs.getBoolean("disclaimer_accepted", false)) return;

        new AlertDialog.Builder(this)
                .setTitle("سياسة الاستخدام")
                .setMessage(
                        "هذا التطبيق مخصص للاستخدام الشخصي فقط.\n\n" +
                        "يتحمل المستخدم المسؤولية الكاملة عن المحتوى الذي يقوم بتحميله، " +
                        "ويجب التأكد من امتلاك الحقوق اللازمة لتحميل أي محتوى.\n\n" +
                        "باستخدامك هذا التطبيق، فأنت توافق على عدم انتهاك حقوق الملكية " +
                        "الفكرية أو شروط استخدام أي منصة.")
                .setPositiveButton("أوافق", (dialog, which) ->
                        prefs.edit().putBoolean("disclaimer_accepted", true).apply())
                .setNegativeButton("رفض", (dialog, which) -> finishAffinity())
                .setCancelable(false)
                .show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void showStatus(String msg, boolean visible) {
        statusText.setText(msg);
        statusText.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
