package com.tencent.tinker.loader.app;

import android.app.Application;

import androidx.annotation.NonNull;

public class TinkerApplication extends Application {

    @NonNull
    public static TinkerApplication getInstance() {
        throw new RuntimeException("Stub!");
    }

    protected ClassLoader mCurrentClassLoader;
}
