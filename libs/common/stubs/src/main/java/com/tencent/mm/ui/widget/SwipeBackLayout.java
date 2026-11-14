package com.tencent.mm.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class SwipeBackLayout extends FrameLayout {

    public SwipeBackLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setEnableGesture(boolean z16) {
        throw new RuntimeException("Stub!");
    }

    public void setLikeIOSAnim(boolean z16) {
        throw new RuntimeException("Stub!");
    }

    /** Lets the rest of the current touch stream pass to the children; reset by the next DOWN. */
    public void setOnceDisEnableGesture(boolean z16) {
        throw new RuntimeException("Stub!");
    }

    /** The view the drag engine slides. */
    public View getTargetContentView() {
        throw new RuntimeException("Stub!");
    }
}
