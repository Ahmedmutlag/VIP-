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
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NazzilhaPlus";
    private static final String API_BASE = "https://www.vip-dl.com";
    private static final String SITE_URL = "https://www.vip-dl.com";
    private static final int FREE_DAILY_LIMIT = 2;
    private static final int STORAGE_PERM_CODE = 100;
    private static final int MAX_HISTORY = 10;

    // ── Views ────────────────────────────────────────────────────────────────
    private EditText urlInput;
    private Button pasteBtn, fetchBtn;
    private ImageButton menuBtn;
    private ProgressBar loadingSpinner;
    private TextView errorBox;
    private LinearLayout resultCard, formatsContainer, progressSection, hintCard;
    private LinearLayout historySection, historyList;
    private ImageView thumbnail;
    private TextView platformBadge, videoTitle, progressPercent, downloadFilename;
    private ProgressBar downloadProgress;
    private AdView bannerAdView;
    private TextView footerHowTo, footerPrivacy, footerAbout;
    private Button clearHistoryBtn;

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
        "dailymotion.com", "vimeo.com", "bilibili.com",
        "twitch.tv", "reddit.com"
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

        MobileAds.initialize(this, status ->
            bannerAdView.loadAd(new AdRequest.Builder().build()));
        loadRewardedAd();

        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token != null)
                getPrefs().edit().putString("fcm_token", token).apply();
        });

        refreshHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoFillClipboard();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  View binding
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        urlInput         = findViewById(R.id.urlInput);
        pasteBtn         = findViewById(R.id.pasteBtn);
        fetchBtn         = findViewById(R.id.fetchBtn);
        menuBtn          = findViewById(R.id.menuBtn);
        loadingSpinner   = findViewById(R.id.loadingSpinner);
        errorBox         = findViewById(R.id.errorBox);
        resultCard       = findViewById(R.id.resultCard);
        formatsContainer = findViewById(R.id.formatsContainer);
        progressSection  = findViewById(R.id.progressSection);
        hintCard         = findViewById(R.id.hintCard);
        historySection   = findViewById(R.id.historySection);
        historyList      = findViewById(R.id.historyList);
        clearHistoryBtn  = findViewById(R.id.clearHistoryBtn);
        thumbnail        = findViewById(R.id.thumbnail);
        platformBadge    = findViewById(R.id.platformBadge);
        videoTitle       = findViewById(R.id.videoTitle);
        progressPercent  = findViewById(R.id.progressPercent);
        downloadFilename = findViewById(R.id.downloadFilename);
        downloadProgress = findViewById(R.id.downloadProgress);
        bannerAdView     = findViewById(R.id.bannerAd);
        footerHowTo      = findViewById(R.id.footerHowTo);
        footerPrivacy    = findViewById(R.id.footerPrivacy);
        footerAbout      = findViewById(R.id.footerAbout);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Listeners
    // ══════════════════════════════════════════════════════════════════════════

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

        menuBtn.setOnClickListener(v -> showPopupMenu(v));

        clearHistoryBtn.setOnClickListener(v -> {
            getPrefs().edit().remove("dl_history").apply();
            refreshHistory();
        });

        footerHowTo.setOnClickListener(v -> openUrl(SITE_URL + "/how-to-use"));
        footerPrivacy.setOnClickListener(v -> openUrl(SITE_URL + "/privacy"));
        footerAbout.setOnClickListener(v -> openUrl(SITE_URL + "/about"));
    }

    private void showPopupMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.main_menu, popup.getMenu());

        // Show correct label for upgrade item based on premium status
        String premiumLabel = isPremiumActive()
            ? "⭐ بريميوم مفعّل — تجديد"
            : "⭐ ترقية للبريميوم";
        popup.getMenu().findItem(R.id.menu_upgrade).setTitle(premiumLabel);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_upgrade)  { showUpgradeDialog();            return true; }
            if (id == R.id.menu_how_to)   { openUrl(SITE_URL + "/how-to-use"); return true; }
            if (id == R.id.menu_privacy)  { openUrl(SITE_URL + "/privacy");    return true; }
            if (id == R.id.menu_about)    { openUrl(SITE_URL + "/about");      return true; }
            if (id == R.id.menu_blog)     { openUrl(SITE_URL + "/blog");       return true; }
            return false;
        });
        popup.show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
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
                "باستخدامك هذا التطبيق فأنت توافق على سياسة الخصوصية وشروط الاستخدام."
            )
            .setPositiveButton("أوافق", (d, w) ->
                p.edit().putBoolean("disclaimer_accepted", true).apply())
            .setNeutralButton("سياسة الخصوصية", (d, w) -> openUrl(SITE_URL + "/privacy"))
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
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                String body = "{\"url\":" + JSONObject.quote(url) + "}";
                conn.getOutputStream().write(body.getBytes("UTF-8"));

                int code = conn.getResponseCode();
                if (code != 200) {
                    String errMsg = readErrorBody(conn);
                    runOnUiThread(() -> { setLoading(false); showError(errMsg); });
                    conn.disconnect();
                    return;
                }

                String respStr = readBody(conn.getInputStream());
                conn.disconnect();
                JSONObject resp = new JSONObject(respStr);

                if (resp.has("error")) {
                    String msg = resp.optString("error", "رابط غير مدعوم");
                    runOnUiThread(() -> { setLoading(false); showError(msg); });
                    return;
                }

                runOnUiThread(() -> { setLoading(false); displayResult(resp); });

            } catch (Exception e) {
                Log.e(TAG, "fetchInfo: " + e.getMessage());
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("فشل الاتصال بالخادم — تأكد من الإنترنت");
                });
            }
        }).start();
    }

    private String readBody(InputStream is) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(
            new java.io.InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String readErrorBody(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es != null) {
                JSONObject j = new JSONObject(readBody(es));
                String err = j.optString("error", "");
                if (!err.isEmpty()) return err;
            }
        } catch (Exception ignored) {}
        return "الرابط غير مدعوم أو الخادم غير متاح حالياً";
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

        videoTitle.setText(title.isEmpty() ? "بدون عنوان" : title);
        platformBadge.setText(platform.isEmpty() ? "فيديو" : platform.toUpperCase());
        resultCard.setVisibility(View.VISIBLE);

        // Load thumbnail asynchronously
        if (!thumbUrl.isEmpty()) {
            final String tu = thumbUrl;
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(tu).openConnection();
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
                JSONObject fmt  = fmts.getJSONObject(i);
                String label    = fmt.optString("label", "تحميل");
                String dlUrl    = fmt.optString("url", "");
                String ext      = fmt.optString("ext", "mp4");
                String type     = fmt.optString("type", "video");
                if (dlUrl.isEmpty()) continue;

                String emoji    = "audio".equals(type) ? "🎵" : "🎬";
                String filename = sanitizeFilename(title) + "." + ext;

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackground(getDrawable(R.drawable.format_btn_bg));
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(8));
                row.setLayoutParams(lp);
                row.setClickable(true);
                row.setFocusable(true);

                TextView emojiTv = new TextView(this);
                emojiTv.setText(emoji);
                emojiTv.setTextSize(18);
                emojiTv.setPadding(0, 0, dp(10), 0);

                TextView labelTv = new TextView(this);
                labelTv.setText(label);
                labelTv.setTextSize(14);
                labelTv.setTextColor(0xFF1F1F2E);
                labelTv.setTypeface(labelTv.getTypeface(), android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                labelTv.setLayoutParams(tlp);

                TextView extTv = new TextView(this);
                extTv.setText(ext.toUpperCase());
                extTv.setTextSize(11);
                extTv.setTextColor(0xFF9CA3AF);

                row.addView(emojiTv);
                row.addView(labelTv);
                row.addView(extTv);

                final String finalUrl  = dlUrl;
                final String finalName = filename;
                final String finalTitle = title;
                final String finalPlatform = platform;
                row.setOnClickListener(v -> onFormatPicked(finalUrl, finalName, finalTitle, finalPlatform));

                formatsContainer.addView(row);
            } catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Format picked → quota check → ad → download
    // ══════════════════════════════════════════════════════════════════════════

    private void onFormatPicked(String dlUrl, String filename, String title, String platform) {
        pendingDlUrl      = dlUrl;
        pendingDlFilename = filename;
        downloadPending   = true;

        if (isPremiumActive() || canDownloadFree()) {
            saveHistory(title, platform);
            beginDownload();
        } else {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("تجاوزت الحد اليومي")
                .setMessage("استنفذت " + FREE_DAILY_LIMIT + " تحميلات مجانية اليوم.\nشاهد إعلاناً أو اشترك في البريميوم للحصول على تحميل غير محدود.")
                .setPositiveButton("شاهد الإعلان", (d, w) -> {
                    saveHistory(title, platform);
                    showRewardedAd();
                })
                .setNeutralButton("⭐ بريميوم", (d, w) -> showUpgradeDialog())
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

    // ── Premium (Wayl) ───────────────────────────────────────────────────────

    private boolean isPremiumActive() {
        SharedPreferences p = getPrefs();
        String exp = p.getString("premium_expires", "");
        if (exp.isEmpty()) return false;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
            java.util.Date expDate = sdf.parse(exp);
            return expDate != null && expDate.after(new java.util.Date());
        } catch (Exception e) { return false; }
    }

    private String getDeviceId() {
        SharedPreferences p = getPrefs();
        String id = p.getString("device_id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private void showUpgradeDialog() {
        if (isPremiumActive()) {
            SharedPreferences p = getPrefs();
            String exp = p.getString("premium_expires", "");
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⭐ أنت مشترك في البريميوم")
                .setMessage("اشتراكك فعّال حتى:\n" + exp + "\n\nهل تريد التجديد المبكر أو التحقق من دفع جديد؟")
                .setPositiveButton("تجديد", (d, w) -> startWaylPayment())
                .setNeutralButton("تحقق من دفع", (d, w) -> showVerifyPaymentDialog())
                .setNegativeButton("إغلاق", null)
                .show();
        } else {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⭐ ترقية للبريميوم")
                .setMessage("احصل على تحميل غير محدود طوال الشهر!\n\nالسعر: 5,000 د.ع / شهر\n\nستُفتح صفحة الدفع في المتصفح، وبعد إتمام الدفع اضغط \"تحقق من الدفع\".")
                .setPositiveButton("ادفع الآن", (d, w) -> startWaylPayment())
                .setNeutralButton("تحقق من دفع", (d, w) -> showVerifyPaymentDialog())
                .setNegativeButton("لاحقاً", null)
                .show();
        }
    }

    private void startWaylPayment() {
        String deviceId = getDeviceId();
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("device_id", deviceId);

                java.net.URL url = new java.net.URL(API_BASE + "/api/wayl/create");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                byte[] input = body.toString().getBytes("utf-8");
                conn.getOutputStream().write(input);

                int code = conn.getResponseCode();
                java.io.InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
                String resp = new String(is.readAllBytes(), "utf-8");
                org.json.JSONObject json = new org.json.JSONObject(resp);

                if (code == 200 || code == 201) {
                    String paymentUrl = json.getString("payment_url");
                    String paymentId  = json.getString("payment_id");
                    getPrefs().edit().putString("pending_payment_id", paymentId).apply();
                    runOnUiThread(() -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl)));
                        } catch (Exception e) {
                            Toast.makeText(this, "تعذّر فتح صفحة الدفع", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    String err = json.optString("error", "فشل الاتصال");
                    runOnUiThread(() -> Toast.makeText(this, err, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showVerifyPaymentDialog() {
        String pending = getPrefs().getString("pending_payment_id", "");
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("رقم الدفع (Payment ID)");
        input.setText(pending);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تحقق من الدفع")
            .setMessage("أدخل رقم الدفع الذي استلمته من Wayl:")
            .setView(input)
            .setPositiveButton("تحقق", (d, w) -> {
                String pid = input.getText().toString().trim();
                if (!pid.isEmpty()) verifyWaylPayment(pid);
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void verifyWaylPayment(String paymentId) {
        Toast.makeText(this, "جاري التحقق...", Toast.LENGTH_SHORT).show();
        String deviceId = getDeviceId();
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("payment_id", paymentId);
                body.put("device_id", deviceId);

                java.net.URL url = new java.net.URL(API_BASE + "/api/wayl/verify");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                byte[] input = body.toString().getBytes("utf-8");
                conn.getOutputStream().write(input);

                int code = conn.getResponseCode();
                java.io.InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
                String resp = new String(is.readAllBytes(), "utf-8");
                org.json.JSONObject json = new org.json.JSONObject(resp);

                if ((code == 200) && json.optBoolean("paid", false)) {
                    String exp = json.optString("expires_at", "");
                    getPrefs().edit()
                        .putString("premium_expires", exp)
                        .putString("premium_code", json.optString("code", ""))
                        .remove("pending_payment_id")
                        .apply();
                    runOnUiThread(() ->
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("⭐ تم تفعيل البريميوم!")
                            .setMessage("اشتراكك مفعّل حتى:\n" + exp + "\n\nاستمتع بتحميل غير محدود!")
                            .setPositiveButton("رائع!", null)
                            .show()
                    );
                } else {
                    String err = json.optString("error", "الدفع لم يُكتمل بعد. تأكد من إتمام الدفع وحاول مجدداً.");
                    runOnUiThread(() -> Toast.makeText(this, err, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "خطأ في التحقق: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void incrementDownloadCount() {
        String today = todayKey();
        SharedPreferences p = getPrefs();
        int count = today.equals(p.getString("dl_date", "")) ? p.getInt("dl_count", 0) : 0;
        p.edit().putString("dl_date", today).putInt("dl_count", count + 1).apply();
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Download history
    // ══════════════════════════════════════════════════════════════════════════

    private void saveHistory(String title, String platform) {
        try {
            SharedPreferences p = getPrefs();
            JSONArray arr = new JSONArray(p.getString("dl_history", "[]"));
            JSONObject entry = new JSONObject();
            entry.put("title", title);
            entry.put("platform", platform);
            entry.put("time", System.currentTimeMillis());
            // Prepend new entry
            JSONArray updated = new JSONArray();
            updated.put(entry);
            for (int i = 0; i < arr.length() && i < MAX_HISTORY - 1; i++) {
                updated.put(arr.getJSONObject(i));
            }
            p.edit().putString("dl_history", updated.toString()).apply();
            runOnUiThread(this::refreshHistory);
        } catch (Exception ignored) {}
    }

    private void refreshHistory() {
        try {
            SharedPreferences p = getPrefs();
            JSONArray arr = new JSONArray(p.getString("dl_history", "[]"));
            if (arr.length() == 0) {
                historySection.setVisibility(View.GONE);
                return;
            }

            historyList.removeAllViews();
            historySection.setVisibility(View.VISIBLE);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                String title    = e.optString("title", "");
                String platform = e.optString("platform", "");

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(8), 0, dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                row.setLayoutParams(lp);

                TextView dot = new TextView(this);
                dot.setText("📥");
                dot.setTextSize(14);
                dot.setPadding(0, 0, dp(10), 0);

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                info.setLayoutParams(ilp);

                TextView tv = new TextView(this);
                tv.setText(title.isEmpty() ? "فيديو" : title);
                tv.setTextSize(13);
                tv.setTextColor(0xFF1F1F2E);
                tv.setMaxLines(1);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView plat = new TextView(this);
                plat.setText(platform);
                plat.setTextSize(11);
                plat.setTextColor(0xFF9CA3AF);

                info.addView(tv);
                info.addView(plat);
                row.addView(dot);
                row.addView(info);
                historyList.addView(row);

                // Divider (not on last item)
                if (i < arr.length() - 1) {
                    View div = new View(this);
                    LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    div.setLayoutParams(dp1);
                    div.setBackgroundColor(0xFFF3F4F6);
                    historyList.addView(div);
                }
            }
        } catch (Exception ignored) {}
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
                @Override public void onAdLoaded(@NonNull RewardedInterstitialAd ad) {
                    rewardedAd = ad; rewardedAdLoading = false;
                }
                @Override public void onAdFailedToLoad(@NonNull LoadAdError e) {
                    rewardedAd = null; rewardedAdLoading = false;
                    Log.w(TAG, "Rewarded ad failed: " + e.getMessage());
                }
            });
    }

    private void showRewardedAd() {
        if (rewardedAd == null || isShowingAd) {
            Toast.makeText(this, "الإعلان لم يتحمل بعد، جرّب مجدداً", Toast.LENGTH_SHORT).show();
            if (!rewardedAdLoading) loadRewardedAd();
            return;
        }
        isShowingAd = true;
        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                rewardedAd = null; isShowingAd = false; loadRewardedAd();
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                rewardedAd = null; isShowingAd = false; loadRewardedAd();
            }
        });
        rewardedAd.show(this, reward -> runOnUiThread(this::beginDownload));
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
        showProgressSection(true, name);
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
            incrementDownloadCount();
            Uri fu = resultUri[0];
            runOnUiThread(() -> { showProgressSection(false, null); showSuccessDialog(fu); });
        } else {
            runOnUiThread(() -> { showProgressSection(false, null); showError("فشل التحميل، حاول مجدداً"); });
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
                            try { nm.notify(notifId, nb.build()); } catch (Exception ig) {}
                        }
                    }
                }
                ContentValues cv2 = new ContentValues();
                cv2.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(dlUri, cv2, null, null);
                out[0] = dlUri;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "dl attempt " + attempt + ": " + e.getMessage());
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
                    scanned[0] = u; synchronized (lock) { lock.notifyAll(); }
                });
                synchronized (lock) { try { lock.wait(3000); } catch (InterruptedException ig) {} }
                out[0] = scanned[0] != null ? scanned[0] : Uri.fromFile(file);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "dl attempt " + attempt + ": " + e.getMessage());
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

    private void showProgressSection(boolean on, String filename) {
        progressSection.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            downloadProgress.setProgress(0);
            progressPercent.setText("0%");
            if (filename != null) downloadFilename.setText(filename);
        }
    }

    private void updateProgress(int pct) {
        downloadProgress.setProgress(pct);
        progressPercent.setText(pct + "%");
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
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
        if (filename.endsWith(".aac")) return "audio/aac";
        return "video/mp4";
    }
}
