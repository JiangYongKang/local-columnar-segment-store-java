package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Binary encoding of typed column values inside a segment block. */
final class SegmentFormat {

    static final byte[] MAGIC = {'L', 'C', 'O', 'L'};
    static final int FORMAT_VERSION = 1;

    private SegmentFormat() {
    }

    static void writeValue(DataOutput out, ColumnType type, Object value) throws IOException {
        out.writeByte(value == null ? 0 : 1);
        if (value == null) {
            return;
        }
        switch (type) {
            case INT -> out.writeInt(((Number) value).intValue());
            case LONG -> out.writeLong(((Number) value).longValue());
            case DOUBLE -> out.writeDouble(((Number) value).doubleValue());
            case BOOL -> out.writeByte((Boolean) value ? 1 : 0);
            case STRING -> out.writeUTF((String) value);
        }
    }

    static Object readValue(DataInput in, ColumnType type) throws IOException {
        boolean present = in.readByte() != 0;
        if (!present) {
            return null;
        }
        return switch (type) {
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case DOUBLE -> in.readDouble();
            case BOOL -> in.readByte() != 0;
            case STRING -> in.readUTF();
        };
    }
}
