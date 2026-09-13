package com.nazzilhaplus.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class OnboardingActivity extends AppCompatActivity {

    private int currentPage = 1;
    private View page1, page2, page3;
    private View dot1, dot2, dot3;
    private TextView nextBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(android.graphics.Color.parseColor("#0a0a0f"));
        setContentView(R.layout.activity_onboarding);

        page1   = findViewById(R.id.page1);
        page2   = findViewById(R.id.page2);
        page3   = findViewById(R.id.page3);
        dot1    = findViewById(R.id.dot1);
        dot2    = findViewById(R.id.dot2);
        dot3    = findViewById(R.id.dot3);
        nextBtn = findViewById(R.id.nextBtn);

        nextBtn.setOnClickListener(v -> {
            if (currentPage < 3) {
                currentPage++;
                showPage(currentPage);
            } else {
                finish_onboarding();
            }
        });

        findViewById(R.id.skipBtn).setOnClickListener(v -> finish_onboarding());
    }

    private void showPage(int page) {
        page1.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        page2.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        page3.setVisibility(page == 3 ? View.VISIBLE : View.GONE);

        int active  = Color.parseColor("#a855f7");
        int inactive = Color.parseColor("#2a2a3a");

        dot1.setBackgroundColor(page == 1 ? active : inactive);
        dot2.setBackgroundColor(page == 2 ? active : inactive);
        dot3.setBackgroundColor(page == 3 ? active : inactive);

        resize(dot1, page == 1 ? 10 : 8);
        resize(dot2, page == 2 ? 10 : 8);
        resize(dot3, page == 3 ? 10 : 8);

        nextBtn.setText(page == 3 ? "🚀 ابدأ الآن" : "التالي ←");
    }

    private void resize(View v, int dp) {
        float density = getResources().getDisplayMetrics().density;
        int px = Math.round(dp * density);
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = px; lp.height = px;
        v.setLayoutParams(lp);
    }

    private void finish_onboarding() {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
