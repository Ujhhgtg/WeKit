package com.tencent.neattextview.textview.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class NeatTextView extends View {

    public NeatTextView(Context context) {
        super(context);
    }

    public NeatTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NeatTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TextView getWrappedTextView() {
        throw new RuntimeException("Stub!");
    }
}
