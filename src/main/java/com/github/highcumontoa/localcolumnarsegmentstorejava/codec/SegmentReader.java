package com.github.highcumontoa.localcolumnarsegmentstorejava.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one immutable segment and projects rows onto a (possibly newer)
 * read schema.
 *
 * <p>Columns that did not exist when the segment was written are filled from
 * the field's declared default (or NULL), which is exactly the rule that keeps
 * historical data readable after additive schema evolution.
 */
public final class SegmentReader {

    private static final ObjectMapper MAPPER = SegmentWriter.MAPPER;

    private final Path segmentDir;
    private final SegmentMeta meta;

    public SegmentReader(Path segmentDir) {
        this.segmentDir = segmentDir;
        try {
            this.meta = MAPPER.readValue(segmentDir.resolve(SegmentFiles.META_JSON).toFile(),
                    SegmentMeta.class);
        } catch (IOException e) {
            throw new StoreException("cannot read segment meta " + segmentDir, e);
        }
    }

    public SegmentMeta meta() {
        return meta;
    }

    /** Whether the segment can hold any partition value in [low, high]. */
    public boolean overlaps(long low, long high) {
        if (meta.minPartition() == null) {
            return true; // unpartitioned / null partition rows
        }
        return meta.maxPartition() >= low && meta.minPartition() <= high;
    }

    /**
     * Read rows in the partition range [low, high] (inclusive) projected onto
     * {@code readSchema}, materializing only {@code selectedColumns} (null =
     * all schema columns).
     */
    public List<Row> readPartitionRange(long low, long high, TableSchema readSchema,
                                        List<String> selectedColumns) {
        if (meta.minPartition() != null && !overlaps(low, high)) {
            return List.of(); // whole-segment partition pruning
        }
        List<String> wanted = effectiveColumns(readSchema, selectedColumns);
        String partCol = readSchema.partitionColumn();
        // The partition column must be materialized internally whenever a
        // partition predicate exists, even if the caller did not project it.
        List<String> toRead = wanted;
        if (partCol != null && !wanted.contains(partCol)) {
            toRead = java.util.List.copyOf(new java.util.ArrayList<>(
                    java.util.stream.Stream.concat(wanted.stream(), java.util.stream.Stream.of(partCol))
                            .distinct().toList()));
        }
        Map<String, List<Object>> data = readColumns(toRead, readSchema);

        List<Object> partVals = partCol == null ? null : data.get(partCol);

        List<Row> rows = new ArrayList<>(meta.rowCount());
        for (int i = 0; i < meta.rowCount(); i++) {
            if (partVals != null) {
                Object pv = partVals.get(i);
                if (pv == null) {
                    continue; // equality/range predicates never match NULL partition
                }
                long p = (Long) pv;
                if (p < low || p > high) {
                    continue; // row-level check for segments spanning the boundary
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String col : wanted) {
                List<Object> colData = data.get(col);
                Object v = colData != null ? colData.get(i) : defaultFor(readSchema, col);
                row.put(col, v);
            }
            rows.add(Row.of(row));
        }
        return rows;
    }

    private Map<String, List<Object>> readColumns(List<String> wanted, TableSchema readSchema) {
        Map<String, List<Object>> data = new LinkedHashMap<>();
        for (String col : wanted) {
            Path colFile = segmentDir.resolve(SegmentFiles.columnFileName(col));
            if (!Files.exists(colFile)) {
                // Column added by a later schema version: not present on disk.
                continue;
            }
            var fd = meta.columnTypes().get(col);
            if (fd == null) {
                throw new StoreException("segment " + segmentDir + " meta has no type for column " + col);
            }
            data.put(col, new ColumnReader(colFile).readAll(fd));
        }
        return data;
    }

    private List<String> effectiveColumns(TableSchema readSchema, List<String> selectedColumns) {
        if (selectedColumns == null) {
            return readSchema.fields().stream().map(FieldDef::name).toList();
        }
        for (String col : selectedColumns) {
            if (readSchema.field(col) == null) {
                throw new StoreException("unknown column: " + col);
            }
        }
        return List.copyOf(selectedColumns);
    }

    private Object defaultFor(TableSchema readSchema, String col) {
        FieldDef fd = readSchema.field(col);
        return fd == null ? null : fd.defaultValue();
    }

    public void close() {
        // all data is read into memory; nothing to release
    }
}
