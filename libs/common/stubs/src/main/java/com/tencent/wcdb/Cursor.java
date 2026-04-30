package com.tencent.wcdb;

import android.content.ContentResolver;
import android.database.CharArrayBuffer;
import android.database.ContentObserver;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;

/* JADX INFO: loaded from: classes11.dex */
@SuppressWarnings({"unused", "UnnecessaryModifier"})
public interface Cursor extends android.database.Cursor {

    public static final int FIELD_TYPE_BLOB = 4;
    public static final int FIELD_TYPE_FLOAT = 2;
    public static final int FIELD_TYPE_INTEGER = 1;
    public static final int FIELD_TYPE_NULL = 0;
    public static final int FIELD_TYPE_STRING = 3;

    @Override // android.database.Cursor, java.io.Closeable, java.lang.AutoCloseable
    void close();

    @Override // android.database.Cursor
    void copyStringToBuffer(int i16, CharArrayBuffer charArrayBuffer);

    @Override // android.database.Cursor
    void deactivate();

    @Override // android.database.Cursor
    byte[] getBlob(int i16);

    @Override // android.database.Cursor
    int getColumnCount();

    @Override // android.database.Cursor
    int getColumnIndex(String str);

    @Override // android.database.Cursor
    int getColumnIndexOrThrow(String str);

    @Override // android.database.Cursor
    String getColumnName(int i16);

    @Override // android.database.Cursor
    String[] getColumnNames();

    @Override // android.database.Cursor
    int getCount();

    @Override // android.database.Cursor
    double getDouble(int i16);

    @Override // android.database.Cursor
    Bundle getExtras();

    @Override // android.database.Cursor
    float getFloat(int i16);

    @Override // android.database.Cursor
    int getInt(int i16);

    @Override // android.database.Cursor
    long getLong(int i16);

    @Override // android.database.Cursor
    int getPosition();

    @Override // android.database.Cursor
    short getShort(int i16);

    @Override // android.database.Cursor
    String getString(int i16);

    @Override // android.database.Cursor
    int getType(int i16);

    @Override // android.database.Cursor
    boolean getWantsAllOnMoveCalls();

    @Override // android.database.Cursor
    boolean isAfterLast();

    @Override // android.database.Cursor
    boolean isBeforeFirst();

    @Override // android.database.Cursor
    boolean isClosed();

    @Override // android.database.Cursor
    boolean isFirst();

    @Override // android.database.Cursor
    boolean isLast();

    @Override // android.database.Cursor
    boolean isNull(int i16);

    @Override // android.database.Cursor
    boolean move(int i16);

    @Override // android.database.Cursor
    boolean moveToFirst();

    @Override // android.database.Cursor
    boolean moveToLast();

    @Override // android.database.Cursor
    boolean moveToNext();

    @Override // android.database.Cursor
    boolean moveToPosition(int i16);

    @Override // android.database.Cursor
    boolean moveToPrevious();

    @Override // android.database.Cursor
    void registerContentObserver(ContentObserver contentObserver);

    @Override // android.database.Cursor
    void registerDataSetObserver(DataSetObserver dataSetObserver);

    @Override // android.database.Cursor
    @Deprecated
    boolean requery();

    @Override // android.database.Cursor
    Bundle respond(Bundle bundle);

    @Override // android.database.Cursor
    void setNotificationUri(ContentResolver contentResolver, Uri uri);

    @Override // android.database.Cursor
    void unregisterContentObserver(ContentObserver contentObserver);

    @Override // android.database.Cursor
    void unregisterDataSetObserver(DataSetObserver dataSetObserver);
}
