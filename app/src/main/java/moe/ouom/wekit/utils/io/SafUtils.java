package moe.ouom.wekit.utils.io;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.Objects;

import moe.ouom.wekit.activity.ShadowSafTransientActivity;
import moe.ouom.wekit.host.HostInfo;
import moe.ouom.wekit.utils.common.SyncUtils;
import moe.ouom.wekit.utils.log.WeLogger;


public class SafUtils {

    public interface SafSelectFileResultCallback {
        void onResult(@NonNull Uri uri);
    }

    /**
     * Request to save a file via SAF.
     */
    public static SaveFileTransaction requestSaveFile(@NonNull Context context) {
        checkProcess();
        return new SaveFileTransaction(context);
    }

    public static class SaveFileTransaction {
        private final Context context;
        private String defaultFileName;
        private String mimeType;
        private SafSelectFileResultCallback resultCallback;
        private Runnable cancelCallback;

        private SaveFileTransaction(@NonNull Context context) {
            Objects.requireNonNull(context, "context");
            this.context = context;
        }

        @NonNull
        public SaveFileTransaction setDefaultFileName(@NonNull String fileName) {
            this.defaultFileName = fileName;
            return this;
        }

        @NonNull
        public SaveFileTransaction setMimeType(@Nullable String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @NonNull
        public SaveFileTransaction onResult(@NonNull SafSelectFileResultCallback callback) {
            Objects.requireNonNull(callback, "callback");
            this.resultCallback = callback;
            return this;
        }

        @NonNull
        public SaveFileTransaction onCancel(@Nullable Runnable callback) {
            this.cancelCallback = callback;
            return this;
        }

        public void commit() {
            Objects.requireNonNull(resultCallback);
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }
            var internalCb = new ShadowSafTransientActivity.RequestResultCallback() {
                @Override
                public void onResult(@Nullable Uri uri) {
                    if (uri != null) {
                        resultCallback.onResult(uri);
                    } else if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                }

                @Override
                public void onException(@NonNull Throwable e) {
                    complainAboutNoSafActivity(context, e);
                    if (cancelCallback != null) cancelCallback.run();
                }
            };
            ShadowSafTransientActivity.startActivityForRequest(
                    context,
                    ShadowSafTransientActivity.TARGET_ACTION_CREATE_AND_WRITE,
                    mimeType,
                    defaultFileName,
                    internalCb
            );
        }
    }

    /**
     * Request to open a file via SAF.
     */
    public static OpenFileTransaction requestOpenFile(@NonNull Context context) {
        checkProcess();
        return new OpenFileTransaction(context);
    }

    public static class OpenFileTransaction {
        private final Context context;
        private String mimeType;
        private SafSelectFileResultCallback resultCallback;
        private Runnable cancelCallback;

        private OpenFileTransaction(@NonNull Context context) {
            Objects.requireNonNull(context, "context");
            this.context = context;
        }

        @NonNull
        public OpenFileTransaction setMimeType(@Nullable String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @NonNull
        public OpenFileTransaction onResult(@NonNull SafSelectFileResultCallback callback) {
            Objects.requireNonNull(callback, "callback");
            this.resultCallback = callback;
            return this;
        }

        @NonNull
        public OpenFileTransaction onCancel(@Nullable Runnable callback) {
            this.cancelCallback = callback;
            return this;
        }

        public void commit() {
            Objects.requireNonNull(resultCallback);
            var internalCb = new ShadowSafTransientActivity.RequestResultCallback() {
                @Override
                public void onResult(@Nullable Uri uri) {
                    if (uri != null) {
                        resultCallback.onResult(uri);
                    } else if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                }

                @Override
                public void onException(@NonNull Throwable e) {
                    complainAboutNoSafActivity(context, e);
                    if (cancelCallback != null) cancelCallback.run();
                }
            };
            ShadowSafTransientActivity.startActivityForRequest(
                    context,
                    ShadowSafTransientActivity.TARGET_ACTION_READ,
                    mimeType,
                    null,
                    internalCb
            );
        }
    }

    /**
     * Request to select a directory via SAF.
     */
    public static DirectorySelectTransaction requestSelectDirectory(@NonNull Context context) {
        checkProcess();
        return new DirectorySelectTransaction(context);
    }

    public static class DirectorySelectTransaction {
        private final Context context;
        private SafSelectFileResultCallback resultCallback;
        private Runnable cancelCallback;

        private DirectorySelectTransaction(@NonNull Context context) {
            Objects.requireNonNull(context, "context");
            this.context = context;
        }

        @NonNull
        public DirectorySelectTransaction onResult(@NonNull SafSelectFileResultCallback callback) {
            Objects.requireNonNull(callback, "callback");
            this.resultCallback = callback;
            return this;
        }

        @NonNull
        public DirectorySelectTransaction onCancel(@Nullable Runnable callback) {
            this.cancelCallback = callback;
            return this;
        }

        public void commit() {
            Objects.requireNonNull(resultCallback);
            var internalCb = new ShadowSafTransientActivity.RequestResultCallback() {
                @Override
                public void onResult(@Nullable Uri uri) {
                    if (uri != null) {
                        resultCallback.onResult(uri);
                    } else if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                }

                @Override
                public void onException(@NonNull Throwable e) {
                    complainAboutNoSafActivity(context, e);
                    if (cancelCallback != null) cancelCallback.run();
                }
            };
            ShadowSafTransientActivity.startActivityForRequest(
                    context,
                    ShadowSafTransientActivity.TARGET_ACTION_OPEN_DOCUMENT_TREE,
                    null,
                    null,
                    internalCb
            );
        }
    }

    @UiThread
    private static void complainAboutNoSafActivity(@NonNull Context context, @NonNull Throwable e) {
        var msg = WeLogger.getStackTraceString();
        new AlertDialog.Builder(context)
                .setTitle("ActivityNotFoundException")
                .setMessage("找不到处理 SAF Intent 的 Activity，可能是系统问题。\n" +
                        "Android 规范要求必须有应用能够处理这些 Intent，但是有些系统没有实现这个规范。")
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(android.R.string.copy, (dialog, which) -> {
                    var clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    var clip = ClipData.newPlainText("wekit-error", msg);
                    clipboard.setPrimaryClip(clip);
                })
                .show();
    }

    private static void checkProcess() {
        if (HostInfo.isInHostProcess() && !SyncUtils.isMainProcess()) {
            throw new IllegalStateException("This method can only be called in the main process");
        }
    }
}