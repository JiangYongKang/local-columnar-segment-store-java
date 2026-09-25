package com.github.highcumontoa.localcolumnarsegmentstorejava.codec;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Writes one column of one segment in the custom binary columnar format.
 *
 * <p>File layout (little-endian values):
 * <pre>
 *   magic   : 4 bytes  "LCSC"
 *   type    : 1 byte   (0=LONG, 1=DOUBLE, 2=STRING, 3=BOOLEAN)
 *   rowCount: 4 bytes  int
 *   nullBits: (rowCount + 7) / 8 bytes, bit i = 1 when row i is NULL
 *   values  : non-null values laid out contiguously
 *             LONG   = 8 bytes each
 *             DOUBLE = 8 bytes each
 *             BOOLEAN= 1 byte each
 *             STRING = 4-byte length + UTF-8 bytes each
 * </pre>
 * All files are written to {@code *.tmp} and atomically moved to their final
 * name by the enclosing {@link SegmentWriter}, so a reader can never observe a
 * half-written column of a published segment.
 */
final class ColumnWriter implements AutoCloseable {

    static final int MAGIC = 0x4C435343; // "LCSC"

    private final Path file;
    private final DataType type;
    private final List<Object> values;

    ColumnWriter(Path file, DataType type, List<Object> values) {
        this.file = file;
        this.type = type;
        this.values = values;
    }

    void write() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             BufferedOutputStream out = new BufferedOutputStream(raw)) {
            writeHeader(out);
            switch (type) {
                case LONG -> writeLongs(out);
                case DOUBLE -> writeDoubles(out);
                case BOOLEAN -> writeBooleans(out);
                case STRING -> writeStrings(out);
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeHeader(OutputStream out) throws IOException {
        writeInt(out, MAGIC);
        out.write(type.ordinal());
        writeInt(out, values.size());
        byte[] bits = new byte[(values.size() + 7) / 8];
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                bits[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        out.write(bits);
    }

    private void writeLongs(OutputStream out) throws IOException {
        for (Object v : values) {
            if (v != null) {
                writeLong(out, (Long) v);
            }
        }
    }

    private void writeDoubles(OutputStream out) throws IOException {
        for (Object v : values) {
            if (v != null) {
                writeLong(out, Double.doubleToLongBits((Double) v));
            }
        }
    }

    private void writeBooleans(OutputStream out) throws IOException {
        for (Object v : values) {
            if (v != null) {
                out.write(((Boolean) v) ? 1 : 0);
            }
        }
    }

    private void writeStrings(OutputStream out) throws IOException {
        for (Object v : values) {
            if (v != null) {
                byte[] utf8 = ((String) v).getBytes(StandardCharsets.UTF_8);
                writeInt(out, utf8.length);
                out.write(utf8);
            }
        }
    }

    static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    static void writeLong(OutputStream out, long v) throws IOException {
        for (int shift = 0; shift < 64; shift += 8) {
            out.write((int) ((v >>> shift) & 0xFF));
        }
    }

    @Override
    public void close() {
        // stateless with respect to open handles: everything is closed in write()
    }

}
