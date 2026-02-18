package moe.ouom.wekit.config;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import moe.ouom.wekit.utils.log.WeLogger;


public class MmkvConfigManagerImpl extends WeConfig {

    private final MMKV mmkv;
    private final File file;
    private final String mmkvId;
    // keep the following the same as ConfigManager.cc
    public static final String TYPE_SUFFIX = "$shadow$type";
    private static final String CLASS_SUFFIX = "$shadow$class";
    private static final int TYPE_BOOL = 0x80 + 2;
    private static final int TYPE_INT = 0x80 + 4;
    private static final int TYPE_LONG = 0x80 + 6;
    private static final int TYPE_FLOAT = 0x80 + 7;
    private static final int TYPE_STRING = 0x80 + 31;
    private static final int TYPE_STRING_SET = 0x80 + 32;
    private static final int TYPE_BYTES = 0x80 + 33;
    private static final int TYPE_SERIALIZABLE = 0x80 + 41;
    public static final int TYPE_JSON = 0x80 + 42;

    HashMap<String, Entry<String, Object>> mCacheMap = new HashMap<>();

    class VirtEntry implements Entry<String, Object> {

        VirtEntry(String key) {
            this.key = key;
        }

        final String key;

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return getObject(key);
        }

        @Override
        public Object setValue(Object value) {
            return putObject(key, value);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof VirtEntry) {
                return key.equals(((VirtEntry) o).key);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    final Set<Entry<String, Object>> mVirtEntrySet = new Set<>() {
        @Override
        public int size() {
            return mShadowMap.size();
        }

        @Override
        public boolean isEmpty() {
            return mShadowMap.isEmpty();
        }

        @Override
        public boolean contains(@Nullable Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            var entry = (Entry<String, Object>) o;
            for (var e : this) {
                if (e.equals(entry)) {
                    return true;
                }
            }
            return false;
        }

        @NonNull
        @Override
        public Iterator<Entry<String, Object>> iterator() {
            return new Iterator<>() {
                final Iterator<String> iterator = mShadowMap.keySet().iterator();

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Entry<String, Object> next() {
                    var key = iterator.next();
                    return mCacheMap.computeIfAbsent(key, VirtEntry::new);
                }
            };
        }

        @NonNull
        @Override
        public Object[] toArray() {
            throw new UnsupportedOperationException("entry set");
        }

        @NonNull
        @Override
        public <T> T[] toArray(@NonNull T[] a) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean add(Entry<String, Object> stringObjectEntry) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean remove(@Nullable Object o) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean containsAll(@NonNull Collection<?> c) {
            for (var o : c) {
                if (!contains(o)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(@NonNull Collection<? extends Entry<String, Object>> c) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean retainAll(@NonNull Collection<?> c) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean removeAll(@NonNull Collection<?> c) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("entry set");
        }
    };

    final Collection<Object> mVirtValues = new Collection<>() {
        @Override
        public int size() {
            return mShadowMap.size();
        }

        @Override
        public boolean isEmpty() {
            return mShadowMap.isEmpty();
        }

        @Override
        public boolean contains(@Nullable Object o) {
            for (var val : this) {
                if (Objects.equals(o, val)) {
                    return true;
                }
            }
            return false;
        }

        @NonNull
        @Override
        public Iterator<Object> iterator() {
            return new Iterator<>() {
                final Iterator<String> iterator = mShadowMap.keySet().iterator();

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Object next() {
                    return getObject(iterator.next());
                }
            };
        }

        @NonNull
        @Override
        public Object[] toArray() {
            throw new UnsupportedOperationException("entry set");
        }

        @NonNull
        @Override
        public <T> T[] toArray(@NonNull T[] a) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean add(Object o) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean remove(@Nullable Object o) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean containsAll(@NonNull Collection<?> c) {
            for (var o : c) {
                if (!contains(o)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(@NonNull Collection<?> c) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean removeAll(@NonNull Collection<?> c) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public boolean retainAll(@NonNull Collection<?> c) {
            throw new UnsupportedOperationException("entry set");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("entry set");
        }
    };
    final Map<String, Object> mShadowMap = new Map<>() {
        @Override
        public int size() {
            return entrySet().size();
        }

        @Override
        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public boolean containsKey(@Nullable Object key) {
            Objects.requireNonNull(key);
            return MmkvConfigManagerImpl.this.containsKey((String) key);
        }

        @Override
        public boolean containsValue(@Nullable Object value) {
            for (Object o : entrySet()) {
                if (Objects.equals(o, value)) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        @Override
        public Object get(@Nullable Object key) {
            Objects.requireNonNull(key);
            return getObject((String) key);
        }

        @Nullable
        @Override
        public Object put(String key, Object value) {
            Objects.requireNonNull(key);
            var obj = getObject(key);
            putObject(key, value);
            return obj;
        }

        @Nullable
        @Override
        public Object remove(@Nullable Object key) {
            Objects.requireNonNull(key);
            var obj = getObject((String) key);
            if (obj != null) {
                mmkv.remove((String) key);
            }
            return obj;
        }

        @Override
        public void putAll(@NonNull Map<? extends String, ?> m) {
            for (var entry : m.entrySet()) {
                var key = entry.getKey();
                var value = entry.getValue();
                putObject(key, value);
            }
        }

        @Override
        public void clear() {
            mmkv.clear();
        }

        @NonNull
        @Override
        public Set<String> keySet() {
            Set<String> keys = new HashSet<>();
            var allKeys = mmkv.allKeys();
            if (allKeys == null) {
                return keys;
            }
            for (var s : allKeys) {
                if (!s.endsWith(TYPE_SUFFIX)) {
                    keys.add(s);
                }
            }
            return keys;
        }

        @NonNull
        @Override
        public Collection<Object> values() {
            return mVirtValues;
        }

        @NonNull
        @Override
        public Set<Entry<String, Object>> entrySet() {
            return mVirtEntrySet;
        }
    };

    protected MmkvConfigManagerImpl(@NonNull String name) {
        mmkvId = Objects.requireNonNull(name, "name");
        // 调用前需要等待模块mmkv初始化完毕
        mmkv = MMKV.mmkvWithID(name, MMKV.MULTI_PROCESS_MODE);
        file = new File(MMKV.getRootDir(), name);
    }

    @NonNull
    @Override
    public File getFile() {
        return file;
    }

    @Nullable
    @Override
    public String getString(@NonNull String key) {
        return mmkv.getString(key, null);
    }

    @Nullable
    @Override
    public Object getObject(@NonNull String key) {
        if (!mmkv.contains(key)) {
            return null;
        }
        switch (mmkv.getInt(key.concat(TYPE_SUFFIX), 0)) {
            case TYPE_BOOL:
                return mmkv.getBoolean(key, false);
            case TYPE_FLOAT:
                return mmkv.getFloat(key, 0);
            case TYPE_INT:
                return mmkv.getInt(key, 0);
            case TYPE_LONG:
                return mmkv.getLong(key, 0L);
            case TYPE_STRING:
                return mmkv.getString(key, null);
            case TYPE_STRING_SET:
                return mmkv.getStringSet(key, null);
            case TYPE_BYTES:
                return mmkv.getBytes(key, null);
            case TYPE_SERIALIZABLE: {
                var bytes = mmkv.getBytes(key, null);
                if (bytes == null) {
                    return null;
                }
                var inputStream = new ByteArrayInputStream(bytes);
                try {
                    var objectInputStream = new ObjectInputStream(inputStream);
                    return objectInputStream.readObject();
                } catch (Exception e) {
                    WeLogger.e(e);
                    return null;
                }
            }
            default:
                return null;
        }
    }

    @NonNull
    @Override
    public Map<String, ?> getAll() {
        return mShadowMap;
    }

    @Nullable
    @Override
    public String getString(@NonNull String key, @Nullable String defValue) {
        return mmkv.getString(key, defValue);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(@NonNull String key, @Nullable Set<String> defValues) {
        return mmkv.getStringSet(key, defValues);
    }

    @Override
    public int getInt(@NonNull String key, int defValue) {
        return mmkv.getInt(key, defValue);
    }

    @Override
    public long getLong(@NonNull String key, long defValue) {
        return mmkv.getLong(key, defValue);
    }

    @Override
    public float getFloat(@NonNull String key, float defValue) {
        return mmkv.getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(@NonNull String key, boolean defValue) {
        return mmkv.getBoolean(key, defValue);
    }

    @Override
    public boolean contains(@NonNull String key) {
        return mmkv.contains(key);
    }

    @Override
    public void save() {
        commit();
    }

    @NonNull
    @Override
    public WeConfig putObject(@NonNull String key, @NonNull Object v) {
        if (v == null || key == null) {
            throw new NullPointerException("null key/value not allowed");
        }
        if (v instanceof Float || v instanceof Double) {
            putFloat(key, ((Number) v).floatValue());
        } else if (v instanceof Long) {
            putLong(key, (long) v);
        } else if (v instanceof Integer) {
            putInt(key, (int) v);
        } else if (v instanceof Boolean) {
            putBoolean(key, (boolean) v);
        } else if (v instanceof String) {
            putString(key, (String) v);
        } else if (v instanceof Set) {
            putStringSet(key, (Set<String>) v);
        } else if (v instanceof byte[]) {
            putBytes(key, (byte[]) v);
        } else if (v instanceof String[]) {
            var set = new HashSet<String>(((String[]) v).length);
            Collections.addAll(set, (String[]) v);
            putStringSet(key, set);
        } else if (v instanceof Serializable) {
            try {
                var outputStream = new ByteArrayOutputStream();
                var objectOutputStream = new ObjectOutputStream(outputStream);
                objectOutputStream.writeObject(v);
                mmkv.putBytes(key, outputStream.toByteArray());
                mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_SERIALIZABLE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new IllegalArgumentException("unsupported type" + v.getClass());
        }
        return this;
    }

    @NonNull
    @Override
    public Editor putString(@NonNull String key, @Nullable String value) {
        mmkv.putString(key, value);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_STRING);
        return this;
    }

    @NonNull
    @Override
    public Editor putStringSet(@NonNull String key, @Nullable Set<String> values) {
        mmkv.putStringSet(key, values);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_STRING_SET);
        return this;
    }

    @NonNull
    @Override
    public Editor putInt(@NonNull String key, int value) {
        mmkv.putInt(key, value);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_INT);
        return this;
    }

    @NonNull
    @Override
    public Editor putLong(@NonNull String key, long value) {
        mmkv.putLong(key, value);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_LONG);
        return this;
    }

    @NonNull
    @Override
    public Editor putFloat(@NonNull String key, float value) {
        mmkv.putFloat(key, value);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_FLOAT);
        return this;
    }

    @NonNull
    @Override
    public Editor putBoolean(@NonNull String key, boolean value) {
        mmkv.putBoolean(key, value);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_BOOL);
        return this;
    }

    @NonNull
    @Override
    public byte[] getBytesOrDefault(@NonNull String key, @NonNull byte[] defValue) {
        return mmkv.getBytes(key, defValue);
    }

    @Nullable
    @Override
    public byte[] getBytes(@NonNull String key, @Nullable byte[] defValue) {
        return mmkv.getBytes(key, defValue);
    }

    @NonNull
    @Override
    public WeConfig putBytes(@NonNull String key, @NonNull byte[] value) {
        mmkv.putBytes(key, value);
        mmkv.putInt(key.concat(TYPE_SUFFIX), TYPE_BYTES);
        return this;
    }

    @NonNull
    @Override
    public Editor remove(@NonNull String key) {
        mmkv.remove(key);
        mmkv.remove(key.concat(TYPE_SUFFIX));
        return this;
    }

    @NonNull
    @Override
    public Editor clear() {
        mmkv.clear();
        return this;
    }

    @Override
    public boolean commit() {
        return true;
    }

    @Override
    public void apply() {
        // do nothing
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @NonNull
    public String getMmkvId() {
        return mmkvId;
    }

    /**
     * Get the internal mmkv instance.
     * <p>
     * THIS IS USUALLY NOT WHAT YOU WANT.
     *
     * @return the underlying mmkv instance
     * @see MMKV
     */
    @NonNull
    public MMKV getInternalMmkv() {
        return mmkv;
    }
}
