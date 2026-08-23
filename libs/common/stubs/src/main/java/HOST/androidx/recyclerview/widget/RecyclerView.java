package HOST.androidx.recyclerview.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/**
 * 宿主微信进程里的 androidx RecyclerView 桩。
 * <p>
 * 类名带 {@code HOST.} 前缀: 运行时由 {@code HybridClassLoader} 把
 * {@code HOST.androidx.recyclerview.widget.RecyclerView} 路由到微信自带的 androidx 类,
 * 避免与本模块打包的 androidx 版本产生 Class 不一致, 从而可以用类型安全的方式调用,
 * 不再需要类名字符串和反射兜底。
 */
public class RecyclerView extends ViewGroup {

    public RecyclerView(Context context) {
        super(context);
    }

    public RecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    }

    // 注意: 不要在这里声明返回宿主类型 (如 getAdapter() 返回 RecyclerView.Adapter) 的方法。
    // 桩类型和宿主类型是两个 Class, 方法签名的返回类型描述符不一致, 运行时 ART 按完整签名
    // (含返回类型) 解析, 会直接抛 NoSuchMethodError。只用参数/返回值都是原语或框架类型的方法。
    public int computeVerticalScrollRange() {
        throw new RuntimeException("Stub!");
    }

    public int computeVerticalScrollOffset() {
        throw new RuntimeException("Stub!");
    }

    public int computeVerticalScrollExtent() {
        throw new RuntimeException("Stub!");
    }
}
