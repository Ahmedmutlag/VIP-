package com.nazzilhaplus.app;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileBrowserActivity extends AppCompatActivity {

    private LinearLayout fileList;
    private LinearLayout emptyState;
    private LinearLayout deleteBar;
    private TextView selectedCountTv;
    private TextView selectAllBtn;

    private List<VideoFile> files = new ArrayList<>();
    private Set<Integer> selectedIndices = new HashSet<>();
    private boolean selectMode = false;

    static class VideoFile {
        long id; String name; long size; long date; Uri uri;
        VideoFile(long id, String name, long size, long date, Uri uri) {
            this.id = id; this.name = name; this.size = size;
            this.date = date; this.uri = uri;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        fileList       = findViewById(R.id.fileList);
        emptyState     = findViewById(R.id.emptyState);
        deleteBar      = findViewById(R.id.deleteBar);
        selectedCountTv = findViewById(R.id.selectedCountTv);
        selectAllBtn   = findViewById(R.id.selectAllBtn);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());

        selectAllBtn.setOnClickListener(v -> {
            if (selectedIndices.size() == files.size()) {
                selectedIndices.clear();
            } else {
                for (int i = 0; i < files.size(); i++) selectedIndices.add(i);
            }
            updateSelectMode();
            renderList();
        });

        findViewById(R.id.deleteBtn).setOnClickListener(v -> confirmDelete());

        loadFiles();
    }

    private void loadFiles() {
        new Thread(() -> {
            List<VideoFile> result = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String[] proj = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED
                };
                try (Cursor c = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                        MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                        new String[]{"%NazzilhaPlus%"},
                        MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
                    if (c != null) while (c.moveToNext()) {
                        long id   = c.getLong(0);
                        String nm = c.getString(1);
                        long sz   = c.getLong(2);
                        long dt   = c.getLong(3) * 1000L;
                        Uri uri   = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                        result.add(new VideoFile(id, nm, sz, dt, uri));
                    }
                } catch (Exception ignored) {}
            } else {
                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "NazzilhaPlus");
                if (dir.exists()) {
                    File[] arr = dir.listFiles();
                    if (arr != null) {
                        Arrays.sort(arr, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        for (File f : arr)
                            result.add(new VideoFile(-1, f.getName(), f.length(), f.lastModified(), Uri.fromFile(f)));
                    }
                }
            }
            runOnUiThread(() -> { files = result; renderList(); });
        }).start();
    }

    private void renderList() {
        fileList.removeAllViews();
        if (files.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            fileList.setVisibility(View.GONE);
            return;
        }
        emptyState.setVisibility(View.GONE);
        fileList.setVisibility(View.VISIBLE);

        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        int dp8  = dp(8);
        int dp12 = dp(12);
        int dp16 = dp(16);

        for (int i = 0; i < files.size(); i++) {
            VideoFile f = files.get(i);
            boolean selected = selectedIndices.contains(i);
            final int idx = i;

            // Card row
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(dp12, dp12, dp12, dp12);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(selected ? 0xFF1e1530 : 0xFF16161f);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), selected ? 0xFF7c3aed : 0xFF2a2a3a);
            card.setBackground(bg);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, dp8);
            card.setLayoutParams(cardParams);

            // Thumbnail
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(72), dp(52));
            thumbParams.setMarginEnd(dp12);
            thumb.setLayoutParams(thumbParams);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable thumbBg = new android.graphics.drawable.GradientDrawable();
            thumbBg.setColor(0xFF2a2a3a);
            thumbBg.setCornerRadius(dp(8));
            thumb.setBackground(thumbBg);
            thumb.setImageResource(android.R.drawable.ic_media_play);
            thumb.setColorFilter(0xFF8888aa);
            card.addView(thumb);

            // Load thumbnail async
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && f.id > 0) {
                new Thread(() -> {
                    try {
                        Bitmap bmp = getContentResolver().loadThumbnail(f.uri, new Size(128, 90), null);
                        runOnUiThread(() -> { thumb.setImageBitmap(bmp); thumb.setColorFilter(null); });
                    } catch (Exception ignored) {}
                }).start();
            }

            // Text column
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView nameTv = new TextView(this);
            nameTv.setText(f.name);
            nameTv.setTextColor(0xFFf0f0f8);
            nameTv.setTextSize(13f);
            nameTv.setMaxLines(2);
            nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(nameTv);

            TextView metaTv = new TextView(this);
            metaTv.setText(formatSize(f.size) + "  ·  " + sdf.format(new Date(f.date)));
            metaTv.setTextColor(0xFF8888aa);
            metaTv.setTextSize(11f);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = dp(3);
            metaTv.setLayoutParams(metaParams);
            textCol.addView(metaTv);

            card.addView(textCol);

            // Checkbox (shown in select mode)
            TextView check = new TextView(this);
            check.setText(selected ? "☑" : "☐");
            check.setTextSize(20f);
            check.setTextColor(selected ? 0xFFa855f7 : 0xFF8888aa);
            check.setPadding(dp8, 0, 0, 0);
            check.setVisibility(selectMode ? View.VISIBLE : View.GONE);
            card.addView(check);

            // Click listeners
            card.setOnClickListener(v -> {
                if (selectMode) {
                    toggleSelect(idx);
                } else {
                    openVideo(f);
                }
            });
            card.setOnLongClickListener(v -> {
                if (!selectMode) {
                    selectMode = true;
                    updateSelectMode();
                }
                toggleSelect(idx);
                return true;
            });

            fileList.addView(card);
        }
    }

    private void toggleSelect(int idx) {
        if (selectedIndices.contains(idx)) selectedIndices.remove(idx);
        else selectedIndices.add(idx);
        updateSelectMode();
        renderList();
    }

    private void updateSelectMode() {
        if (selectedIndices.isEmpty() && selectMode) {
            selectMode = false;
            deleteBar.setVisibility(View.GONE);
            selectAllBtn.setVisibility(View.GONE);
        } else if (selectMode) {
            deleteBar.setVisibility(View.VISIBLE);
            selectAllBtn.setVisibility(View.VISIBLE);
            selectedCountTv.setText("محدد: " + selectedIndices.size());
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
            .setTitle("حذف الملفات")
            .setMessage("حذف " + selectedIndices.size() + " ملف؟")
            .setPositiveButton("حذف", (d, w) -> deleteSelected())
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void deleteSelected() {
        List<Integer> sorted = new ArrayList<>(selectedIndices);
        sorted.sort((a, b) -> b - a);
        for (int idx : sorted) {
            VideoFile f = files.get(idx);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && f.id > 0) {
                    getContentResolver().delete(f.uri, null, null);
                } else {
                    new File(f.uri.getPath()).delete();
                }
                files.remove(idx);
            } catch (Exception ignored) {}
        }
        selectedIndices.clear();
        selectMode = false;
        deleteBar.setVisibility(View.GONE);
        selectAllBtn.setVisibility(View.GONE);
        Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show();
        renderList();
    }

    private void openVideo(VideoFile f) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra("uri", f.uri.toString());
        intent.putExtra("title", f.name);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
