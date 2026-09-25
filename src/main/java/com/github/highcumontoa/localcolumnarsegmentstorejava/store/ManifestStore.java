package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Persists snapshots as {@code manifest-<version>.json} files plus a {@code CURRENT}
 * pointer file containing the committed version number.
 *
 * Commit protocol (crash safe):
 *   1. write manifest-<version>.json.tmp, atomically rename to manifest-<version>.json
 *   2. write CURRENT.tmp, atomically rename to CURRENT
 * A crash between the two renames leaves the old CURRENT in place, so the store
 * always recovers to exactly one consistent snapshot; the orphan manifest is
 * removed by {@link #recover()}.
 *
 * The expected-version check and the pointer swap are serialized per table
 * directory, so a losing concurrent committer gets a
 * {@link ConcurrentCommitException} instead of silently overwriting.
 */
public final class ManifestStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<Path, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private final Path tableDir;
    private final Object lock;

    public ManifestStore(Path tableDir) {
        this.tableDir = tableDir;
        this.lock = LOCKS.computeIfAbsent(tableDir.toAbsolutePath().normalize(), k -> new Object());
    }

    /**
     * Per-table-directory monitor. Readers that load a snapshot and then read its
     * segment files, and the compactor when it deletes superseded segment files,
     * must synchronize on this lock so deletion never races an in-flight read.
     * It is reentrant, so {@link #commit} may be called while holding it.
     */
    public Object lock() {
        return lock;
    }

    public Optional<Snapshot> loadCurrent() throws IOException {
        Path current = tableDir.resolve("CURRENT");
        if (!Files.exists(current)) {
            return Optional.empty();
        }
        long version = Long.parseLong(Files.readString(current).trim());
        return loadSnapshot(version);
    }

    public Optional<Snapshot> loadSnapshot(long version) throws IOException {
        Path manifest = tableDir.resolve("manifest-" + version + ".json");
        if (!Files.exists(manifest)) {
            return Optional.empty();
        }
        return Optional.of(fromJson(MAPPER.readTree(Files.readString(manifest))));
    }

    public void commit(Snapshot snapshot, long expectedVersion) throws IOException {
        synchronized (lock) {
            long currentVersion = currentVersion();
            if (currentVersion != expectedVersion) {
                throw new ConcurrentCommitException(
                        "commit rejected: expected version " + expectedVersion
                                + " but current is " + currentVersion
                                + " (concurrent writer/compactor won the race)");
            }
            if (snapshot.version() <= currentVersion) {
                throw new ConcurrentCommitException(
                        "commit rejected: snapshot version " + snapshot.version()
                                + " must be greater than current " + currentVersion);
            }
            Path manifest = tableDir.resolve("manifest-" + snapshot.version() + ".json");
            Path manifestTmp = tableDir.resolve("manifest-" + snapshot.version() + ".json.tmp");
            Files.writeString(manifestTmp, toJson(snapshot));
            Files.move(manifestTmp, manifest, StandardCopyOption.ATOMIC_MOVE);
            Path currentTmp = tableDir.resolve("CURRENT.tmp");
            Files.writeString(currentTmp, Long.toString(snapshot.version()));
            Files.move(currentTmp, tableDir.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Recover after a crash: delete tmp files, orphan manifests newer than CURRENT,
     * and segment files not referenced by the committed snapshot.
     */
    public void recover() throws IOException {
        synchronized (lock) {
            if (!Files.exists(tableDir)) {
                return;
            }
            long currentVersion = currentVersion();
            Set<String> referencedSegments = new HashSet<>();
            if (currentVersion >= 0) {
                Optional<Snapshot> snapshot = loadSnapshot(currentVersion);
                snapshot.ifPresent(s -> s.segments().forEach(m -> referencedSegments.add(m.fileName())));
            }
            try (Stream<Path> files = Files.list(tableDir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".tmp")) {
                        Files.deleteIfExists(file);
                    } else if (name.startsWith("manifest-") && name.endsWith(".json")) {
                        long v = Long.parseLong(name.substring("manifest-".length(), name.length() - ".json".length()));
                        if (v > currentVersion) {
                            Files.deleteIfExists(file);
                        }
                    } else if (name.startsWith("seg-") && name.endsWith(".col") && !referencedSegments.contains(name)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    /** Delete segment files no longer referenced after a successful compaction commit. */
    public void deleteSegments(List<SegmentMeta> segments) throws IOException {
        for (SegmentMeta meta : segments) {
            Files.deleteIfExists(tableDir.resolve(meta.fileName()));
        }
    }

    private long currentVersion() throws IOException {
        Path current = tableDir.resolve("CURRENT");
        if (!Files.exists(current)) {
            return -1;
        }
        return Long.parseLong(Files.readString(current).trim());
    }

    private String toJson(Snapshot snapshot) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", snapshot.version());
        ObjectNode schema = root.putObject("schema");
        Schema s = snapshot.schema();
        schema.put("version", s.version());
        schema.put("keyColumn", s.keyColumn());
        schema.put("partitionColumn", s.partitionColumn());
        ArrayNode columns = schema.putArray("columns");
        for (ColumnDef c : s.columns()) {
            ObjectNode col = columns.addObject();
            col.put("name", c.name());
            col.put("type", c.type().name());
        }
        ObjectNode defaults = schema.putObject("defaults");
        s.defaults().forEach((k, v) -> defaults.putPOJO(k, v));
        ArrayNode segments = root.putArray("segments");
        for (SegmentMeta m : snapshot.segments()) {
            ObjectNode seg = segments.addObject();
            seg.put("segmentId", m.segmentId());
            seg.put("partition", m.partition());
            seg.put("rowCount", m.rowCount());
            seg.put("schemaVersion", m.schemaVersion());
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Snapshot fromJson(JsonNode root) {
        JsonNode schemaNode = root.get("schema");
        List<ColumnDef> columns = new ArrayList<>();
        for (JsonNode col : schemaNode.get("columns")) {
            columns.add(new ColumnDef(col.get("name").asText(), ColumnType.valueOf(col.get("type").asText())));
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        schemaNode.get("defaults").fields().forEachRemaining(e -> defaults.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class)));
        Schema schema = new Schema(schemaNode.get("version").asLong(), columns,
                schemaNode.get("keyColumn").asText(), schemaNode.get("partitionColumn").asText(), defaults);
        List<SegmentMeta> segments = new ArrayList<>();
        for (JsonNode seg : root.get("segments")) {
            segments.add(new SegmentMeta(seg.get("segmentId").asText(), seg.get("partition").asText(),
                    seg.get("rowCount").asLong(), seg.get("schemaVersion").asLong()));
        }
        return new Snapshot(root.get("version").asLong(), schema, segments);
    }
}
