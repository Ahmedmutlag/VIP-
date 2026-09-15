package com.nazzilhaplus.app;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        new AppOpenAdManager(this, "");
    }
}
