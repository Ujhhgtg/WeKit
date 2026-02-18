package moe.ouom.wekit.utils.common;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class CheckUtils {

    private CheckUtils() {
        throw new UnsupportedOperationException("No instances");
    }

    public static void checkNonNull(Object obj, String message) {
        if (obj == null) {
            throw new NullPointerException(message);
        }
    }

    public static boolean isInteger(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isJSON(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            if (text.trim().startsWith("{")) {
                new JSONObject(text);
            } else if (text.trim().startsWith("[")) {
                new JSONArray(text);
            } else {
                return false;
            }
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

}
