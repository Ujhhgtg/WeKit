package sun.misc;

import java.lang.reflect.Field;

/**
 * Compile-only stub for the public surface shared by Android 9 through Android 16's
 * {@code sun.misc.Unsafe}.
 *
 * <p>The implementation is supplied by Android's boot class path. Keep this class in the
 * compile-only stubs module and do not package it in the APK.</p>
 */
public final class Unsafe {
    public static final int INVALID_FIELD_OFFSET = -1;

    private Unsafe() {}

    /**
     * Only succeeds for callers loaded by the boot class loader. Application code normally has
     * to obtain {@code theUnsafe} reflectively instead.
     */
    public static native Unsafe getUnsafe();

    // Field and array layout.
    public native long objectFieldOffset(Field field);
    public native int arrayBaseOffset(Class<?> clazz);
    public native int arrayIndexScale(Class<?> clazz);

    // Compare-and-swap operations.
    public native boolean compareAndSwapInt(
            Object object, long offset, int expectedValue, int newValue);
    public native boolean compareAndSwapLong(
            Object object, long offset, long expectedValue, long newValue);
    public native boolean compareAndSwapObject(
            Object object, long offset, Object expectedValue, Object newValue);

    // Volatile field and array access.
    public native int getIntVolatile(Object object, long offset);
    public native void putIntVolatile(Object object, long offset, int newValue);
    public native long getLongVolatile(Object object, long offset);
    public native void putLongVolatile(Object object, long offset, long newValue);
    public native Object getObjectVolatile(Object object, long offset);
    public native void putObjectVolatile(Object object, long offset, Object newValue);

    // Plain and ordered field and array access.
    public native int getInt(Object object, long offset);
    public native void putInt(Object object, long offset, int newValue);
    public native void putOrderedInt(Object object, long offset, int newValue);
    public native long getLong(Object object, long offset);
    public native void putLong(Object object, long offset, long newValue);
    public native void putOrderedLong(Object object, long offset, long newValue);
    public native Object getObject(Object object, long offset);
    public native void putObject(Object object, long offset, Object newValue);
    public native void putOrderedObject(Object object, long offset, Object newValue);
    public native boolean getBoolean(Object object, long offset);
    public native void putBoolean(Object object, long offset, boolean newValue);
    public native byte getByte(Object object, long offset);
    public native void putByte(Object object, long offset, byte newValue);
    public native char getChar(Object object, long offset);
    public native void putChar(Object object, long offset, char newValue);
    public native short getShort(Object object, long offset);
    public native void putShort(Object object, long offset, short newValue);
    public native float getFloat(Object object, long offset);
    public native void putFloat(Object object, long offset, float newValue);
    public native double getDouble(Object object, long offset);
    public native void putDouble(Object object, long offset, double newValue);

    // Thread parking.
    public native void park(boolean absolute, long time);
    public native void unpark(Object object);

    // Allocation and native-memory information.
    public native Object allocateInstance(Class<?> clazz);
    public native int addressSize();
    public native int pageSize();
    public native long allocateMemory(long bytes);
    public native void freeMemory(long address);
    public native void setMemory(long address, long bytes, byte value);

    // Absolute native-memory access.
    public native byte getByte(long address);
    public native void putByte(long address, byte value);
    public native short getShort(long address);
    public native void putShort(long address, short value);
    public native char getChar(long address);
    public native void putChar(long address, char value);
    public native int getInt(long address);
    public native void putInt(long address, int value);
    public native long getLong(long address);
    public native void putLong(long address, long value);
    public native float getFloat(long address);
    public native void putFloat(long address, float value);
    public native double getDouble(long address);
    public native void putDouble(long address, double value);

    // Native-memory copies. The array arguments must be primitive arrays.
    public native void copyMemoryToPrimitiveArray(
            long sourceAddress, Object destination, long destinationOffset, long bytes);
    public native void copyMemoryFromPrimitiveArray(
            Object source, long sourceOffset, long destinationAddress, long bytes);
    public native void copyMemory(long sourceAddress, long destinationAddress, long bytes);

    // Atomic read-modify-write operations.
    public final native int getAndAddInt(Object object, long offset, int delta);
    public final native long getAndAddLong(Object object, long offset, long delta);
    public final native int getAndSetInt(Object object, long offset, int newValue);
    public final native long getAndSetLong(Object object, long offset, long newValue);
    public final native Object getAndSetObject(Object object, long offset, Object newValue);

    // Memory fences.
    public native void loadFence();
    public native void storeFence();
    public native void fullFence();
}
