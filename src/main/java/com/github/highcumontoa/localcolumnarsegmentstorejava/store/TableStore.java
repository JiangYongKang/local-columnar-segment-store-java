package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Main entry point for one table. All reads see only committed snapshots;
 * writes produce immutable segments and a new snapshot version. Commit races
 * are retried with re-validation; if the race is lost repeatedly a
 * {@link ConcurrentCommitException} is surfaced instead of silently overwriting.
 */
public final class TableStore implements AutoCloseable {

    private static final int MAX_COMMIT_ATTEMPTS = 50;

    private final Path tableDir;
    private final ManifestStore manifestStore;
    private final SegmentWriter segmentWriter = new SegmentWriter();
    private final SegmentReader segmentReader = new SegmentReader();
    private final SchemaEvolver schemaEvolver = new SchemaEvolver();

    public TableStore(Path tableDir) throws IOException {
        this.tableDir = tableDir;
        Files.createDirectories(tableDir);
        this.manifestStore = new ManifestStore(tableDir);
        this.manifestStore.recover();
    }

    /** Create a new table with the given schema; fails if the table already exists. */
    public void create(Schema schema) throws IOException {
        validateSchema(schema);
        if (manifestStore.loadCurrent().isPresent()) {
            throw new StoreException("table already exists at " + tableDir);
        }
        manifestStore.commit(new Snapshot(1, schema, List.of()), -1);
    }

    public WriteResult write(String partition, List<Map<String, Object>> records) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Snapshot snapshot;
            List<RejectedRecord> rejected = new ArrayList<>();
            List<Map<String, Object>> accepted;
            synchronized (manifestStore.lock()) {
                snapshot = requireSnapshot();
                accepted = validateBatch(snapshot, partition, records, rejected);
            }
            if (accepted.isEmpty()) {
                return new WriteResult(snapshot.version(), 0, rejected);
            }
            SegmentMeta meta = segmentWriter.write(tableDir, snapshot.schema(), partition, accepted);
            List<SegmentMeta> segments = new ArrayList<>(snapshot.segments());
            segments.add(meta);
            try {
                manifestStore.commit(new Snapshot(snapshot.version() + 1, snapshot.schema(), segments),
                        snapshot.version());
                return new WriteResult(snapshot.version() + 1, accepted.size(), rejected);
            } catch (ConcurrentCommitException e) {
                Files.deleteIfExists(tableDir.resolve(meta.fileName()));
                if (attempt >= MAX_COMMIT_ATTEMPTS - 1) {
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    public List<Map<String, Object>> query(String partition) throws IOException {
        synchronized (manifestStore.lock()) {
            return queryLocked(requireSnapshot(), partition);
        }
    }

    public List<Map<String, Object>> query(String partition, long snapshotVersion) throws IOException {
        synchronized (manifestStore.lock()) {
            Snapshot snapshot = manifestStore.loadSnapshot(snapshotVersion)
                    .orElseThrow(() -> new StoreException("snapshot version not found: " + snapshotVersion));
            return queryLocked(snapshot, partition);
        }
    }

    private List<Map<String, Object>> queryLocked(Snapshot snapshot, String partition) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SegmentMeta meta : snapshot.segments()) {
            if (meta.partition().equals(partition)) {
                rows.addAll(segmentReader.read(tableDir, meta, snapshot.schema()));
            }
        }
        return rows;
    }

    public Schema schema() {
        try {
            return requireSnapshot().schema();
        } catch (IOException e) {
            throw new StoreException("failed to load schema", e);
        }
    }

    public Snapshot currentSnapshot() {
        try {
            return requireSnapshot();
        } catch (IOException e) {
            throw new StoreException("failed to load snapshot", e);
        }
    }

