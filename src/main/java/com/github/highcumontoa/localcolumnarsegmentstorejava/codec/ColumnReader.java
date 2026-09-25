package com.github.highcumontoa.localcolumnarsegmentstorejava.codec;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads one column file fully into memory and verifies its header. */
final class ColumnReader {

    private final Path file;

    ColumnReader(Path file) {
        this.file = file;
    }

    List<Object> readAll(DataType expectedType) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new StoreException("failed to read column file " + file, e);
        }
        Cursor c = new Cursor(bytes);
        int magic = c.readInt();
        if (magic != ColumnWriter.MAGIC) {
            throw new StoreException("corrupt column file " + file + ": bad magic");
        }
        DataType type = DataType.values()[c.readByte()];
        if (type != expectedType) {
            throw new StoreException("corrupt column file " + file + ": stored type " + type
                    + " != expected " + expectedType);
        }
        int rowCount = c.readInt();
        byte[] nullBits = c.readBytes((rowCount + 7) / 8);
        List<Object> values = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            boolean isNull = (nullBits[i >> 3] & (1 << (i & 7))) != 0;
            if (isNull) {
                values.add(null);
            } else {
                values.add(switch (type) {
                    case LONG -> c.readLong();
                    case DOUBLE -> Double.longBitsToDouble(c.readLong());
                    case BOOLEAN -> c.readByte() != 0;
                    case STRING -> c.readString();
                });
            }
        }
        return values;
    }

    /** Simple little-endian cursor over a byte array. */
    private static final class Cursor {
        private final byte[] bytes;
        private int pos;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int readByte() {
            return bytes[pos++] & 0xFF;
        }

        int readInt() {
            int v = (readByte()) | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
            return v;
        }

        long readLong() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 8) {
                v |= ((long) readByte()) << shift;
            }
            return v;
        }

        byte[] readBytes(int n) {
            byte[] out = new byte[n];
            System.arraycopy(bytes, pos, out, 0, n);
            pos += n;
            return out;
        }

        String readString() {
            int len = readInt();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(len);
            for (int i = 0; i < len; i++) {
                bos.write(readByte());
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
}
