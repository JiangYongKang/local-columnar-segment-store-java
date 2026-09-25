package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentFiles;
import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentMeta;
import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentReader;
import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentWriter;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.IncompatibleSchemaException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectedRecord;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.SnapshotInfo;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Values;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.WriteResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One table: append-only immutable segments, atomic snapshot publication,
 * serial write/compact operations and crash recovery on open.
 *
 * <h3>Snapshot semantics</h3>
 * Readers resolve the visible snapshot through {@code meta/CURRENT} (an
 * atomically renamed pointer) and then read only immutable segment files, so a
 * query sees either the old complete snapshot or the new complete snapshot.
 *
 * <h3>Concurrency</h3>
 * Writes, schema changes and compactions hold a single exclusive commit lock;
 * queries hold the corresponding read lock for the duration of their scan.
 * Therefore file deletion after compaction never races an in-flight reader,
 * and two concurrent batches are serialized: a key committed by the batch that
 * wins the lock is reported as {@link RejectReason#DUPLICATE_KEY} to the loser
 * instead of silently overwriting it.
 */
public final class TableStore {

    private static final Logger LOG = Logger.getLogger(TableStore.class.getName());
    static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path tableDir;
    private final Path segmentsDir;
    private final int compactThreshold;
    private final int manifestHistoryKeep;
    private final SchemaStore schemaStore;
    private final ManifestStore manifestStore;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Always accessed/updated while the appropriate lock is held. */
    private volatile TableSchema currentSchema;
    private volatile Manifest currentManifest;
    private final Set<String> committedKeys = new HashSet<>();
    private long nextSegmentId;

    public TableStore(Path tableDir) {
        this(tableDir, 4, 3);
    }

    public TableStore(Path tableDir, int compactThreshold, int manifestHistoryKeep) {
        this.tableDir = tableDir;
        this.segmentsDir = tableDir.resolve(SegmentFiles.segmentsDir());
        this.compactThreshold = compactThreshold;
        this.manifestHistoryKeep = manifestHistoryKeep;
        this.schemaStore = new SchemaStore(tableDir, MAPPER);
        this.manifestStore = new ManifestStore(tableDir, MAPPER);
        recover();
    }

    // ------------------------------------------------------------------ open / recovery

    /**
     * Recover a unique, consistent snapshot after an abrupt stop:
     * <ol>
     *   <li>load the newest schema version;</li>
     *   <li>resolve CURRENT when present and validate it points to a complete
     *       manifest whose segment directories all exist; otherwise promote the
     *       highest complete durable manifest and repoint CURRENT;</li>
     *   <li>delete {@code seg-*.tmp} directories and finalized segments that no
     *       manifest in the recovered lineage references (orphans of a
     *       write/compaction that died before the pointer switch);</li>
     *   <li>rebuild the in-memory key index and the segment id allocator.</li>
     * </ol>
     */
    private void recover() {
        lock.writeLock().lock();
        try {
            List<Integer> schemaVersions = schemaStore.availableVersions();
            if (schemaVersions.isEmpty()) {
                throw new StoreException("table directory " + tableDir + " has no schema files");
            }
            currentSchema = schemaStore.load(schemaVersions.get(schemaVersions.size() - 1));

            Manifest recovered = resolveRecoveredManifest();
            deleteTempSegments();
            if (recovered != null) {
                deleteOrphanSegments(recovered);
            }
            currentManifest = recovered;

            nextSegmentId = scanMaxSegmentId() + 1;
            committedKeys.clear();
            if (recovered != null) {
                for (String key : readAllKeys(recovered)) {
                    committedKeys.add(key);
                }
            }
            LOG.info(() -> "recovered table " + tableDir.getFileName()
                    + " snapshot=v" + (recovered == null ? 0 : recovered.version())
                    + " schema=v" + currentSchema.version()
                    + " segments=" + (recovered == null ? 0 : recovered.segments().size())
                    + " rows=" + (recovered == null ? 0 : recovered.rowCount())
                    + " keys=" + committedKeys.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Manifest resolveRecoveredManifest() {
        // The unique consistent snapshot is the highest version whose manifest
        // is durable AND whose segments all exist. If CURRENT already points at
        // it we are done; if a later fully-durable transaction exists (files
        // fsynced, but the pointer switch did not happen before the crash), it
        // must be committed, not rolled back. Anything beyond is discarded.
        List<Long> versions = manifestStore.listManifestVersions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            Manifest candidate = manifestStore.load(versions.get(i));
            if (isComplete(candidate)) {
                Manifest pointed = manifestStore.current();
                if (pointed == null || pointed.version() != candidate.version()) {
                    LOG.warning(() -> "recovering " + tableDir.getFileName()
                            + ": promoting unique consistent snapshot v" + candidate.version()
                            + (pointed == null ? " (CURRENT missing)"
                                    : " (CURRENT lagged at v" + pointed.version() + ")"));
                    repointCurrent(candidate);
                }
                return candidate;
            }
        }
        return null;
    }

    private boolean isComplete(Manifest m) {
        for (SegmentMeta seg : m.segments()) {
            if (!Files.isDirectory(segmentsDir.resolve(SegmentFiles.segmentDirName(seg.segmentId())))) {
                return false;
            }
        }
        return true;
    }

    private void repointCurrent(Manifest m) {
        Path current = tableDir.resolve(SegmentFiles.metaDir()).resolve(SegmentFiles.CURRENT);
        Path tmp = current.resolveSibling(current.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, SegmentFiles.manifestFileName(m.version()));
            Files.move(tmp, current, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StoreException("recovery: cannot repoint CURRENT", e);
        }
    }

    private void deleteTempSegments() {
        if (!Files.isDirectory(segmentsDir)) {
            return;
        }
        try (var paths = Files.list(segmentsDir)) {
            paths.filter(p -> Files.isDirectory(p)
                            && p.getFileName().toString().endsWith(".tmp"))
                    .forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new StoreException("recovery: cannot list temp segments", e);
        }
    }

    private void deleteOrphanSegments(Manifest m) {
        Set<String> live = new HashSet<>();
        for (SegmentMeta seg : m.segments()) {
            live.add(SegmentFiles.segmentDirName(seg.segmentId()));
        }
        if (!Files.isDirectory(segmentsDir)) {
            return;
        }
        try (var paths = Files.list(segmentsDir)) {
            paths.filter(p -> Files.isDirectory(p)
                            && p.getFileName().toString().startsWith("seg-")
                            && !p.getFileName().toString().endsWith(".tmp")
                            && !live.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        LOG.warning(() -> "deleting orphan segment " + p + " (not referenced by snapshot)");
                        deleteQuietly(p);
                    });
        } catch (IOException e) {
            throw new StoreException("recovery: cannot list segments", e);
        }
    }

    private void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; a leftover tmp dir is removed again on next open
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private long scanMaxSegmentId() {
        long max = 0L;
        if (!Files.isDirectory(segmentsDir)) {
            return max;
        }
        try (var paths = Files.list(segmentsDir)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith("seg-") && !n.endsWith(".tmp")) {
                    try {
                        max = Math.max(max, Long.parseLong(n.substring(4)));
                    } catch (NumberFormatException ignored) {
                        // unrelated directory
                    }
                }
            }
        } catch (IOException e) {
            throw new StoreException("recovery: cannot scan segments", e);
        }
        return max;
    }

    private List<String> readAllKeys(Manifest m) {
        List<String> keys = new ArrayList<>();
        List<String> keyCols = currentSchema.keyFields().stream().map(FieldDef::name).toList();
        for (SegmentMeta seg : m.segments()) {
            SegmentReader reader = new SegmentReader(
                    segmentsDir.resolve(SegmentFiles.segmentDirName(seg.segmentId())));
            for (Row row : reader.readPartitionRange(Long.MIN_VALUE, Long.MAX_VALUE,
                    currentSchema, keyCols)) {
                keys.add(Values.compositeKey(currentSchema, row.values()));
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ public accessors

    public TableSchema currentSchema() {
        return currentSchema;
    }

    public SnapshotInfo snapshotInfo() {
        Manifest m = currentManifest;
        return new SnapshotInfo(currentSchema.tableName(), m.version(),
                m.schemaVersion(), m.segments().size(), m.rowCount());
    }

    public void setCrashHooks(CrashHooks hooks) {
        manifestStore.setCrashHooks(hooks);
    }

    // ------------------------------------------------------------------ write

    public WriteResult write(List<Row> rows) {
        lock.writeLock().lock();
        try {
            TableSchema schema = currentSchema;
            Manifest base = currentManifest;
            long baseVersion = base == null ? 0L : base.version();

            List<RejectedRecord> rejected = new ArrayList<>();
            List<Row> accepted = new ArrayList<>();
            Set<String> batchKeys = new HashSet<>();

            for (int i = 0; i < rows.size(); i++) {
                Row raw = rows.get(i);
                ValidationResult vr = validate(raw, schema, batchKeys);
                if (vr.rejection != null) {
                    rejected.add(new RejectedRecord(i, new LinkedHashMap<>(raw.values()),
                            vr.rejection.v1(), vr.rejection.v2()));
                } else {
                    accepted.add(vr.coerced);
                    batchKeys.add(Values.compositeKey(schema, vr.coerced.values()));
                }
            }

            LOG.fine(() -> "write attempt table=" + schema.tableName()
                    + " baseSnapshot=v" + baseVersion + " submitted=" + rows.size()
                    + " accepted=" + accepted.size() + " rejected=" + rejected.size());

            if (accepted.isEmpty()) {
                return WriteResult.nothing(baseVersion, rejected);
            }

            long segId = nextSegmentId++;
            List<String> allColumns = schema.fields().stream().map(FieldDef::name).toList();
            SegmentWriter writer = SegmentWriter.begin(tableDir, segId, schema, allColumns);
            for (Row row : accepted) {
                writer.append(row);
            }
            SegmentMeta meta = writer.commit();

            List<SegmentMeta> segments = new ArrayList<>(
                    base == null ? List.of() : base.segments());
            segments.add(meta);
            long newRowCount = (base == null ? 0L : base.rowCount()) + accepted.size();
            Manifest next = new Manifest(baseVersion + 1, schema.version(),
                    List.copyOf(segments), newRowCount, "WRITE", System.currentTimeMillis());
            manifestStore.publish(next);
            currentManifest = next;
            committedKeys.addAll(batchKeys);
            manifestStore.pruneHistory(manifestHistoryKeep);

            LOG.fine(() -> "write committed snapshot=v" + next.version()
                    + " segment=seg-" + segId + " rows=" + newRowCount);
            return WriteResult.committed(next.version(), accepted.size(), rejected);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private record Rejection(RejectReason v1, String v2) {
    }

    private record ValidationResult(Row coerced, Rejection rejection) {
    }

    /** Validate one record; returns coerced row or the first violation found. */
    private ValidationResult validate(Row raw, TableSchema schema, Set<String> batchKeys) {
        Map<String, Object> in = raw.values();
        Map<String, Object> coerced = new LinkedHashMap<>();

        for (FieldDef fd : schema.fields()) {
            Object value = in.get(fd.name());

            if (!in.containsKey(fd.name())) {
                if (fd.defaultValue() != null) {
                    coerced.put(fd.name(), fd.defaultValue());
                    continue;
                }
                if (fd.nullable()) {
                    coerced.put(fd.name(), null);
                    continue;
                }
                return new ValidationResult(null, new Rejection(RejectReason.MISSING_FIELD,
                        "field '" + fd.name() + "' is absent and has no default and is not nullable"));
            }

            if (value == null) {
                if (!fd.nullable() && fd.defaultValue() == null) {
                    return new ValidationResult(null, new Rejection(RejectReason.MISSING_FIELD,
                            "field '" + fd.name() + "' is null but declared NOT NULL without default"));
                }
                coerced.put(fd.name(), fd.defaultValue());
                continue;
            }

            if (!Values.isAssignable(fd.type(), value)) {
                return new ValidationResult(null, new Rejection(RejectReason.TYPE_MISMATCH,
                        "field '" + fd.name() + "' declared " + fd.type()
                                + " but received " + value.getClass().getSimpleName()
                                + "(" + value + ")"));
            }
            coerced.put(fd.name(), Values.coerce(fd.type(), value));
        }

        // Unknown columns are rejected rather than silently discarded.
        for (String supplied : in.keySet()) {
            if (schema.field(supplied) == null) {
                return new ValidationResult(null, new Rejection(RejectReason.TYPE_MISMATCH,
                        "unknown column '" + supplied + "' is not defined in schema v"
                                + schema.version()));
            }
        }

        // Partition constraints.
        FieldDef part = schema.partitionField();
        if (part != null) {
            Object pv = coerced.get(part.name());
            if (pv == null) {
                return new ValidationResult(null, new Rejection(RejectReason.PARTITION_VIOLATION,
                        "partition column '" + part.name() + "' must not be null"));
            }
            long p = (Long) pv;
            if (schema.lowerBound() != null && p < schema.lowerBound()) {
                return new ValidationResult(null, new Rejection(RejectReason.PARTITION_VIOLATION,
                        "partition value " + p + " < lowerBound " + schema.lowerBound()));
            }
            if (schema.upperBound() != null && p > schema.upperBound()) {
                return new ValidationResult(null, new Rejection(RejectReason.PARTITION_VIOLATION,
                        "partition value " + p + " > upperBound " + schema.upperBound()));
            }
        }

        // Key conflict: same batch first, then already committed snapshots.
        String key = Values.compositeKey(schema, coerced);
        if (batchKeys.contains(key)) {
            return new ValidationResult(null, new Rejection(RejectReason.DUPLICATE_KEY,
                    "composite key " + key + " already used earlier in the same batch"));
        }
        if (committedKeys.contains(key)) {
            return new ValidationResult(null, new Rejection(RejectReason.DUPLICATE_KEY,
                    "composite key " + key + " already exists in committed snapshot v"
                            + currentManifest.version()));
        }

        return new ValidationResult(Row.of(coerced), null);
    }

    // ------------------------------------------------------------------ query

    /**
     * Query the visible snapshot.
     *
     * @param partitionLow  inclusive lower partition bound (nullable)
     * @param partitionHigh inclusive upper partition bound (nullable)
     * @param partitionEq   exact partition value (nullable; wins over range)
     * @param columns       projected columns (nullable = all)
     * @param schemaVersion optional fixed schema version for reproducible
     *                      historical projection (nullable = current schema)
     */
    public List<Row> query(Long partitionLow, Long partitionHigh,
                           Long partitionEq, List<String> columns, Integer schemaVersion) {
        lock.readLock().lock();
        try {
            Manifest m = currentManifest;
            if (m == null) {
                return List.of();
            }
            TableSchema readSchema = schemaVersion == null
                    ? currentSchema : schemaStore.load(schemaVersion);

            long low;
            long high;
            if (partitionEq != null) {
                low = high = partitionEq;
            } else {
                low = partitionLow == null ? Long.MIN_VALUE : partitionLow;
                high = partitionHigh == null ? Long.MAX_VALUE : partitionHigh;
            }
            if (low > high) {
                throw new StoreException("invalid partition range: low " + low + " > high " + high);
            }
            if (readSchema.partitionColumn() == null
                    && (partitionEq != null || partitionLow != null || partitionHigh != null)) {
                throw new StoreException("table has no partition column");
            }

            List<Row> out = new ArrayList<>((int) Math.min(Integer.MAX_VALUE, m.rowCount()));
            for (SegmentMeta seg : m.segments()) {
                SegmentReader reader = new SegmentReader(
                        segmentsDir.resolve(SegmentFiles.segmentDirName(seg.segmentId())));
                out.addAll(reader.readPartitionRange(low, high, readSchema, columns));
            }
            LOG.fine(() -> "query snapshot=v" + m.version() + " schema=v" + readSchema.version()
                    + " range=[" + low + "," + high + "] segments=" + m.segments().size()
                    + " returnedRows=" + out.size());
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------ schema evolution

    public Manifest addColumn(String name, DataType type, boolean nullable, Object defaultValue) {
        lock.writeLock().lock();
        try {
            TableSchema next = schemaStore.addColumn(currentSchema, name, type, nullable, defaultValue);
            return publishSchemaChange(next);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Manifest dropColumn(String name) {
        lock.writeLock().lock();
        try {
            // always throws by design; kept behind the lock for a stable error point
            schemaStore.dropColumn(currentSchema, name);
            return currentManifest;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Manifest renameColumn(String oldName, String newName) {
        lock.writeLock().lock();
        try {
            schemaStore.renameColumn(currentSchema, oldName, newName);
            return currentManifest;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Manifest changeColumnType(String name, DataType newType) {
        lock.writeLock().lock();
        try {
            schemaStore.changeColumnType(currentSchema, name, newType);
            return currentManifest;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Manifest publishSchemaChange(TableSchema next) {
        Manifest base = currentManifest;
        Manifest published = new Manifest(base.version() + 1, next.version(),
                base.segments(), base.rowCount(), "SCHEMA", System.currentTimeMillis());
        manifestStore.publish(published);
        currentManifest = published;
        currentSchema = next;
        manifestStore.pruneHistory(manifestHistoryKeep);
        return published;
    }

    // ------------------------------------------------------------------ compaction

    /**
     * Rewrite all visible segments into a single new segment.
     *
     * <p>The logical row multiset is byte-for-byte unchanged: rows are streamed
     * in segment-id / insertion order through the same append path, and the
     * new snapshot becomes visible only by the atomic CURRENT repoint. Old
     * segment directories are deleted afterwards while the exclusive lock is
     * still held, so no reader can observe a missing file. A crash at either
     * injection point leaves the previous (or the new, complete) snapshot;
     * {@link #recover()} discards the half artifacts.
     */
    public Manifest compact() {
        lock.writeLock().lock();
        try {
            Manifest base = currentManifest;
            // Explicit requests always merge when >= 2 segments exist.
            // The configured threshold only governs how eagerly the operation
            // has an effect; a single segment is already fully converged.
            if (base.segments().size() < 2) {
                LOG.fine(() -> "compaction skipped: already converged (1 segment)");
                return base;
            }
            TableSchema schema = currentSchema;
            long segId = nextSegmentId++;
            List<String> allColumns = schema.fields().stream().map(FieldDef::name).toList();
            SegmentWriter writer = SegmentWriter.begin(tableDir, segId, schema, allColumns);
            for (SegmentMeta seg : base.segments()) {
                SegmentReader reader = new SegmentReader(
                        segmentsDir.resolve(SegmentFiles.segmentDirName(seg.segmentId())));
                for (Row row : reader.readPartitionRange(Long.MIN_VALUE, Long.MAX_VALUE,
                        schema, allColumns)) {
                    writer.append(row);
                }
            }
            SegmentMeta merged = writer.commit();
            Manifest next = new Manifest(base.version() + 1, schema.version(),
                    List.of(merged), base.rowCount(), "COMPACT", System.currentTimeMillis());
            manifestStore.publish(next);

            // Snapshot switched; delete the now obsolete segments.
            for (SegmentMeta seg : base.segments()) {
                deleteQuietly(segmentsDir.resolve(SegmentFiles.segmentDirName(seg.segmentId())));
            }
            currentManifest = next;
            manifestStore.pruneHistory(manifestHistoryKeep);
            LOG.info(() -> "compaction committed snapshot=v" + next.version()
                    + " mergedSegments=" + base.segments().size() + " rows=" + next.rowCount());
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ init helpers

    /** Create a brand-new table on disk and publish its empty INIT snapshot. */
    public static TableStore create(Path tableDir, TableSchema schema,
                                   int compactThreshold, int manifestHistoryKeep) {
        try {
            Files.createDirectories(tableDir.resolve(SegmentFiles.segmentsDir()));
        } catch (IOException e) {
            throw new StoreException("cannot create table dir " + tableDir, e);
        }
        SchemaStore schemas = new SchemaStore(tableDir, MAPPER);
        schemas.initialize(schema);
        Manifest init = new Manifest(0L, schema.version(), List.of(), 0L, "INIT",
                System.currentTimeMillis());
        ManifestStore manifests = new ManifestStore(tableDir, MAPPER);
        manifests.publish(init);
        return new TableStore(tableDir, compactThreshold, manifestHistoryKeep);
    }

    public static TableStore open(Path tableDir, int compactThreshold, int manifestHistoryKeep) {
        return new TableStore(tableDir, compactThreshold, manifestHistoryKeep);
    }
}
