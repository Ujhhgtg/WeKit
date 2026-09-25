package android.content.res;

import android.content.res.loader.ResourcesLoader;

import androidx.annotation.NonNull;

import java.util.List;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(AssetManager.class)
public class AssetManagerHidden {

    public @NonNull ApkAssets[] getApkAssets() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public List<ResourcesLoader> getLoaders() {
        throw new RuntimeException("Stub!");
    }

    void setLoaders(@NonNull List<ResourcesLoader> newLoaders) {
        throw new RuntimeException("Stub!");
    }

    public int addAssetPath(String path) {
        throw new RuntimeException("Stub!");
    }
}
