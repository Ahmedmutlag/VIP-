package com.nazzilhaplus.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class ReferralActivity extends AppCompatActivity {

    private static final String API_BASE = "https://www.vip-dl.com";

    private TextView codeView, progressText, statusText, rewardText;
    private ProgressBar progressBar;
    private EditText codeInput;
    private LinearLayout registerSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0f"));
        setContentView(R.layout.activity_referral);

        codeView       = findViewById(R.id.referralCode);
        progressText   = findViewById(R.id.progressText);
        statusText     = findViewById(R.id.statusText);
        rewardText     = findViewById(R.id.rewardText);
        progressBar    = findViewById(R.id.progressBar);
        codeInput      = findViewById(R.id.codeInput);
        registerSection = findViewById(R.id.registerSection);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.shareBtn).setOnClickListener(v -> shareReferral());
        findViewById(R.id.copyBtn).setOnClickListener(v -> copyCode());
        findViewById(R.id.registerBtn).setOnClickListener(v -> registerCode());

        String deviceId = getOrCreateDeviceId();
        boolean alreadyRegistered = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getBoolean("referral_registered", false);
        if (alreadyRegistered) {
            registerSection.setVisibility(View.GONE);
        }

        loadStats(deviceId);
    }

    private void loadStats(String deviceId) {
        String body = "{\"device_id\":" + org.json.JSONObject.quote(deviceId) + "}";
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(API_BASE + "/api/referral/stats").openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.getOutputStream().write(body.getBytes("UTF-8"));
                java.io.InputStream is = c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream();
                String resp = new String(is.readAllBytes(), "UTF-8");
                c.disconnect();
                org.json.JSONObject json = new org.json.JSONObject(resp);
                String code      = json.optString("code", "");
                String link      = json.optString("link", "");
                String shareText = json.optString("share_text", "");
                int    count     = json.optInt("valid_count", 0);
                int    needed    = json.optInt("needed", 10);
                int    rewards   = json.optInt("total_rewards", 0);
                runOnUiThread(() -> updateUI(code, link, shareText, count, needed, rewards));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("تعذر الاتصال بالخادم"));
            }
        }).start();
    }

    private void updateUI(String code, String link, String shareText,
                          int count, int needed, int rewards) {
        codeView.setText(code);
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit().putString("referral_code", code)
            .putString("referral_link", link)
            .putString("referral_share_text", shareText).apply();

        int remaining = needed - (count % needed == 0 && count > 0 ? needed : count % needed);
        if (count % needed == 0 && count > 0) remaining = 0;
        int progress  = (count % needed) * 100 / needed;
        progressBar.setProgress(progress);
        progressText.setText(count + " / " + needed);

        if (remaining == 0 && count > 0) {
            statusText.setText("🎉 أحضرت " + needed + " أشخاص!");
        } else {
            statusText.setText("أحضر " + (needed - count % needed) + " أشخاص أكثر لتحصل على شهر مجاني");
        }

        if (rewards > 0) {
            rewardText.setVisibility(View.VISIBLE);
            rewardText.setText("🏆 حصلت على " + rewards + " شهر مجاني حتى الآن");
        } else {
            rewardText.setVisibility(View.GONE);
        }
    }

    private void shareReferral() {
        String shareText = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("referral_share_text", "");
        if (shareText.isEmpty()) {
            String code = codeView.getText().toString();
            shareText = "حمّل نزّلها+ لتحميل الفيديوهات!\nكودي: " + code +
                "\nhttps://www.vip-dl.com/r/" + code;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(i, "مشاركة الدعوة"));
    }

    private void copyCode() {
        String code = codeView.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("referral_code", code));
        Toast.makeText(this, "تم نسخ الكود ✅", Toast.LENGTH_SHORT).show();
    }

    private void registerCode() {
        String code = codeInput.getText().toString().trim().toUpperCase();
        if (code.length() != 8) {
            Toast.makeText(this, "الكود يجب أن يكون 8 أحرف", Toast.LENGTH_SHORT).show();
            return;
        }
        String deviceId = getOrCreateDeviceId();
        String body = "{\"device_id\":" + org.json.JSONObject.quote(deviceId) +
            ",\"code\":" + org.json.JSONObject.quote(code) + "}";
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(API_BASE + "/api/referral/register").openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.getOutputStream().write(body.getBytes("UTF-8"));
                java.io.InputStream is = c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream();
                String resp = new String(is.readAllBytes(), "UTF-8");
                c.disconnect();
                org.json.JSONObject json = new org.json.JSONObject(resp);
                String err = json.optString("error", "");
                runOnUiThread(() -> {
                    if ("invalid_code".equals(err)) {
                        Toast.makeText(this, "الكود غير صحيح", Toast.LENGTH_SHORT).show();
                    } else if ("self_referral".equals(err)) {
                        Toast.makeText(this, "لا تقدر تستخدم كودك الخاص", Toast.LENGTH_SHORT).show();
                    } else {
                        getSharedPreferences("app_prefs", MODE_PRIVATE)
                            .edit().putBoolean("referral_registered", true).apply();
                        registerSection.setVisibility(View.GONE);
                        Toast.makeText(this, "تم تسجيل كود صديقك ✅", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "خطأ في الاتصال", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String getOrCreateDeviceId() {
        String id = getSharedPreferences("nazzilha_prefs", MODE_PRIVATE).getString("device_id", "");
        if (!id.isEmpty()) return id;
        String raw = android.provider.Settings.Secure.getString(
            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((raw != null ? raw : java.util.UUID.randomUUID().toString()).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            id = sb.toString();
        } catch (Exception e) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        getSharedPreferences("nazzilha_prefs", MODE_PRIVATE).edit().putString("device_id", id).apply();
        return id;
    }
}
