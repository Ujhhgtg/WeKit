package com.tencent.mm.ui.chatting.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/** 聊天消息列表宿主桩: 把查找范围限定在真正的消息列表内, 避免命中表情面板等其他 RecyclerView。 */
public class MMChattingListView extends ViewGroup {

    public MMChattingListView(Context context) {
        super(context);
    }

    public MMChattingListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MMChattingListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    }
}
