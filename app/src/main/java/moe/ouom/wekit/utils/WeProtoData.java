package moe.ouom.wekit.utils;

import static cn.hutool.core.convert.Convert.hexToBytes;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import moe.ouom.wekit.utils.log.WeLogger;

public class WeProtoData {

    private static final class Field {
        final int fieldNumber;
        final int wireType;
        Object value;

        Field(int fieldNumber, int wireType, Object value) {
            this.fieldNumber = fieldNumber;
            this.wireType = wireType;
            this.value = value;
        }
    }

    private enum LenView {
        AUTO, SUB, UTF8, HEX
    }

    private static final class LenValue {
        byte[] raw;
        String utf8;
        WeProtoData subMessage;
        LenView view;

        LenValue(byte[] raw) {
            this.raw = raw != null ? raw : new byte[0];
            this.view = LenView.AUTO;
        }
    }

    private final List<Field> fields = new ArrayList<>();
    private byte[] packetPrefix = new byte[0];

    public static boolean hasPacketPrefix(byte[] b) {
        return b != null && b.length >= 4 && (b[0] & 0xFF) == 0;
    }

    public static byte[] getUnpPackage(byte[] b) {
        if (b == null) return null;
        if (b.length < 4) return b;
        if ((b[0] & 0xFF) == 0) return Arrays.copyOfRange(b, 4, b.length);
        return b;
    }

    public void clear() {
        fields.clear();
        packetPrefix = new byte[0];
    }

    public byte[] getPacketPrefix() {
        return packetPrefix != null ? Arrays.copyOf(packetPrefix, packetPrefix.length) : new byte[0];
    }

    public void setPacketPrefix(byte[] prefix) {
        this.packetPrefix = prefix != null ? Arrays.copyOf(prefix, prefix.length) : new byte[0];
    }

    public void fromBytes(byte[] b) throws IOException {
        clear();
        if (b == null) return;

        var body = b;
        if (hasPacketPrefix(b)) {
            packetPrefix = Arrays.copyOfRange(b, 0, 4);
            body = Arrays.copyOfRange(b, 4, b.length);
        }
        parseMessageBytes(body, true);
    }

    public void fromMessageBytes(byte[] b) throws IOException {
        clear();
        packetPrefix = new byte[0];
        parseMessageBytes(b, true);
    }

