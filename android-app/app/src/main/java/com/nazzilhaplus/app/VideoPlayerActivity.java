package com.nazzilhaplus.app;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;

public class VideoPlayerActivity extends AppCompatActivity {

    private VideoView videoView;
    private SeekBar seekBar;
    private ImageButton playPauseBtn;
    private TextView currentTime;
    private TextView totalTime;
    private View controls;

    private final Handler handler = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (videoView.isPlaying()) {
                int pos = videoView.getCurrentPosition();
                seekBar.setProgress(pos);
                currentTime.setText(formatTime(pos));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_video_player);

        videoView    = findViewById(R.id.videoView);
        seekBar      = findViewById(R.id.seekBar);
        playPauseBtn = findViewById(R.id.playPauseBtn);
        currentTime  = findViewById(R.id.currentTime);
        totalTime    = findViewById(R.id.totalTime);
        controls     = findViewById(R.id.controls);

        String uriStr = getIntent().getStringExtra("uri");
        String title  = getIntent().getStringExtra("title");

        ((TextView) findViewById(R.id.videoTitle)).setText(title != null ? title : "");

        if (uriStr != null) videoView.setVideoURI(Uri.parse(uriStr));

        videoView.setOnPreparedListener(mp -> {
            mp.start();
            int dur = videoView.getDuration();
            seekBar.setMax(dur > 0 ? dur : 0);
            totalTime.setText(formatTime(dur));
            updatePlayPauseIcon();
            handler.post(ticker);
        });

        videoView.setOnCompletionListener(mp -> {
            updatePlayPauseIcon();
            handler.removeCallbacks(ticker);
        });

        playPauseBtn.setOnClickListener(v -> {
            if (videoView.isPlaying()) videoView.pause();
            else videoView.start();
            updatePlayPauseIcon();
        });

        // Tap video to toggle controls
        videoView.setOnClickListener(v -> {
            controls.setVisibility(controls.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    videoView.seekTo(progress);
                    currentTime.setText(formatTime(progress));
                }
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
    }

    private void updatePlayPauseIcon() {
        playPauseBtn.setImageResource(videoView.isPlaying()
            ? android.R.drawable.ic_media_pause
            : android.R.drawable.ic_media_play);
    }

    private String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView.isPlaying()) videoView.pause();
        updatePlayPauseIcon();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        videoView.stopPlayback();
    }
}
