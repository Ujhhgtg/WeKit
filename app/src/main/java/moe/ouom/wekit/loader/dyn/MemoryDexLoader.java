package moe.ouom.wekit.loader.dyn;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Objects;

import dalvik.system.DexFile;
import moe.ouom.wekit.utils.io.IoUtils;

public class MemoryDexLoader {

    /**
     * Create a DexFile instance from a byte array. Applications generally should not create a DexFile directly.
     * <p>
     * It will hurt performance in most cases and will lead to incorrect execution of bytecode in the worst case.
     *
     * @param dexBytes        dex file data
     * @param definingContext the class loader where the dex file will be attached to
     * @param name            optional name for the dex file, may be null
     * @return a DexFile instance
     */
    @NonNull
    public static DexFile createDexFileFormBytes(@NonNull byte[] dexBytes, @NonNull ClassLoader definingContext, @Nullable String name) {
        Objects.requireNonNull(dexBytes, "dexBytes is null");
        Objects.requireNonNull(definingContext, "definingContext is null");
        if (dexBytes.length < 20) {
            throw new IllegalArgumentException("dexBytes is too short");
        }
        return createDexFileFormBytesAboveOreo(dexBytes, definingContext, name);
    }

    @NonNull
    private static DexFile createDexFileFormBytesAboveOreo(@NonNull byte[] dexBytes, @NonNull ClassLoader definingContext, @Nullable String name) {
        // Android 8.0 - 10:  DexFile(ByteBuffer buf) throws IOException;
        // Android 10+: DexFile(ByteBuffer[] bufs, ClassLoader loader, DexPathList.Element[] elements);
        Constructor<DexFile> constructor1 = null;
        Constructor<DexFile> constructor3 = null;
        Class<?> kElementArray;
        try {
            kElementArray = Class.forName("[Ldalvik.system.DexPathList$Element;");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class.forName(\"[Ldalvik.system.DexPathList$Element;\"); fail", e);
        }
        try {
            constructor1 = DexFile.class.getDeclaredConstructor(ByteBuffer.class);
            constructor1.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            constructor3 = DexFile.class.getDeclaredConstructor(ByteBuffer[].class, ClassLoader.class, kElementArray);
            constructor3.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        var byteBuffer = ByteBuffer.wrap(dexBytes);
        if (constructor3 != null) {
            var byteBuffers = new ByteBuffer[]{byteBuffer};
            try {
                return constructor3.newInstance(byteBuffers, definingContext, null);
            } catch (ReflectiveOperationException e) {
                throw IoUtils.unsafeThrowForIteCause(e);
            }
        } else if (constructor1 != null) {
            try {
                return constructor1.newInstance(byteBuffer);
            } catch (ReflectiveOperationException e) {
                throw IoUtils.unsafeThrowForIteCause(e);
            }
        } else {
            throw new IllegalStateException("DexFile constructor not found, SDK_INT=" + Build.VERSION.SDK_INT);
        }
    }
}