    private void parseMessageBytes(byte[] b, boolean analyzeLen) throws IOException {
        if (b == null) return;

        var in = CodedInputStream.newInstance(b);
        while (!in.isAtEnd()) {
            final int tag;
            try {
                tag = in.readTag();
            } catch (InvalidProtocolBufferException e) {
                throw new InvalidProtocolBufferException(e);
            }

            if (tag == 0) break;

            var fieldNumber = tag >>> 3;
            var wireType = tag & 7;

            if (wireType == 4 || wireType == 3 || wireType > 5) {
                throw new IOException("Unexpected wireType: " + wireType);
            }

            switch (wireType) {
                case 0: {
                    var v = in.readInt64();
                    fields.add(new Field(fieldNumber, wireType, v));
                    break;
                }
                case 1: {
                    var v = in.readFixed64();
                    fields.add(new Field(fieldNumber, wireType, v));
                    break;
                }
                case 2: {
                    var subBytes = in.readByteArray();
                    var lv = new LenValue(subBytes);
                    if (analyzeLen) analyzeLenValue(lv);
                    fields.add(new Field(fieldNumber, wireType, lv));
                    break;
                }
                case 5: {
                    var v = in.readFixed32();
                    fields.add(new Field(fieldNumber, wireType, v));
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void analyzeLenValue(LenValue lv) {
        if (lv == null) return;

        var sub = tryParseSubMessageStrong(lv.raw);
        if (sub != null) {
            lv.subMessage = sub;
            lv.utf8 = null;
            lv.view = LenView.SUB;
            return;
        }

        var s = tryDecodeUtf8Roundtrip(lv.raw);
        if (s != null) {
            lv.utf8 = s;
            lv.subMessage = null;
            lv.view = LenView.UTF8;
            return;
        }

        lv.utf8 = null;
        lv.subMessage = null;
        lv.view = LenView.HEX;
    }

    private static String tryDecodeUtf8Roundtrip(byte[] b) {
        try {
            var s = new String(b, StandardCharsets.UTF_8);
            var re = s.getBytes(StandardCharsets.UTF_8);
            if (Arrays.equals(b, re)) return s;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static WeProtoData tryParseSubMessageStrong(byte[] b) {
        try {
            if (b == null || b.length == 0) return null;
            var sub = new WeProtoData();
            sub.parseMessageBytes(b, true);
            if (sub.fields.isEmpty()) return null;
            var re = sub.toMessageBytes();
            if (!Arrays.equals(b, re)) return null;
            return sub;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static WeProtoData ensureSubParsedStrong(LenValue lv) {
        if (lv == null) return null;
        if (lv.subMessage != null) return lv.subMessage;
        var sub = tryParseSubMessageStrong(lv.raw);
        if (sub != null) lv.subMessage = sub;
        return lv.subMessage;
    }

    private static String ensureUtf8Decoded(LenValue lv) {
        if (lv == null) return null;
        if (lv.utf8 != null) return lv.utf8;
        var s = tryDecodeUtf8Roundtrip(lv.raw);
        if (s != null) lv.utf8 = s;
        return lv.utf8;
    }

    public JSONObject toJSON() throws Exception {
        var obj = new JSONObject();
        for (var f : fields) {
            var k = String.valueOf(f.fieldNumber);
            var jsonVal = fieldValueToJsonValue(f);

            if (!obj.has(k)) {
                obj.put(k, jsonVal);
            } else {
                var existing = obj.get(k);
                JSONArray arr;
                if (existing instanceof JSONArray) {
                    arr = (JSONArray) existing;
                } else {
                    arr = new JSONArray();
                    arr.put(existing);
                    obj.put(k, arr);
                }
                arr.put(jsonVal);
            }
        }
        return obj;
    }

    private Object fieldValueToJsonValue(Field f) throws Exception {
        if (f.wireType != 2) return f.value;

        var lv = (LenValue) f.value;

        var v = lv.view;
        if (v == LenView.AUTO) {
            var sub = ensureSubParsedStrong(lv);
            if (sub != null) {
                lv.view = LenView.SUB;
                return sub.toJSON();
            }
            var s = ensureUtf8Decoded(lv);
            if (s != null) {
                lv.view = LenView.UTF8;
                return s;
            }
            lv.view = LenView.HEX;
            return "hex->" + bytesToHex(lv.raw);
        }

        if (v == LenView.SUB) {
            var sub = ensureSubParsedStrong(lv);
            if (sub != null) return sub.toJSON();
            var s = ensureUtf8Decoded(lv);
            if (s != null) return s;
            return "hex->" + bytesToHex(lv.raw);
        }

        if (v == LenView.UTF8) {
            var s = ensureUtf8Decoded(lv);
            if (s != null) return s;
            var sub = ensureSubParsedStrong(lv);
            if (sub != null) return sub.toJSON();
            return "hex->" + bytesToHex(lv.raw);
        }

        return "hex->" + bytesToHex(lv.raw);
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    public byte[] toBytes() {
        return toMessageBytes();
    }

    public byte[] toMessageBytes() {
        var bos = new ByteArrayOutputStream();
        var out = CodedOutputStream.newInstance(bos);
        try {
            for (var f : fields) {
                switch (f.wireType) {
                    case 0: {
                        long v = (Long) f.value;
                        if (v >= 0) out.writeUInt64(f.fieldNumber, v);
                        else out.writeInt64(f.fieldNumber, v);
                        break;
                    }
                    case 1: {
                        long v = (Long) f.value;
                        out.writeFixed64(f.fieldNumber, v);
                        break;
                    }
                    case 2: {
                        var lv = (LenValue) f.value;
                        if (lv.subMessage != null) {
                            var newRaw = lv.subMessage.toMessageBytes();
                            if (!Arrays.equals(newRaw, lv.raw)) lv.raw = newRaw;
                        } else if (lv.utf8 != null && lv.view == LenView.UTF8) {
                            var newRaw = lv.utf8.getBytes(StandardCharsets.UTF_8);
                            if (!Arrays.equals(newRaw, lv.raw)) lv.raw = newRaw;
                        }
                        out.writeByteArray(f.fieldNumber, lv.raw != null ? lv.raw : new byte[0]);
                        break;
                    }
                    case 5: {
                        int v = (Integer) f.value;
                        out.writeFixed32(f.fieldNumber, v);
                        break;
                    }
                    default:
                        break;
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            WeLogger.e("WeProtoData - toBytes", e);
            return new byte[0];
        }
    }

    public byte[] toPacketBytes() {
        var body = toMessageBytes();
        if (packetPrefix == null || packetPrefix.length == 0) return body;
        var out = new byte[packetPrefix.length + body.length];
        System.arraycopy(packetPrefix, 0, out, 0, packetPrefix.length);
        System.arraycopy(body, 0, out, packetPrefix.length, body.length);
        return out;
    }

    private int findFieldIndex(int fieldNumber, int occurrenceIndex) {
        var occ = 0;
        for (var i = 0; i < fields.size(); i++) {
            var f = fields.get(i);
            if (f.fieldNumber == fieldNumber) {
                if (occ == occurrenceIndex) return i;
                occ++;
            }
        }
        return -1;
    }

    public boolean setVarint(int fieldNumber, int occurrenceIndex, long value) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.get(idx).value = value;
        return true;
    }

    public boolean setFixed64(int fieldNumber, int occurrenceIndex, long value) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.get(idx).value = value;
        return true;
    }

    public boolean setFixed32(int fieldNumber, int occurrenceIndex, int value) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.get(idx).value = value;
        return true;
    }

    public boolean setLenHex(int fieldNumber, int occurrenceIndex, String hex) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        var f = fields.get(idx);
        if (f.wireType != 2) return false;
        var lv = (LenValue) f.value;

        var h = stripNonHex(hex);
        lv.raw = h.isEmpty() ? new byte[0] : hexToBytes(h);
        lv.utf8 = null;
        lv.subMessage = null;
        lv.view = LenView.HEX;
        return true;
    }

    public boolean setLenUtf8(int fieldNumber, int occurrenceIndex, String text) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        var f = fields.get(idx);
        if (f.wireType != 2) return false;
        var lv = (LenValue) f.value;

        if (text == null) text = "";
        lv.utf8 = text;
        lv.raw = text.getBytes(StandardCharsets.UTF_8);
        lv.subMessage = null;
        lv.view = LenView.UTF8;
        return true;
    }

    public boolean setLenSubBytes(int fieldNumber, int occurrenceIndex, byte[] subBytes) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        var f = fields.get(idx);
        if (f.wireType != 2) return false;
        var lv = (LenValue) f.value;

        var sub = tryParseSubMessageStrong(subBytes);
        lv.raw = subBytes != null ? subBytes : new byte[0];
        lv.subMessage = sub;
        lv.utf8 = null;
        lv.view = sub != null ? LenView.SUB : LenView.HEX;
        return true;
    }

    public boolean removeField(int fieldNumber, int occurrenceIndex) {
        var idx = findFieldIndex(fieldNumber, occurrenceIndex);
        if (idx < 0) return false;
        fields.remove(idx);
        return true;
    }

    public int replaceUtf8Contains(String needle, String replacement) {
        if (needle == null || needle.isEmpty()) return 0;
        if (replacement == null) replacement = "";
        return replaceUtf8ContainsInternal(needle, replacement);
    }

    private int replaceUtf8ContainsInternal(String needle, String replacement) {
        var changed = 0;
        for (var f : fields) {
            if (f.wireType != 2) continue;
            var lv = (LenValue) f.value;

            var sub = ensureSubParsedStrong(lv);
            if (sub != null) {
                var subChanged = sub.replaceUtf8ContainsInternal(needle, replacement);
                if (subChanged > 0) {
                    lv.subMessage = sub;
                    lv.raw = sub.toMessageBytes();
                    lv.utf8 = null;
                    lv.view = LenView.SUB;
                    changed += subChanged;
                }
            }

            var s = ensureUtf8Decoded(lv);
            if (s != null && s.contains(needle)) {
                var ns = s.replace(needle, replacement);
                if (!ns.equals(s)) {
                    lv.utf8 = ns;
                    lv.raw = ns.getBytes(StandardCharsets.UTF_8);
                    lv.subMessage = null;
                    lv.view = LenView.UTF8;
                    changed++;
                }
            }
        }
        return changed;
    }

    public int replaceUtf8Regex(Pattern pattern, String replacement) {
        if (pattern == null) return 0;
        if (replacement == null) replacement = "";
        return replaceUtf8RegexInternal(pattern, replacement);
    }

    private int replaceUtf8RegexInternal(Pattern pattern, String replacement) {
        var matchesTotal = 0;
        for (var f : fields) {
            if (f.wireType != 2) continue;
            var lv = (LenValue) f.value;

            var sub = ensureSubParsedStrong(lv);
            if (sub != null) {
                var subMatches = sub.replaceUtf8RegexInternal(pattern, replacement);
                if (subMatches > 0) {
                    lv.subMessage = sub;
                    lv.raw = sub.toMessageBytes();
                    lv.utf8 = null;
                    lv.view = LenView.SUB;
                    matchesTotal += subMatches;
                }
            }

            var s = ensureUtf8Decoded(lv);
            if (s != null) {
                var m = pattern.matcher(s);
                var cnt = 0;
                while (m.find()) cnt++;
                if (cnt > 0) {
                    var ns = pattern.matcher(s).replaceAll(replacement);
                    lv.utf8 = ns;
                    lv.raw = ns.getBytes(StandardCharsets.UTF_8);
                    lv.subMessage = null;
                    lv.view = LenView.UTF8;
                    matchesTotal += cnt;
                }
            }
        }
        return matchesTotal;
    }

    private static String stripNonHex(String s) {
        if (s == null) return "";
        var out = new StringBuilder(s.length());
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if ((c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') ||
                    (c >= 'A' && c <= 'F')) out.append(c);
        }
        return out.toString();
    }

    public void fromJSON(JSONObject json) {
        try {
            clear();
            var keyIt = json.keys();
            while (keyIt.hasNext()) {
                var key = keyIt.next();
                var fieldNumber = Integer.parseInt(key);
                var value = json.get(key);

                if (value instanceof JSONObject) {
                    var sub = new WeProtoData();
                    sub.fromJSON((JSONObject) value);
                    var lv = new LenValue(sub.toMessageBytes());
                    lv.subMessage = sub;
                    lv.view = LenView.SUB;
                    fields.add(new Field(fieldNumber, 2, lv));
                } else if (value instanceof JSONArray arr) {
                    for (var i = 0; i < arr.length(); i++) {
                        var v = arr.get(i);
                        addJsonValueAsField(fieldNumber, v);
                    }
                } else {
                    addJsonValueAsField(fieldNumber, value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void addJsonValueAsField(int fieldNumber, Object value) {
        try {
            if (value instanceof JSONObject) {
                var sub = new WeProtoData();
                sub.fromJSON((JSONObject) value);
                var lv = new LenValue(sub.toMessageBytes());
                lv.subMessage = sub;
                lv.view = LenView.SUB;
                fields.add(new Field(fieldNumber, 2, lv));
            } else if (value instanceof Number) {
                var v = ((Number) value).longValue();
                fields.add(new Field(fieldNumber, 0, v));
            } else if (value instanceof String s) {
                if (s.startsWith("hex->")) {
                    var raw = hexToBytes(stripNonHex(s.substring(5)));
                    var lv = new LenValue(raw);
                    lv.view = LenView.HEX;
                    fields.add(new Field(fieldNumber, 2, lv));
                } else {
                    var raw = s.getBytes(StandardCharsets.UTF_8);
                    var lv = new LenValue(raw);
                    lv.utf8 = s;
                    lv.view = LenView.UTF8;
                    fields.add(new Field(fieldNumber, 2, lv));
                }
            } else if (value == null) {
            } else {
                WeLogger.w("WeProtoData.fromJSON Unknown type: " + value.getClass().getName());
            }
        } catch (Exception ignored) {
        }
    }

    public int applyViewJSON(JSONObject view, boolean deleteMissing) {
        if (view == null) return 0;

        var changes = 0;

        if (deleteMissing) {
            List<Integer> existingNums = new ArrayList<>();
            for (var f : fields) existingNums.add(f.fieldNumber);

            for (var i = 0; i < existingNums.size(); i++) {
                int fn = existingNums.get(i);
                if (!view.has(String.valueOf(fn))) {
                    changes += removeAllOccurrences(fn);
                }
            }
        }

        var it = view.keys();
        while (it.hasNext()) {
            var key = it.next();
            int fn;
            try {
                fn = Integer.parseInt(key);
            } catch (Exception e) {
                continue;
            }

            var val = view.opt(key);
            if (val == null || val == JSONObject.NULL) {
                if (deleteMissing) changes += removeAllOccurrences(fn);
                continue;
            }

            if (val instanceof JSONArray arr) {
                var idxs = indicesOf(fn);

                var min = Math.min(arr.length(), idxs.size());
                for (var i = 0; i < min; i++) {
                    var v = arr.opt(i);
                    if (v == JSONObject.NULL) continue;
                    changes += applyOne(fields.get(idxs.get(i)), v, deleteMissing);
                }

                if (deleteMissing && idxs.size() > arr.length()) {
                    for (var i = idxs.size() - 1; i >= arr.length(); i--) {
                        fields.remove((int) idxs.get(i));
                        changes++;
                    }
                }
            } else {
                var idx = findFieldIndex(fn, 0);
                if (idx >= 0) {
                    changes += applyOne(fields.get(idx), val, deleteMissing);
                }
            }
        }

        return changes;
    }

    private int applyOne(Field f, Object val, boolean deleteMissing) {
        if (f == null || val == null || val == JSONObject.NULL) return 0;

        try {
            switch (f.wireType) {
                case 0, 1: {
                    if (val instanceof Number) {
                        f.value = ((Number) val).longValue();
                        return 1;
                    }
                    if (val instanceof String) {
                        f.value = Long.parseLong((String) val);
                        return 1;
                    }
                    return 0;
                }
                case 5: {
                    if (val instanceof Number) {
                        f.value = ((Number) val).intValue();
                        return 1;
                    }
                    if (val instanceof String) {
                        f.value = Integer.parseInt((String) val);
                        return 1;
                    }
                    return 0;
                }
                case 2: {
                    var lv = (LenValue) f.value;

                    if (val instanceof JSONObject) {
                        var sub = ensureSubParsedStrong(lv);
                        if (sub == null) {
                            sub = new WeProtoData();
                        }
                        var c = sub.applyViewJSON((JSONObject) val, deleteMissing);
                        lv.subMessage = sub;
                        lv.raw = sub.toMessageBytes();
                        lv.utf8 = null;
                        lv.view = LenView.SUB;
                        return Math.max(1, c);
                    }

                    if (val instanceof String s) {
                        if (s.startsWith("hex->")) {
                            var raw = hexToBytes(stripNonHex(s.substring(5)));
                            lv.raw = raw != null ? raw : new byte[0];
                            lv.utf8 = null;
                            lv.subMessage = null;
                            lv.view = LenView.HEX;
                        } else {
                            lv.utf8 = s;
                            lv.raw = s.getBytes(StandardCharsets.UTF_8);
                            lv.subMessage = null;
                            lv.view = LenView.UTF8;
                        }
                        return 1;
                    }

                    return 0;
                }
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Integer> indicesOf(int fieldNumber) {
        List<Integer> idxs = new ArrayList<>();
        for (var i = 0; i < fields.size(); i++) {
            if (fields.get(i).fieldNumber == fieldNumber) idxs.add(i);
        }
        return idxs;
    }

    private int removeAllOccurrences(int fieldNumber) {
        var removed = 0;
        for (var i = fields.size() - 1; i >= 0; i--) {
            if (fields.get(i).fieldNumber == fieldNumber) {
                fields.remove(i);
                removed++;
            }
        }
        return removed;
    }

}
