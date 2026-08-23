package com.tencent.mm.pluginsdk.ui.tools;

import android.content.Context;
import android.util.AttributeSet;
import HOST.androidx.recyclerview.widget.RecyclerView;

/**
 * 微信聊天消息列表的实际 RecyclerView 包装类桩。
 * <p>
 * 8.0.65–8.0.76 中 {@code ScrollControlRecyclerView extends ChattingRecyclerView},
 * 一次 {@code is ChattingRecyclerView} 检查即可覆盖两种运行时类型。这里只声明参数/返回值
 * 都是原语的方法: 返回宿主类型的方法 (如 getAdapter) 签名对不上会抛 NoSuchMethodError。
 */
public class ChattingRecyclerView extends RecyclerView {

    public ChattingRecyclerView(Context context) {
        super(context);
    }

    public ChattingRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChattingRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 消息总数 (含头/尾视图), 各版本保留 (反编译 ChattingRecyclerView.java:86 / m3|v3.java:34)。
     * 返回 int, 签名不涉及宿主类型, 可以安全地通过桩直接调用。
     */
    public int getCount() {
        throw new RuntimeException("Stub!");
    }

    /**
     * 头/尾视图数量。外层包装 adapter 的 getItemCount() = header + footer + 内层消息数,
     * 计算"最后一条消息的内层数据位置"需要把它们扣掉 (m3|v3.java:38/40, r3.java)。
     */
    public int getHeaderViewsCount() {
        throw new RuntimeException("Stub!");
    }

    public int getFooterViewsCount() {
        throw new RuntimeException("Stub!");
    }
}
