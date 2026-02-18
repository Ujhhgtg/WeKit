package moe.ouom.wekit.utils.common;

import android.content.Context;
import android.widget.Toast;

import moe.ouom.wekit.host.HostInfo;
import moe.ouom.wekit.utils.log.WeLogger;

public class Toasts {
    static public void showToast(Context ctx, String msg) {
        WeLogger.i("Toasts", "showToast: " + msg);
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    static public void showToast(String msg) {
        WeLogger.i("Toasts", "showToast: " + msg);
        Toast.makeText(HostInfo.getApplication(), msg, Toast.LENGTH_SHORT).show();
    }
}
