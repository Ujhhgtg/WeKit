package com.android.internal.content;

import android.content.Intent;
import android.os.IBinder;

public class ReferrerIntent extends Intent {

    public String mReferrer;

    public IBinder mCallerToken;

    public ReferrerIntent(Intent baseIntent, String referrer) {
        throw new RuntimeException("Stub!");
    }

    public ReferrerIntent(Intent baseIntent, String referrer, IBinder callerToken) {
        throw new RuntimeException("Stub!");
    }
}
