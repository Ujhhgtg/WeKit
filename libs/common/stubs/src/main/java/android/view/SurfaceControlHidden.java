package android.view;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(SurfaceControl.class)
public class SurfaceControlHidden {

    public long mNativeObject;

    @RefineAs(SurfaceControl.Transaction.class)
    public static class TransactionHidden {
        public SurfaceControl.Transaction setSkipScreenshot(SurfaceControl sc, boolean skipScrenshot) {
            throw new RuntimeException("Stub!");
        }
    }
}
