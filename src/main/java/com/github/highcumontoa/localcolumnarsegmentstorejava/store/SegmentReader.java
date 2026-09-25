package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an immutable columnar segment. Columns present in the current schema
 * but absent from an old segment are filled with the schema default, keeping
 * historical data readable after compatible schema evolution.
 */
public final class SegmentReader {

    public List<Map<String, Object>> read(Path dir, SegmentMeta meta, Schema currentSchema) throws IOException {
        Path file = dir.resolve(meta.fileName());
        try (InputStream fis = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(fis))) {
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4 || magic[0] != 'L' || magic[1] != 'C' || magic[2] != 'O' || magic[3] != 'L') {
                throw new StoreException("bad segment magic in " + file);
            }
            int formatVersion = in.readInt();
            if (formatVersion != SegmentFormat.FORMAT_VERSION) {
                throw new StoreException("unsupported segment format version " + formatVersion + " in " + file);
            }
            in.readLong(); // schema version at write time (informational)
            in.readUTF();  // partition (already known from meta)
            int rowCount = in.readInt();
            int columnCount = in.readInt();
            List<ColumnDef> fileColumns = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                String name = in.readUTF();
                ColumnType type = ColumnType.values()[in.readInt()];
                fileColumns.add(new ColumnDef(name, type));
            }
            // decode column blocks
            Map<String, List<Object>> decoded = new LinkedHashMap<>();
            for (ColumnDef col : fileColumns) {
                List<Object> values = new ArrayList<>(rowCount);
                for (int r = 0; r < rowCount; r++) {
                    values.add(SegmentFormat.readValue(in, col.type()));
                }
                decoded.put(col.name(), values);
            }
            // rebuild rows in current schema order, filling defaults for missing columns
            List<Map<String, Object>> rows = new ArrayList<>(rowCount);
            for (int r = 0; r < rowCount; r++) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (ColumnDef col : currentSchema.columns()) {
                    List<Object> values = decoded.get(col.name());
                    if (values != null) {
                        row.put(col.name(), values.get(r));
                    } else if (currentSchema.defaults().containsKey(col.name())) {
                        row.put(col.name(), currentSchema.defaults().get(col.name()));
                    } else {
                        row.put(col.name(), null);
                    }
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
