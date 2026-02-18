package moe.ouom.wekit.loader.hookimpl;

import android.annotation.SuppressLint;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import moe.ouom.wekit.loader.dyn.MemoryDexLoader;
import moe.ouom.wekit.loader.hookapi.IClassLoaderHelper;
import moe.ouom.wekit.utils.io.IoUtils;

public class InMemoryClassLoaderHelper implements IClassLoaderHelper {

    public static final InMemoryClassLoaderHelper INSTANCE = new InMemoryClassLoaderHelper();

    private InMemoryClassLoaderHelper() {
    }

    @NonNull
    @Override
    public ClassLoader createEmptyInMemoryMultiDexClassLoader(@NonNull ClassLoader parent) throws UnsupportedOperationException {
        Objects.requireNonNull(parent, "parent");
        return new BaseDexClassLoader("", null, null, parent);
    }

    private Class<?> kDexPathList;
    private Class<?> kDexPathListElement;
    private Class<?> kDexPathListElementArray;
    private Field dexElementsField;
    private Field pathListField;
    private Constructor<?> elementConstructor1;
    private Constructor<?> elementConstructor4;

    // Warning: You need to bypass the following hidden API restrictions before using this method:
    // Ldalvik/system/DexPathList$Element;-><init>(Ldalvik/system/DexFile;)V,lo-prio,max-target-o

    @SuppressLint("ObsoleteSdkInt")
    private Object createElement(@NonNull DexFile dexFiles) {
        if (Build.VERSION.SDK_INT >= 26) {
            // public Element(DexFile dexFile) since SDK 26+
            try {
                if (elementConstructor1 == null) {
                    elementConstructor1 = kDexPathListElement.getDeclaredConstructor(DexFile.class);
                    elementConstructor1.setAccessible(true);
                }
                return elementConstructor1.newInstance(dexFiles);
            } catch (NoSuchMethodException e) {
                throw new UnsupportedOperationException(e);
            } catch (IllegalAccessException | InvocationTargetException |
                     InstantiationException e) {
                throw IoUtils.unsafeThrowForIteCause(e);
            }
        } else {
            // public Element(File dir, boolean isDirectory, File zip, DexFile dexFile) SDK < 26
            try {
                if (elementConstructor4 == null) {
                    elementConstructor4 = kDexPathListElement.getDeclaredConstructor(File.class, boolean.class, File.class, DexFile.class);
                    elementConstructor4.setAccessible(true);
                }
                return elementConstructor4.newInstance(new File(""), false, null, dexFiles);
            } catch (NoSuchMethodException e) {
                throw new UnsupportedOperationException(e);
            } catch (IllegalAccessException | InvocationTargetException |
                     InstantiationException e) {
                throw IoUtils.unsafeThrowForIteCause(e);
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    @Override
    public void injectDexToClassLoader(@NonNull ClassLoader classLoader, @NonNull byte[] dexBytes, @Nullable String dexName)
            throws IllegalArgumentException, UnsupportedOperationException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(dexBytes, "dexBytes");
        if (!(classLoader instanceof BaseDexClassLoader)) {
            throw new UnsupportedOperationException("classLoader is not an instance of BaseDexClassLoader, got " + classLoader.getClass().getName());
        }
        if (kDexPathList == null) {
            try {
                kDexPathList = Class.forName("dalvik.system.DexPathList");
            } catch (ClassNotFoundException e) {
                throw new UnsupportedOperationException("Failed to find DexPathList class", e);
            }
        }
        if (kDexPathListElement == null) {
            try {
                kDexPathListElement = Class.forName("dalvik.system.DexPathList$Element");
            } catch (ClassNotFoundException e) {
                throw new UnsupportedOperationException("Failed to find DexPathList$Element class", e);
            }
        }
        if (kDexPathListElementArray == null) {
            kDexPathListElementArray = Array.newInstance(kDexPathListElement, 0).getClass();
        }
        if (pathListField == null) {
            try {
                pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
                pathListField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new UnsupportedOperationException("Failed to find pathList field", e);
            }
        }
        if (dexElementsField == null) {
            try {
                dexElementsField = kDexPathList.getDeclaredField("dexElements");
                dexElementsField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new UnsupportedOperationException("Failed to find dexElements field", e);
            }
        }
        // create DexFile
        var dexFile = MemoryDexLoader.createDexFileFormBytes(dexBytes, classLoader, dexName);
        var element = createElement(dexFile);
        try {
            var pathList = pathListField.get(classLoader);
            var oldElements = (Object[]) dexElementsField.get(pathList);
            assert oldElements != null;
            var newElements = (Object[]) Array.newInstance(kDexPathListElement, oldElements.length + 1);
            System.arraycopy(oldElements, 0, newElements, 0, oldElements.length);
            newElements[oldElements.length] = element;
            dexElementsField.set(pathList, newElements);
        } catch (ReflectiveOperationException e) {
            throw IoUtils.unsafeThrowForIteCause(e);
        }
    }
}
