package moe.ouom.wekit.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import moe.ouom.wekit.utils.log.WeLogger;

public class ShadowSafTransientActivity extends Activity {

    public static final String PARAM_TARGET_ACTION = "ShadowSafTransientActivity.PARAM_TARGET_ACTION";
    public static final String PARAM_SEQUENCE = "ShadowSafTransientActivity.PARAM_SEQUENCE";
    public static final String PARAM_FILE_NAME = "ShadowSafTransientActivity.PARAM_FILE_NAME";
    public static final String PARAM_MINE_TYPE = "ShadowSafTransientActivity.PARAM_MINE_TYPE";

    public static final int TARGET_ACTION_READ = 1;
    public static final int TARGET_ACTION_CREATE_AND_WRITE = 2;
    public static final int TARGET_ACTION_OPEN_DOCUMENT_TREE = 3;

    private static final int REQ_READ_FILE = 10001;
    private static final int REQ_WRITE_FILE = 10002;
    private static final int REQ_OPEN_DIR = 10003;

    private int mSequence;
    private int mTargetAction;
    private int mOriginRequest;
    private String mMimeType;
    private String mFileName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        super.onCreate(savedInstanceState);
        var extras = getIntent().getExtras();
        if (extras == null) {
            finish();
            return;
        }
        mTargetAction = extras.getInt(PARAM_TARGET_ACTION, -1);
        mSequence = extras.getInt(PARAM_SEQUENCE, -1);
        mFileName = extras.getString(PARAM_FILE_NAME);
        mMimeType = extras.getString(PARAM_MINE_TYPE);
        if (mTargetAction < 0 || mSequence < 0) {
            finish();
            return;
        }
        var request = sRequestMap.get(mSequence);
        if (request == null) {
            WeLogger.e("sequence not found: " + mSequence);
            finish();
            return;
        }
        Intent intent;
        switch (mTargetAction) {
            case TARGET_ACTION_READ:
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(request.mimeType != null ? request.mimeType : "*/*");
                mOriginRequest = REQ_READ_FILE;
                break;
            case TARGET_ACTION_CREATE_AND_WRITE:
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (request.fileName != null) {
                    intent.putExtra(Intent.EXTRA_TITLE, request.fileName);
                }
                intent.setType(request.mimeType != null ? request.mimeType : "*/*");
                mOriginRequest = REQ_WRITE_FILE;
                break;
            case TARGET_ACTION_OPEN_DOCUMENT_TREE:
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                mOriginRequest = REQ_OPEN_DIR;
                break;
            default:
                throw new IllegalArgumentException("Unknown target action: " + mTargetAction);
        }
        try {
            startActivityForResult(intent, mOriginRequest);
        } catch (ActivityNotFoundException e) {
            request.callback.onException(e);
            sRequestMap.remove(mSequence);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == mOriginRequest) {
            var uri = data != null ? data.getData() : null;
            var request = sRequestMap.get(mSequence);
            if (request != null) {
                request.callback.onResult(uri);
            }
            sRequestMap.remove(mSequence);
            finish();
        }
    }

    public record Request(int sequence, int targetAction, String mimeType, String fileName,
                          RequestResultCallback callback) {
    }

    private static final ConcurrentHashMap<Integer, Request> sRequestMap = new ConcurrentHashMap<>();
    private static final AtomicInteger sSequenceGenerator = new AtomicInteger(10000);

    public static void startActivityForRequest(@NonNull Context host, int targetAction,
                                               @Nullable String mimeType, @Nullable String fileName,
                                               @NonNull RequestResultCallback callback) {
        var sequence = sSequenceGenerator.incrementAndGet();
        var request = new Request(sequence, targetAction, mimeType, fileName, callback);
        sRequestMap.put(sequence, request);
        var start = new Intent(host, ShadowSafTransientActivity.class);
        start.putExtra(PARAM_SEQUENCE, sequence);
        start.putExtra(PARAM_TARGET_ACTION, targetAction);
        start.putExtra(PARAM_FILE_NAME, fileName);
        start.putExtra(PARAM_MINE_TYPE, mimeType);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        host.startActivity(start);
    }

    public interface RequestResultCallback {
        @UiThread
        void onResult(@Nullable Uri uri);

        @UiThread
        void onException(@NonNull Throwable e);
    }
}