package com.github.highcumontoa.localcolumnarsegmentstorejava.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one new immutable segment directory.
 *
 * <p>The segment is assembled entirely inside {@code seg-&lt;id&gt;.tmp/}. The
 * final {@link #commit()} atomically renames it to {@code seg-&lt;id&gt;/};
 * until that rename happens the segment does not exist for any manifest, so a
 * crash mid-write leaves only an orphan temp directory (cleaned on recovery).
 */
public final class SegmentWriter {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path tableDir;
    private final long segmentId;
    private final int schemaVersion;
    private final TableSchema schema;
    private final List<String> columns;
    private final Map<String, List<Object>> columnValues = new LinkedHashMap<>();

    private SegmentWriter(Path tableDir, long segmentId, TableSchema schema, List<String> columns) {
        this.tableDir = tableDir;
        this.segmentId = segmentId;
        this.schema = schema;
        this.schemaVersion = schema.version();
        this.columns = List.copyOf(columns);
        for (String col : this.columns) {
            columnValues.put(col, new ArrayList<>());
        }
    }

    /**
     * Begin a new segment.
     *
     * @param columns columns to materialize (must be schema columns); rows
     *                appended afterwards may omit columns added in later schema
     *                versions - they are filled from defaults/NULL.
     */
    public static SegmentWriter begin(Path tableDir, long segmentId,
                                      TableSchema schema, List<String> columns) {
        SegmentWriter w = new SegmentWriter(tableDir, segmentId, schema, columns);
        try {
            Files.createDirectories(w.tmpDir());
        } catch (IOException e) {
            throw new StoreException("cannot create temp segment dir " + w.tmpDir(), e);
        }
        return w;
    }

    /** Append an already-validated row. Missing columns get default/NULL. */
    public void append(Row row) {
        Map<String, Object> vals = row.values();
        for (String col : columns) {
            Object v = vals.get(col);
            if (v == null) {
                var fd = schema.field(col);
                v = fd == null ? null : fd.defaultValue();
            }
            columnValues.get(col).add(v);
        }
    }

    /** Write column files and meta.json, then atomically publish the directory. */
    public SegmentMeta commit() {
        Long min = null;
        Long max = null;
        String partCol = schema.partitionColumn();
        if (partCol != null && columns.contains(partCol)) {
            for (Object v : columnValues.get(partCol)) {
                if (v != null) {
                    long lv = (Long) v;
                    if (min == null || lv < min) {
                        min = lv;
                    }
                    if (max == null || lv > max) {
                        max = lv;
                    }
                }
            }
        }
        int rowCount = columns.isEmpty() ? 0 : columnValues.get(columns.get(0)).size();
        Map<String, DataType> types = new LinkedHashMap<>();
        for (String col : columns) {
            types.put(col, schema.field(col).type());
        }
        try {
            for (String col : columns) {
                Path colFile = tmpDir().resolve(SegmentFiles.columnFileName(col));
                new ColumnWriter(colFile, schema.field(col).type(), columnValues.get(col)).write();
            }
            SegmentMeta meta = new SegmentMeta(segmentId, schemaVersion,
                    List.copyOf(columns), Map.copyOf(types), rowCount, min, max);
            Path metaTmp = tmpDir().resolve(SegmentFiles.META_JSON + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(metaTmp.toFile(), meta);
            Files.move(metaTmp, tmpDir().resolve(SegmentFiles.META_JSON),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            Path finalDir = finalDir();
            Files.move(tmpDir(), finalDir, StandardCopyOption.ATOMIC_MOVE);
            return meta;
        } catch (IOException e) {
            throw new StoreException("failed to commit segment " + segmentId, e);
        }
    }

    public Path tmpDir() {
        return tableDir.resolve(SegmentFiles.segmentsDir())
                .resolve(SegmentFiles.tmpSegmentDirName(segmentId));
    }

    public Path finalDir() {
        return tableDir.resolve(SegmentFiles.segmentsDir())
                .resolve(SegmentFiles.segmentDirName(segmentId));
    }

    public int rowCount() {
        return columns.isEmpty() ? 0 : columnValues.get(columns.get(0)).size();
    }
}
