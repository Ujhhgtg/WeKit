package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 会话内容区桩 (FrameLayout): 「x条新消息」气泡 (bm4) 与消息列表宿主的直接父容器。 */
public class ChattingContent extends FrameLayout {

    public ChattingContent(Context context) {
        super(context);
    }

    public ChattingContent(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChattingContent(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
