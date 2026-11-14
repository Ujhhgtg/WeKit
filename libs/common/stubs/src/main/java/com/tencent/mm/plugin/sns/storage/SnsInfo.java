package com.tencent.mm.plugin.sns.storage;

public class SnsInfo {
    public long field_snsId;
    /** Local row id; -1 for the placeholder rows the album list inserts as headers. */
    public int localid;
    public int field_createTime;
    public byte[] field_content;
    /** Post type: 1 image, 2 text, 3 link, 4 music, 15 short video, 54 live-photo album. */
    public int field_type;

    public int getLikeFlag() {
        throw new RuntimeException("Stub!");
    }

    public String getSnsId() {
        throw new RuntimeException("Stub!");
    }

    public String getUserName() {
        throw new RuntimeException("Stub!");
    }

    public boolean isAd() {
        throw new RuntimeException("Stub!");
    }

    public boolean isDieItem() {
        throw new RuntimeException("Stub!");
    }

    public boolean isWaitPost() {
        throw new RuntimeException("Stub!");
    }

    public boolean isDeadSource() {
        throw new RuntimeException("Stub!");
    }

    public boolean isValid() {
        throw new RuntimeException("Stub!");
    }
}
