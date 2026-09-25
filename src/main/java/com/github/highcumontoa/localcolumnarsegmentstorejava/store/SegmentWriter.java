package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an immutable columnar segment file.
 * Layout: header (magic, format version, schema version, partition, row count, column defs)
 * followed by one value block per column (column-major order).
 * The file is first written to a tmp name and atomically renamed, so readers
 * never observe a partially written segment.
 */
public final class SegmentWriter {

    public SegmentMeta write(Path dir, Schema schema, String partition, List<Map<String, Object>> rows)
            throws IOException {
        String segmentId = UUID.randomUUID().toString();
        SegmentMeta meta = new SegmentMeta(segmentId, partition, rows.size(), schema.version());
        Path tmp = dir.resolve(meta.fileName() + ".tmp");
        Path target = dir.resolve(meta.fileName());
        try (OutputStream fos = Files.newOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
            out.write(SegmentFormat.MAGIC);
            out.writeInt(SegmentFormat.FORMAT_VERSION);
            out.writeLong(schema.version());
            out.writeUTF(partition);
            out.writeInt(rows.size());
            out.writeInt(schema.columns().size());
            for (ColumnDef col : schema.columns()) {
                out.writeUTF(col.name());
                out.writeInt(col.type().ordinal());
            }
            // column-major: one block per column
            for (ColumnDef col : schema.columns()) {
                for (Map<String, Object> row : rows) {
                    SegmentFormat.writeValue(out, col.type(), row.get(col.name()));
                }
            }
            out.flush();
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return meta;
    }
}
