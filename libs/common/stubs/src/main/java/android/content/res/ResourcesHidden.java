package android.content.res;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(Resources.class)
public class ResourcesHidden {

    public ResourcesImpl getImpl() {
        throw new RuntimeException("Stub!");
    }
}
