package android.app;

import android.os.IBinder;
import android.util.ArrayMap;

import androidx.annotation.Nullable;

public class ActivityThread {

    final ArrayMap<IBinder, ActivityClientRecord> mActivities = new ArrayMap<>();

    public Application getApplication() {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    public static Application currentApplication() {
        throw new RuntimeException("Stub!");
    }

    public static ActivityThread currentActivityThread() {
        throw new RuntimeException("Stub!");
    }

    public static final class ActivityClientRecord {}
}