    public Schema evolveAddColumn(ColumnDef def, Object defaultValue) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Snapshot snapshot = requireSnapshot();
            Schema evolved = schemaEvolver.addColumn(snapshot.schema(), def, defaultValue);
            try {
                manifestStore.commit(new Snapshot(snapshot.version() + 1, evolved, snapshot.segments()),
                        snapshot.version());
                return evolved;
            } catch (ConcurrentCommitException e) {
                if (attempt >= MAX_COMMIT_ATTEMPTS - 1) {
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    public SchemaEvolver schemaEvolver() {
        return schemaEvolver;
    }

    /**
     * Compact one partition: merge all its segments into one, preserving the exact
     * row set, then commit a new snapshot and delete the superseded segment files.
     * A commit race with a concurrent writer is retried; if it keeps losing, the
     * {@link ConcurrentCommitException} is surfaced to the caller.
     */
    public long compact(String partition) throws IOException {
        for (int attempt = 0; ; attempt++) {
            synchronized (manifestStore.lock()) {
                Snapshot snapshot = requireSnapshot();
                List<SegmentMeta> ofPartition = snapshot.segments().stream()
                        .filter(m -> m.partition().equals(partition)).toList();
                if (ofPartition.size() <= 1) {
                    return snapshot.version();
                }
                List<Map<String, Object>> merged = new ArrayList<>();
                for (SegmentMeta meta : ofPartition) {
                    merged.addAll(segmentReader.read(tableDir, meta, snapshot.schema()));
                }
                SegmentMeta mergedMeta = segmentWriter.write(tableDir, snapshot.schema(), partition, merged);
                List<SegmentMeta> segments = new ArrayList<>();
                for (SegmentMeta meta : snapshot.segments()) {
                    if (!meta.partition().equals(partition)) {
                        segments.add(meta);
                    }
                }
                segments.add(mergedMeta);
                try {
                    manifestStore.commit(new Snapshot(snapshot.version() + 1, snapshot.schema(), segments),
                            snapshot.version());
                    manifestStore.deleteSegments(ofPartition);
                    return snapshot.version() + 1;
                } catch (ConcurrentCommitException e) {
                    Files.deleteIfExists(tableDir.resolve(mergedMeta.fileName()));
                    if (attempt >= MAX_COMMIT_ATTEMPTS - 1) {
                        throw e;
                    }
                }
            }
            backoff(attempt);
        }
    }

    public Optional<Snapshot> loadSnapshot(long version) throws IOException {
        return manifestStore.loadSnapshot(version);
    }

    Path tableDir() {
        return tableDir;
    }

    @Override
    public void close() {
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(1L << attempt, 20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Snapshot requireSnapshot() throws IOException {
        return manifestStore.loadCurrent()
                .orElseThrow(() -> new StoreException("table not created at " + tableDir));
    }

    private List<Map<String, Object>> validateBatch(Snapshot snapshot, String partition,
                                                    List<Map<String, Object>> records,
                                                    List<RejectedRecord> rejected) throws IOException {
        Schema schema = snapshot.schema();
        Set<Object> existingKeys = loadKeys(snapshot, partition);
        Set<Object> batchKeys = new HashSet<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Map<String, Object> record : records) {
            RejectedRecord rejection = validateRecord(schema, partition, record, existingKeys, batchKeys);
            if (rejection != null) {
                rejected.add(rejection);
            } else {
                Map<String, Object> normalized = normalize(schema, record);
                batchKeys.add(normalized.get(schema.keyColumn()));
                accepted.add(normalized);
            }
        }
        return accepted;
    }

    private RejectedRecord validateRecord(Schema schema, String partition, Map<String, Object> record,
                                          Set<Object> existingKeys, Set<Object> batchKeys) {
        for (ColumnDef col : schema.columns()) {
            if (!record.containsKey(col.name()) && !schema.defaults().containsKey(col.name())) {
                return new RejectedRecord(record, RejectReason.MISSING_FIELD,
                        "missing field '" + col.name() + "' and no default defined");
            }
            Object value = record.get(col.name());
            if (value != null && !SchemaEvolver.typeMatches(col.type(), value)) {
                return new RejectedRecord(record, RejectReason.TYPE_MISMATCH,
                        "field '" + col.name() + "' expects " + col.type() + " but got "
                                + value.getClass().getSimpleName());
            }
            if (col.type() == ColumnType.INT && value instanceof Number n
                    && !(value instanceof Integer) && n.longValue() != n.intValue()) {
                return new RejectedRecord(record, RejectReason.TYPE_MISMATCH,
                        "field '" + col.name() + "' value " + value + " overflows INT");
            }
        }
        Object partitionValue = record.get(schema.partitionColumn());
        if (partitionValue == null || !String.valueOf(partitionValue).equals(partition)) {
            return new RejectedRecord(record, RejectReason.PARTITION_VIOLATION,
                    "partition column '" + schema.partitionColumn() + "' value " + partitionValue
                            + " does not match target partition '" + partition + "'");
        }
        Object key = record.get(schema.keyColumn());
        if (existingKeys.contains(key)) {
            return new RejectedRecord(record, RejectReason.KEY_CONFLICT,
                    "key " + key + " already exists in partition '" + partition + "'");
        }
        if (!batchKeys.add(key)) {
            return new RejectedRecord(record, RejectReason.KEY_CONFLICT,
                    "duplicate key " + key + " within the same batch");
        }
        return null;
    }

    private Map<String, Object> normalize(Schema schema, Map<String, Object> record) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnDef col : schema.columns()) {
            Object value = record.containsKey(col.name())
                    ? record.get(col.name())
                    : schema.defaults().get(col.name());
            row.put(col.name(), value);
        }
        return row;
    }

    private Set<Object> loadKeys(Snapshot snapshot, String partition) throws IOException {
        synchronized (manifestStore.lock()) {
            Set<Object> keys = new HashSet<>();
            String keyColumn = snapshot.schema().keyColumn();
            for (SegmentMeta meta : snapshot.segments()) {
                if (meta.partition().equals(partition)) {
                    for (Map<String, Object> row : segmentReader.read(tableDir, meta, snapshot.schema())) {
                        keys.add(row.get(keyColumn));
                    }
                }
            }
            return keys;
        }
    }

    private static void validateSchema(Schema schema) {
        if (schema.column(schema.keyColumn()) == null) {
            throw new StoreException("key column '" + schema.keyColumn() + "' not in schema");
        }
        if (schema.column(schema.partitionColumn()) == null) {
            throw new StoreException("partition column '" + schema.partitionColumn() + "' not in schema");
        }
    }
}
