package com.nazzilhaplus.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

public class AppOpenAdManager implements Application.ActivityLifecycleCallbacks {

    private AppOpenAd appOpenAd = null;
    private boolean isLoadingAd = false;
    private boolean isShowingAd = false;
    private Activity currentActivity = null;
    private int activityCount = 0;
    private final Application application;
    private final String adUnitId;

    public AppOpenAdManager(Application app, String adUnitId) {
        this.application = app;
        this.adUnitId = adUnitId;
        app.registerActivityLifecycleCallbacks(this);
        loadAd();
    }

    private void loadAd() {
        if (isLoadingAd || isAdAvailable()) return;
        isLoadingAd = true;
        AppOpenAd.load(application, adUnitId, new AdRequest.Builder().build(),
            new AppOpenAd.AppOpenAdLoadCallback() {
                @Override public void onAdLoaded(@NonNull AppOpenAd ad) {
                    appOpenAd = ad;
                    isLoadingAd = false;
                }
                @Override public void onAdFailedToLoad(@NonNull LoadAdError e) {
                    appOpenAd = null;
                    isLoadingAd = false;
                }
            });
    }

    private boolean isAdAvailable() {
        return appOpenAd != null;
    }

    private void showAdIfAvailable() {
        if (isShowingAd || !isAdAvailable() || currentActivity == null) {
            loadAd();
            return;
        }
        appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                appOpenAd = null;
                isShowingAd = false;
                loadAd();
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                appOpenAd = null;
                isShowingAd = false;
                loadAd();
            }
            @Override public void onAdShowedFullScreenContent() {
                isShowingAd = true;
            }
        });
        appOpenAd.show(currentActivity);
    }

    // Track foreground: activityCount 0→1 means app came from background
    @Override public void onActivityStarted(@NonNull Activity a) {
        currentActivity = a;
        if (!isShowingAd) {
            activityCount++;
            if (activityCount == 1) showAdIfAvailable();
        }
    }

    @Override public void onActivityStopped(@NonNull Activity a) {
        if (!isShowingAd) activityCount--;
    }

    @Override public void onActivityResumed(@NonNull Activity a)  { currentActivity = a; }
    @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
    @Override public void onActivityPaused(@NonNull Activity a)   {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {
        if (currentActivity == a) currentActivity = null;
    }
}
