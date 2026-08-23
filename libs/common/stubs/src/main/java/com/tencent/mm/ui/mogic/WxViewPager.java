package com.tencent.mm.ui.mogic;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

public class WxViewPager extends ViewGroup {

    public WxViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WxViewPager(Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        throw new RuntimeException("Stub!");
    }

    public int getCurrentItem() {
        throw new RuntimeException("Stub!");
    }

    public void setCurrentItem(int item, boolean smoothScroll) {
        throw new RuntimeException("Stub!");
    }
}
