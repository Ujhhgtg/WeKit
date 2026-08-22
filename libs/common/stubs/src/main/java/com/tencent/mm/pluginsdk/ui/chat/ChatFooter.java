package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ChatFooter extends FrameLayout {

    public ChatFooter(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ChatFooter(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public String getLastText() {
        throw new RuntimeException("Stub!");
    }

    public void setLastText(String str) {
        throw new RuntimeException("Stub!");
    }

    public long getLastQuoteMsgId() {
        throw new RuntimeException("Stub!");
    }

    public void setMode(int i16) {
        throw new RuntimeException("Stub!");
    }

    /**
     * 软键盘 / 底部面板的标准高度。名字未被混淆 —— Android 默认 proguard 规则保留了
     * View 子类的 {@code get*} / {@code set*}，同一条规则也保住了
     * {@link AppPanel#setPortHeighPx(int)}。
     */
    public int getKeyBordHeightPX() {
        throw new RuntimeException("Stub!");
    }

    public int getYFromBottom() {
        throw new RuntimeException("Stub!");
    }

    public View getV2TBtnLayout() {
        throw new RuntimeException("Stub!");
    }
}
