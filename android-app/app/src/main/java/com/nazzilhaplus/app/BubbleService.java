package com.nazzilhaplus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class BubbleService extends Service {

    private static final String CHANNEL_SVC  = "bubble_svc";
    private static final String CHANNEL_DL   = "bubble_dl";
    private static final int    NOTIF_SVC_ID = 9001;
    private static final String API_BASE     = "https://www.vip-dl.com";
    private static final String PREFS        = "nazzilha_prefs";

    private WindowManager wm;
    private View          bubbleRoot;
    private View          panelRoot;
    private TextView      badgeView;
    private View          dismissTarget;
    private boolean       panelOpen = false;
    private String        detectedUrl = "";

    // Drag tracking
    private int   bInitX, bInitY;
    private float bInitTX, bInitTY;
    private long  bDownTime;

    // ══════════════════════════════════════════════════════════════════════════
    //  Service lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(NOTIF_SVC_ID, buildSvcNotif());
        inflateBubble();
        watchClipboard();
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (progressHandler != null) { progressHandler.removeCallbacksAndMessages(null); progressHandler = null; }
        safeRemove(bubbleRoot);
        safeRemove(dismissTarget);
        safeRemove(panelRoot);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Foreground notification
    // ══════════════════════════════════════════════════════════════════════════

    private Notification buildSvcNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_SVC, "فقاعة نزّلها+", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            NotificationChannel dlCh = new NotificationChannel(
                CHANNEL_DL, "تحميلات نزّلها+", NotificationManager.IMPORTANCE_DEFAULT);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(dlCh);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_SVC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("نزّلها+ — الفقاعة نشطة")
            .setContentText("انسخ رابط فيديو لبدء التحميل السريع")
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Bubble
    // ══════════════════════════════════════════════════════════════════════════

    private void inflateBubble() {
        int size = dp(60);

        FrameLayout root = new FrameLayout(this);

        // Circle background
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF7C3AED);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        icon.setBackground(bg);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setClipToOutline(true);

        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(size, size);
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);

        // Badge dot
        badgeView = new TextView(this);
        badgeView.setTextSize(7);
        badgeView.setTextColor(Color.WHITE);
        badgeView.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(0xFFEF4444);
        badgeView.setBackground(badgeBg);
        badgeView.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(14), dp(14));
        badgeLp.gravity = Gravity.TOP | Gravity.END;
        badgeLp.topMargin = dp(4);
        badgeLp.rightMargin = dp(4);
        badgeView.setLayoutParams(badgeLp);

        root.addView(icon);
        root.addView(badgeView);
        bubbleRoot = root;

        int wType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            dp(70), dp(70), wType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 350;

        bubbleRoot.setOnTouchListener(new DragTap(lp));
        wm.addView(bubbleRoot, lp);
        startProgressPolling();
    }

    private android.os.Handler progressHandler;

    private void startProgressPolling() {
        progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable poll = new Runnable() {
            @Override public void run() {
                int pct = MainActivity.bubbleDownloadPct;
                if (badgeView != null) {
                    if (pct >= 0) {
                        badgeView.setTextSize(8);
                        badgeView.setText(pct + "%");
                        badgeView.setVisibility(View.VISIBLE);
                        // Resize badge to fit text
                        android.view.ViewGroup.LayoutParams lp = badgeView.getLayoutParams();
                        lp.width = dp(28); lp.height = dp(18);
                        badgeView.setLayoutParams(lp);
                    } else if (badgeView.getText().toString().contains("%")) {
                        badgeView.setText("");
                        badgeView.setVisibility(View.GONE);
                        android.view.ViewGroup.LayoutParams lp = badgeView.getLayoutParams();
                        lp.width = dp(14); lp.height = dp(14);
                        badgeView.setLayoutParams(lp);
                    }
                }
                if (progressHandler != null) progressHandler.postDelayed(this, 500);
            }
        };
        progressHandler.post(poll);
    }

    private class DragTap implements View.OnTouchListener {
        final WindowManager.LayoutParams lp;
        DragTap(WindowManager.LayoutParams lp) { this.lp = lp; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            int screenH = dm.heightPixels;
            int screenW = dm.widthPixels;

            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    bInitX = lp.x; bInitY = lp.y;
                    bInitTX = e.getRawX(); bInitTY = e.getRawY();
                    bDownTime = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    lp.x = bInitX + (int)(e.getRawX() - bInitTX);
                    lp.y = bInitY + (int)(e.getRawY() - bInitTY);
                    wm.updateViewLayout(bubbleRoot, lp);
                    boolean dragging = Math.abs(e.getRawX()-bInitTX) > dp(8)
                                    || Math.abs(e.getRawY()-bInitTY) > dp(8);
                    if (dragging) showDismissTarget();
                    // Snap bubble to dismiss zone when close enough
                    boolean nearDismiss = e.getRawY() > screenH - dp(120)
                            && Math.abs(e.getRawX() - screenW / 2f) < dp(60);
                    bubbleRoot.setAlpha(nearDismiss ? 0.5f : 1f);
                    return true;

                case MotionEvent.ACTION_UP:
                    hideDismissTarget();
                    bubbleRoot.setAlpha(1f);
                    boolean wasDrag = Math.abs(e.getRawX()-bInitTX) > dp(8)
                                   || Math.abs(e.getRawY()-bInitTY) > dp(8);
                    boolean droppedOnDismiss = e.getRawY() > screenH - dp(120)
                            && Math.abs(e.getRawX() - screenW / 2f) < dp(60);
                    if (wasDrag && droppedOnDismiss) {
                        stop(BubbleService.this);
                        return true;
                    }
                    if (!wasDrag && System.currentTimeMillis()-bDownTime < 350) {
                        if (panelOpen) closePanel();
                        else openPanel();
                    }
                    return true;
            }
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Dismiss target (trash zone at bottom)
    // ══════════════════════════════════════════════════════════════════════════

    private void showDismissTarget() {
        if (dismissTarget != null) return;
        int wType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        TextView tv = new TextView(this);
        tv.setText("✕");
        tv.setTextSize(22);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCCEF4444);
        tv.setBackground(bg);
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            dp(64), dp(64), wType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(48);

        dismissTarget = tv;
        wm.addView(dismissTarget, lp);
    }

    private void hideDismissTarget() {
        if (dismissTarget == null) return;
        safeRemove(dismissTarget);
        dismissTarget = null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clipboard watcher
    // ══════════════════════════════════════════════════════════════════════════

    private void watchClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.addPrimaryClipChangedListener(() -> {
            try {
                if (!cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return;
                android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                if (item == null || item.getText() == null) return;
                String txt = item.getText().toString().trim();
                if (isVideoUrl(txt) && !txt.equals(detectedUrl)) {
                    detectedUrl = txt;
                    ui(() -> {
                        badgeView.setVisibility(View.VISIBLE);
                        // Brief pulse animation
                        bubbleRoot.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150)
                            .withEndAction(() -> bubbleRoot.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                            .start();
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private boolean isVideoUrl(String url) {
        if (url == null || !url.startsWith("http")) return false;
        String low = url.toLowerCase();
        for (String d : new String[]{"tiktok.com","instagram.com","youtube.com","youtu.be",
            "facebook.com","twitter.com","x.com","snapchat.com","pinterest.com",
            "vm.tiktok.com","vt.tiktok.com","fb.watch","dailymotion.com","vimeo.com"})
            if (low.contains(d)) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Panel
    // ══════════════════════════════════════════════════════════════════════════

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        badgeView.setVisibility(View.GONE);
        // Read clipboard directly in case it was copied before the service started
        if (detectedUrl.isEmpty()) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String txt = item.getText().toString().trim();
                        if (isVideoUrl(txt)) detectedUrl = txt;
                    }
                }
            } catch (Exception ignored) {}
        }
        buildPanel(detectedUrl);
    }

    private void closePanel() {
        panelOpen = false;
        safeRemove(panelRoot);
        panelRoot = null;
    }

    /** Builds the whole panel as a WindowManager overlay */
    private void buildPanel(String prefilledUrl) {
        int wType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        // Outer dim layer
        FrameLayout root = new FrameLayout(this);

        // Card container
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF0F0F22);
        cardBg.setCornerRadii(new float[]{dp(20),dp(20),dp(20),dp(20),0,0,0,0});
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(10), dp(16), dp(24));

        // Handle bar
        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x44FFFFFF);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(14);
        handle.setLayoutParams(handleLp);
        card.addView(handle);

        // ── Input state ───────────────────────────────────────────────────
        LinearLayout inputState = new LinearLayout(this);
        inputState.setOrientation(LinearLayout.VERTICAL);

        // Header row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fullW = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullW.bottomMargin = dp(14);
        headerRow.setLayoutParams(fullW);

        TextView headerTitle = new TextView(this);
        headerTitle.setText("⬇  تحميل سريع");
        headerTitle.setTextSize(14);
        headerTitle.setTypeface(null, Typeface.BOLD);
        headerTitle.setTextColor(0xFFEEEEFF);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerTitle.setLayoutParams(htLp);
        headerRow.addView(headerTitle);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(16);
        closeBtn.setTextColor(0xFF888899);
        closeBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        closeBtn.setOnClickListener(v -> closePanel());
        headerRow.addView(closeBtn);
        inputState.addView(headerRow);

        // URL display row
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable urlBg = new GradientDrawable();
        urlBg.setColor(0x22FFFFFF);
        urlBg.setCornerRadius(dp(10));
        urlRow.setBackground(urlBg);
        urlRow.setPadding(dp(10), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams urlRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlRowLp.bottomMargin = dp(12);
        urlRow.setLayoutParams(urlRowLp);

        TextView urlDisplay = new TextView(this);
        urlDisplay.setText(prefilledUrl.isEmpty() ? "لا يوجد رابط — اضغط لصق" : prefilledUrl);
        urlDisplay.setTextSize(11);
        urlDisplay.setTextColor(prefilledUrl.isEmpty() ? 0xFF666688 : 0xFFCCCCFF);
        urlDisplay.setMaxLines(1);
        urlDisplay.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        urlDisplay.setSingleLine(true);
        LinearLayout.LayoutParams udLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        urlDisplay.setLayoutParams(udLp);
        urlRow.addView(urlDisplay);

        // Paste button
        TextView pasteBtn = new TextView(this);
        pasteBtn.setText("📋 لصق");
        pasteBtn.setTextSize(11);
        pasteBtn.setTextColor(0xFFA78BFA);
        pasteBtn.setTypeface(null, Typeface.BOLD);
        pasteBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            try {
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    String txt = cm.getPrimaryClip().getItemAt(0).getText().toString().trim();
                    if (!txt.isEmpty()) {
                        detectedUrl = txt;
                        urlDisplay.setText(txt);
                        urlDisplay.setTextColor(0xFFCCCCFF);
                    }
                }
            } catch (Exception ignored) {}
        });
        urlRow.addView(pasteBtn);
        inputState.addView(urlRow);

        // Fetch button
        Button fetchBtn = new Button(this);
        fetchBtn.setText("جلب معلومات الفيديو ←");
        fetchBtn.setTextColor(Color.WHITE);
        fetchBtn.setTypeface(null, Typeface.BOLD);
        fetchBtn.setTextSize(13);
        fetchBtn.setAllCaps(false);
        GradientDrawable fetchBg = new GradientDrawable();
        fetchBg.setColor(0xFF7C3AED);
        fetchBg.setCornerRadius(dp(12));
        fetchBtn.setBackground(fetchBg);
        fetchBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fetchBtn.setLayoutParams(fbLp);

        inputState.addView(fetchBtn);
        card.addView(inputState);

        // ── Loading state ─────────────────────────────────────────────────
        LinearLayout loadingState = new LinearLayout(this);
        loadingState.setOrientation(LinearLayout.VERTICAL);
        loadingState.setGravity(Gravity.CENTER);
        loadingState.setVisibility(View.GONE);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80));
        loadingState.setLayoutParams(loadLp);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF7C3AED));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        spLp.gravity = Gravity.CENTER_HORIZONTAL;
        spLp.bottomMargin = dp(8);
        spinner.setLayoutParams(spLp);
        loadingState.addView(spinner);

        TextView loadingTxt = new TextView(this);
        loadingTxt.setText("جارٍ جلب معلومات الفيديو...");
        loadingTxt.setTextColor(0xFF888899);
        loadingTxt.setTextSize(12);
        loadingTxt.setGravity(Gravity.CENTER);
        loadingState.addView(loadingTxt);
        card.addView(loadingState);

        // ── Formats state ─────────────────────────────────────────────────
        LinearLayout formatsState = new LinearLayout(this);
        formatsState.setOrientation(LinearLayout.VERTICAL);
        formatsState.setVisibility(View.GONE);

        TextView fmtTitle = new TextView(this);
        fmtTitle.setText("اختر الجودة:");
        fmtTitle.setTextSize(12);
        fmtTitle.setTextColor(0xFF888899);
        fmtTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams ftLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ftLp.bottomMargin = dp(8);
        fmtTitle.setLayoutParams(ftLp);
        formatsState.addView(fmtTitle);

        LinearLayout fmtList = new LinearLayout(this);
        fmtList.setOrientation(LinearLayout.VERTICAL);
        formatsState.addView(fmtList);
        card.addView(formatsState);

        // ── Progress state ────────────────────────────────────────────────
        LinearLayout progressState = new LinearLayout(this);
        progressState.setOrientation(LinearLayout.VERTICAL);
        progressState.setVisibility(View.GONE);

        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.bottomMargin = dp(8);
        progRow.setLayoutParams(prLp);

        TextView progLabel = new TextView(this);
        progLabel.setText("جارٍ التحميل...");
        progLabel.setTextSize(12);
        progLabel.setTextColor(0xFFCCCCFF);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        progLabel.setLayoutParams(plLp);
        progRow.addView(progLabel);

        TextView progPct = new TextView(this);
        progPct.setText("0%");
        progPct.setTextSize(12);
        progPct.setTextColor(0xFFA78BFA);
        progPct.setTypeface(null, Typeface.BOLD);
        progRow.addView(progPct);
        progressState.addView(progRow);

        ProgressBar progBar = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(100);
        progBar.setProgress(0);
        progBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF7C3AED));
        progBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        pbLp.bottomMargin = dp(6);
        progBar.setLayoutParams(pbLp);
        progressState.addView(progBar);

        TextView progFile = new TextView(this);
        progFile.setText("");
        progFile.setTextSize(10);
        progFile.setTextColor(0xFF666688);
        progFile.setSingleLine(true);
        progFile.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        progressState.addView(progFile);
        card.addView(progressState);

        // ── Done state ────────────────────────────────────────────────────
        LinearLayout doneState = new LinearLayout(this);
        doneState.setOrientation(LinearLayout.VERTICAL);
        doneState.setGravity(Gravity.CENTER);
        doneState.setVisibility(View.GONE);
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80));
        doneState.setLayoutParams(doneLp);

        TextView doneIco = new TextView(this);
        doneIco.setText("✅");
        doneIco.setTextSize(28);
        doneIco.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams diLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        diLp.bottomMargin = dp(4);
        doneIco.setLayoutParams(diLp);
        doneState.addView(doneIco);

        TextView doneTxt = new TextView(this);
        doneTxt.setText("تم الحفظ في المعرض! 🎉");
        doneTxt.setTextSize(13);
        doneTxt.setTextColor(0xFF22C55E);
        doneTxt.setTypeface(null, Typeface.BOLD);
        doneTxt.setGravity(Gravity.CENTER);
        doneState.addView(doneTxt);
        card.addView(doneState);

        // ── Place card in root ────────────────────────────────────────────
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM);
        card.setLayoutParams(cardLp);
        root.addView(card);
        panelRoot = root;

        // ── Window params ─────────────────────────────────────────────────
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            wType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.BOTTOM;
        wm.addView(panelRoot, wlp);

        // ── Fetch button logic ────────────────────────────────────────────
        fetchBtn.setOnClickListener(v -> {
            String url = detectedUrl.trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "الصق رابط الفيديو أولاً", Toast.LENGTH_SHORT).show();
                return;
            }
            inputState.setVisibility(View.GONE);
            loadingState.setVisibility(View.VISIBLE);

            resolveUrl(url, new PanelCallback() {
                @Override public void onResolved(JSONObject data) {
                    loadingState.setVisibility(View.GONE);
                    formatsState.setVisibility(View.VISIBLE);
                    String title    = data.optString("title", "فيديو");
                    String platform = data.optString("platform", "");
                    JSONArray fmts  = data.optJSONArray("formats");
                    if (fmts == null || fmts.length() == 0) {
                        onError("لا توجد صيغ متاحة");
                        return;
                    }
                    fmtList.removeAllViews();
                    for (int i = 0; i < Math.min(fmts.length(), 6); i++) {
                        try {
                            JSONObject fmt = fmts.getJSONObject(i);
                            String label   = fmt.optString("label", "تحميل");
                            String dlUrl   = fmt.optString("url", "");
                            String ext     = fmt.optString("ext", "mp4");
                            String type    = fmt.optString("type", "video");
                            if (dlUrl.isEmpty()) continue;

                            String emoji    = "audio".equals(type) ? "🎵" : "🎬";
                            String filename = sanitize(title) + "." + ext;

                            LinearLayout row = new LinearLayout(BubbleService.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            GradientDrawable rowBg = new GradientDrawable();
                            rowBg.setColor(0x22FFFFFF);
                            rowBg.setCornerRadius(dp(10));
                            row.setBackground(rowBg);
                            row.setPadding(dp(12), dp(10), dp(12), dp(10));
                            row.setClickable(true);
                            row.setFocusable(true);
                            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                            rlp.bottomMargin = dp(6);
                            row.setLayoutParams(rlp);

                            TextView eIco = new TextView(BubbleService.this);
                            eIco.setText(emoji);
                            eIco.setTextSize(16);
                            eIco.setPadding(0, 0, dp(10), 0);
                            row.addView(eIco);

                            TextView lbl = new TextView(BubbleService.this);
                            lbl.setText(label);
                            lbl.setTextSize(13);
                            lbl.setTypeface(null, Typeface.BOLD);
                            lbl.setTextColor(0xFFEEEEFF);
                            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                            lbl.setLayoutParams(llp);
                            row.addView(lbl);

                            TextView extTv = new TextView(BubbleService.this);
                            extTv.setText(ext.toUpperCase());
                            extTv.setTextSize(10);
                            extTv.setTextColor(0xFF888899);
                            row.addView(extTv);

                            final String fUrl  = dlUrl;
                            final String fName = filename;
                            row.setOnClickListener(vv -> {
                                formatsState.setVisibility(View.GONE);
                                progressState.setVisibility(View.VISIBLE);
                                progFile.setText(fName);
                                startDownload(fUrl, fName, progBar, progPct, () -> {
                                    progressState.setVisibility(View.GONE);
                                    doneState.setVisibility(View.VISIBLE);
                                    detectedUrl = "";
                                    new Handler(Looper.getMainLooper()).postDelayed(
                                        BubbleService.this::closePanel, 2000);
                                });
                            });
                            fmtList.addView(row);
                        } catch (Exception ignored) {}
                    }
                }

                @Override public void onError(String msg) {
                    loadingState.setVisibility(View.GONE);
                    inputState.setVisibility(View.VISIBLE);
                    Toast.makeText(BubbleService.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  API: resolve
    // ══════════════════════════════════════════════════════════════════════════

    interface PanelCallback {
        void onResolved(JSONObject data);
        void onError(String msg);
    }

    private void resolveUrl(String url, PanelCallback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection) new URL(API_BASE + "/api/resolve").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);

                JSONObject body = new JSONObject();
                body.put("url", url);
                body.put("platform", "android");
                body.put("version", "2.0");

                SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                String cert = p.getString("cert_hash", "");
                if (!cert.isEmpty()) body.put("cert_hash", cert);

                byte[] b = body.toString().getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(b.length));
                conn.getOutputStream().write(b);

                int code = conn.getResponseCode();
                if (code == 200) {
                    String resp = readBody(conn.getInputStream());
                    JSONObject data = new JSONObject(resp);
                    ui(() -> cb.onResolved(data));
                } else {
                    ui(() -> cb.onError("تعذر جلب معلومات الفيديو"));
                }
                conn.disconnect();
            } catch (Exception e) {
                ui(() -> cb.onError("فشل الاتصال بالخادم"));
            }
        }).start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Download
    // ══════════════════════════════════════════════════════════════════════════

    private void startDownload(String url, String filename,
                               ProgressBar bar, TextView pct, Runnable onDone) {
        int notifId = filename.hashCode();
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_DL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(filename)
            .setContentText("جارٍ التحميل...")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        try { nm.notify(notifId, nb.build()); } catch (Exception ignored) {}

        new Thread(() -> {
            boolean ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? dlMediaStore(url, filename, bar, pct, nb, nm, notifId)
                : dlFileSystem(url, filename, bar, pct, nb, nm, notifId);

            nm.cancel(notifId);

            if (ok) {
                showDlDoneNotif(filename);
                ui(onDone);
            } else {
                ui(() -> Toast.makeText(this, "فشل التحميل، حاول مجدداً", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private boolean dlMediaStore(String url, String filename,
                                 ProgressBar bar, TextView pct,
                                 NotificationCompat.Builder nb,
                                 NotificationManagerCompat nm, int notifId) {
        Uri dlUri = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long existing = 0;
                if (dlUri == null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mimeFor(filename));
                    cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/NazzilhaPlus");
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
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;
                long total = existing + conn.getContentLengthLong();
                String mode = (code == 206 && existing > 0) ? "wa" : "w";
                try (InputStream in = conn.getInputStream();
                     OutputStream os = getContentResolver().openOutputStream(dlUri, mode)) {
                    if (os == null) break;
                    byte[] buf = new byte[8192]; int n; long done = existing;
                    while ((n = in.read(buf)) != -1) {
                        os.write(buf, 0, n); done += n;
                        if (total > 0) {
                            int p = (int)(done * 100L / total);
                            ui(() -> { bar.setProgress(p); pct.setText(p + "%"); });
                            nb.setProgress(100, p, false).setContentText(p + "%");
                            try { nm.notify(notifId, nb.build()); } catch (Exception ig) {}
                        }
                    }
                }
                ContentValues cv2 = new ContentValues();
                cv2.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(dlUri, cv2, null, null);
                return true;
            } catch (Exception e) {
                if (attempt < 4) try { Thread.sleep(2000L*(attempt+1)); } catch (Exception ig) {}
            }
        }
        if (dlUri != null) try { getContentResolver().delete(dlUri, null, null); } catch (Exception ig) {}
        return false;
    }

    private boolean dlFileSystem(String url, String filename,
                                 ProgressBar bar, TextView pct,
                                 NotificationCompat.Builder nb,
                                 NotificationManagerCompat nm, int notifId) {
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NazzilhaPlus");
        dir.mkdirs();
        File file = new File(dir, filename);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long existing = file.exists() ? file.length() : 0;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) break;
                long total = existing + conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(file, code == 206)) {
                    byte[] buf = new byte[8192]; int n; long done = existing;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n); done += n;
                        if (total > 0) {
                            int p = (int)(done * 100L / total);
                            ui(() -> { bar.setProgress(p); pct.setText(p + "%"); });
                            nb.setProgress(100, p, false).setContentText(p + "%");
                            try { nm.notify(notifId, nb.build()); } catch (Exception ig) {}
                        }
                    }
                }
                android.media.MediaScannerConnection.scanFile(
                    this, new String[]{file.getAbsolutePath()}, null, null);
                return true;
            } catch (Exception e) {
                if (attempt < 4) try { Thread.sleep(2000L*(attempt+1)); } catch (Exception ig) {}
            }
        }
        return false;
    }

    private void showDlDoneNotif(String filename) {
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_DL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("✅ اكتمل التحميل")
            .setContentText(filename)
            .setAutoCancel(true);
        try { NotificationManagerCompat.from(this).notify(filename.hashCode() + 1, nb.build()); }
        catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Static helpers
    // ══════════════════════════════════════════════════════════════════════════

    static void start(Context ctx) {
        Intent i = new Intent(ctx, BubbleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(i);
        else
            ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BubbleService.class));
    }

    static boolean isRunning(Context ctx) {
        android.app.ActivityManager am =
            (android.app.ActivityManager) ctx.getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(50))
            if (BubbleService.class.getName().equals(s.service.getClassName())) return true;
        return false;
    }

    private void ui(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private void safeRemove(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) {}
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private String readBody(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        int total = 0;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > 512 * 1024) throw new IOException("Response too large");
            baos.write(buf, 0, n);
        }
        return baos.toString("UTF-8");
    }

    private String sanitize(String t) {
        if (t == null || t.isEmpty()) return "video";
        return t.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String mimeFor(String f) {
        if (f.endsWith(".mp3")) return "audio/mpeg";
        if (f.endsWith(".m4a")) return "audio/mp4";
        if (f.endsWith(".aac")) return "audio/aac";
        return "video/mp4";
    }
}
