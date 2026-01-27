package moe.ouom.wekit.util.common;

import android.content.Context;
import android.widget.Toast;

public class Toasts {
    static public void showToast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}
