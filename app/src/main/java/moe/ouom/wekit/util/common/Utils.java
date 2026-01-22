package moe.ouom.wekit.util.common;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import moe.ouom.wekit.util.log.WeLogger;

public class Utils {
    private static Handler sHandler;

    public static List<View> getAllViews(Activity act) {
        return getAllChildViews(act.getWindow().getDecorView());
    }

    private static List<View> getAllChildViews(View view) {
        List<View> allChildren = new ArrayList<>();
        if (view instanceof ViewGroup vp) {
            for (int i = 0; i < vp.getChildCount(); i++) {
                View viewChild = vp.getChildAt(i);
                allChildren.add(viewChild);
                allChildren.addAll(getAllChildViews(viewChild));
            }
        }
        return allChildren;
    }

    public static View getViewByDesc(Activity act, String desc, int limit) throws InterruptedException {
        for (int x = 0; x < limit; x++){
            for (View view : getAllViews(act)) {
                try {
                    if (view.getContentDescription().equals(desc)) {
                        return view;
                    }
                } catch (Exception ignored) {}

            }
            Thread.sleep(200);
        }



        return null;
    }

    public static View getViewByDesc(Activity act, String desc) {
        try {
            for (View view : getAllViews(act)) {
                try {
                    if (view.getContentDescription().equals(desc)) {
                        return view;
                    }
                } catch (Exception ignored) {}

            }
        } catch (Exception e) {
            WeLogger.e(e);
        }


        return null;
    }

    public static void printStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        WeLogger.e("---------------------- [Stack Trace] ----------------------");
        for (StackTraceElement element : stackTrace) {
            WeLogger.d("    at " + element.toString());
        }
        WeLogger.e("^---------------------- over ----------------------^");
    }


    public static void printIntentExtras(String TAG, Intent intent) {
        if (intent == null) {
            WeLogger.e("Intent is null or has no extras.");
            return;
        }

        WeLogger.i("*-------------------- " + TAG + " --------------------*");
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                WeLogger.d(key + " = " + Objects.requireNonNull(value) + "(" + value.getClass() + ")");
            }
        } else {
            WeLogger.w("No extras found in the Intent.");
        }

        WeLogger.i("^-------------------- " + "OVER~" + " --------------------^");
    }

    public static Activity getActivityFromView(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }


    public static Activity getActivityFromContext(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static void jumpUrl(Context context, String webUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(webUrl));
        context.startActivity(intent);
    }

    public static String convertTimestampToDate(long timestamp) {
        Date date = new Date(timestamp);
        @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date);
    }

    public static Method findMethodByName(Class<?> clazz, String methodName) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Method not found: " + methodName);
    }

    public static String timeToFormat(long time) {
        @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(time);
    }

    public static String[] parseURLComponents(String url) {
        String host = "";
        String type = "";
        try {
            URL Url = new URL(url);
            host = Url.getHost();
            type = Url.toURI().getScheme();
        } catch (Exception ignored) {}
        return new String[] {host, type};
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static Object deepGet(Object obj, String path, Object def) {
        try {
            String[] keys = path.split("\\.");

            Object current = obj;

            for (String key : keys) {

                if (current == null) return def;

                if (current instanceof JSONObject json) {

                    if (json.has(key)) {
                        current = json.opt(key);
                        continue;
                    }

                    return def;
                }

                else if (current instanceof JSONArray arr) {

                    if (!key.matches("\\d+")) return def;

                    int index = Integer.parseInt(key);
                    if (index < 0 || index >= arr.length()) return def;

                    current = arr.opt(index);
                }

                else {
                    return def;
                }
            }

            return current != null ? current : def;
        } catch (Exception e) {
            return def;
        }
    }
}
