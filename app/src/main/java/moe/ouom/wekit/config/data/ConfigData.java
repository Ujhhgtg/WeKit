package moe.ouom.wekit.config.data;

import android.os.Looper;
import android.widget.Toast;

import moe.ouom.wekit.config.ConfigManager;
import moe.ouom.wekit.host.HostInfo;
import moe.ouom.wekit.util.common.SyncUtils;
import moe.ouom.wekit.util.log.WeLogger;

public class ConfigData<T> {

    final String mKeyName;
    final ConfigManager mgr;

    public ConfigData(String keyName) {
        this(keyName, ConfigManager.getDefaultConfig());
    }

    public ConfigData(String keyName, ConfigManager manager) {
        mKeyName = keyName;
        mgr = manager;
    }

    public void remove() {
        try {
            mgr.remove(mKeyName);
        } catch (Exception e) {
            WeLogger.e(e);
        }
    }

    public T getValue() {
        try {
            return (T) mgr.getObject(mKeyName);
        } catch (Exception e) {
            try {
                mgr.remove(mKeyName);
            } catch (Exception ignored) {
            }
            WeLogger.e(e);
            return null;
        }
    }

    public void setValue(T value) {
        try {
            mgr.putObject(mKeyName, value);
            mgr.save();
        } catch (Exception e) {
            try {
                mgr.remove(mKeyName);
            } catch (Exception ignored) {
            }
            WeLogger.e(e);
            if (Looper.myLooper() == Looper.getMainLooper()) {
//                Toasts.error(HostInfo.getApplication(), "设置存储失败, 请重新设置" + e);
                Toast.makeText(HostInfo.getApplication(), "设置存储失败, 请重新设置", Toast.LENGTH_SHORT).show();
            } else {
                SyncUtils.post(() ->
                    Toast.makeText(HostInfo.getApplication(), "设置存储失败, 请重新设置", Toast.LENGTH_SHORT).show()
                );
            }
        }
    }

    public T getOrDefault(T def) {
        try {
            return (T) mgr.getOrDefault(mKeyName, def);
        } catch (Exception e) {
            WeLogger.e(e);
            return def;
        }
    }
}
