package com.tencent.mm.ui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.view.View;

import com.tencent.mm.ui.widget.SwipeBackLayout;

public class MMFragment {

    public void onSwipeBack() {
        throw new RuntimeException("Stub!");
    }

    public SwipeBackLayout getSwipeBackLayout() {
        throw new RuntimeException("Stub!");
    }

    /** Whether WeChat's swipe-back (and its SwipeBackLayout wrapper) is enabled for this fragment. */
    public final boolean isSupportNavigationSwipeBack() {
        throw new RuntimeException("Stub!");
    }

    /** Inherited from WeChat's androidx Fragment. */
    public View getView() {
        throw new RuntimeException("Stub!");
    }

    public void finish() {
        throw new RuntimeException("Stub!");
    }

    public final Activity getContext() {
        throw new RuntimeException("Stub!");
    }

    public boolean removeOptionMenu(int i17) {
        throw new RuntimeException("Stub!");
    }

    public void addTextOptionMenu(int i17, String str, MenuItem.OnMenuItemClickListener onMenuItemClickListener) {
        throw new RuntimeException("Stub!");
    }

    public void addTextOptionMenu(int i17, String str, MenuItem.OnMenuItemClickListener onMenuItemClickListener, View.OnLongClickListener onLongClickListener) {
        throw new RuntimeException("Stub!");
    }

    public void addIconOptionMenu(int i17, String str, Drawable drawable, MenuItem.OnMenuItemClickListener onMenuItemClickListener) {
        throw new RuntimeException("Stub!");
    }
}
