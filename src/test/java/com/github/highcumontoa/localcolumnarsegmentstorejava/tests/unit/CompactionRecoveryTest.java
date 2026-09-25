package com.github.highcumontoa.localcolumnarsegmentstorejava.tests.unit;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.CrashHooks;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Manifest;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compaction and crash-recovery contract.
 *
 * <p>Crashes are injected deterministically via {@link CrashHooks} throwing
 * {@link CrashHooks.JvmCrashError} at the two publication boundaries. After
 * reopening the table on disk the recovered state must be the unique
 * consistent snapshot - either the old one or the new one - never a half
 * merged state; rows are neither lost nor duplicated.
 */
class CompactionRecoveryTest {

    private static final Logger LOG = Logger.getLogger(CompactionRecoveryTest.class.getName());

    private static List<FieldDef> schema() {
        return List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null),
                new FieldDef("day", DataType.LONG, false, false, true, null),
                new FieldDef("v", DataType.STRING, true, false, false, "d"));
    }

    /** Produce {@code batches} snapshots with {@code perBatch} distinct rows each. */
    private List<Long> seedSegments(TableStore table, int batches, int perBatch) {
        List<Long> ids = new ArrayList<>();
        long id = 1;
        for (int b = 0; b < batches; b++) {
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < perBatch; i++) {
                long day = ((id - 1) % 5); // 5 partitions
                rows.add(Row.of(Map.of("id", id, "day", day, "v", "v" + id)));
                ids.add(id);
                id++;
            }
            var result = table.write(rows);
            int batchNo = b;
            LOG.info(() -> "SEED batch " + batchNo + " -> snapshot v" + result.snapshotVersion());
        }
        return ids;
    }

    private Map<Long, Row> indexedRows(TableStore t) {
        return t.query(null, null, null, null, null).stream()
                .collect(Collectors.toMap(r -> (Long) r.values().get("id"), r -> r));
    }

    @Test
    void compactionConvergesFileCountWithoutChangingRows() {
        Path dir = createTableDir("compact_ok", 4, 3);
        StoreCatalog catalog = new StoreCatalog(dir, 4, 3);
        TableStore table = catalog.getTable("compact_ok");
        List<Long> ids = seedSegments(table, 5, 3); // 5 segments >= threshold 4

        long beforeVersion = table.snapshotInfo().version();
        assertEquals(5, table.snapshotInfo().segmentCount());

        Manifest m = table.compact();
        LOG.info(() -> "COMPACT v" + beforeVersion + " -> v" + m.version()
                + " segments " + table.snapshotInfo().segmentCount()
                + " rows " + m.rowCount()
                + " BASIS=row multiset streamed unchanged, pointer atomically switched");

        assertEquals(1, table.snapshotInfo().segmentCount());
        assertEquals(15, m.rowCount());
        Map<Long, Row> rows = indexedRows(table);
        assertEquals(Set.copyOf(ids), rows.keySet());
        assertEquals("v7", rows.get(7L).values().get("v"));

        // second compaction with only one segment is a no-op
        Manifest again = table.compact();
        assertEquals(m.version(), again.version());
    }

    @Test
    void crashBeforeManifestPublishKeepsOldSnapshotAndCleansArtifactsOnReopen() {
        String name = "crash_before_publish";
        Path dir = createTableDir(name, 4, 3);
        StoreCatalog catalog = new StoreCatalog(dir, 4, 3);
        TableStore table = catalog.getTable(name);
        List<Long> ids = seedSegments(table, 4, 2); // 8 rows, 4 segments
        long oldVersion = table.snapshotInfo().version();
        int oldSegments = table.snapshotInfo().segmentCount();

        table.setCrashHooks(new CrashHooks() {
            @Override
            public void beforeManifestPublish(Manifest pending) {
                LOG.severe("SIMULATED CRASH before manifest publish for pending v"
                        + pending.version());
                throw new JvmCrashError("beforeManifestPublish(compact)");
            }
        });
        assertThrows(CrashHooks.JvmCrashError.class, table::compact);
        LOG.info("process 'killed' mid-compaction; segment file already on disk");

        // Simulate process restart: reopen purely from disk with default hooks.
        StoreCatalog catalog2 = new StoreCatalog(dir, 4, 3);
        TableStore reopened = catalog2.getTable(name);
        LOG.info(() -> "RECOVERED snapshot=v" + reopened.snapshotInfo().version()
                + " segments=" + reopened.snapshotInfo().segmentCount()
                + " rows=" + reopened.snapshotInfo().rowCount()
                + " BASIS=CURRENT never moved; orphan merged segment deleted");

        assertEquals(oldVersion, reopened.snapshotInfo().version(),
                "snapshot must stay the old committed one");
        assertEquals(oldSegments, reopened.snapshotInfo().segmentCount(),
                "half-built merged segment must not be visible");
        assertEquals(8, reopened.snapshotInfo().rowCount());
        assertEquals(Set.copyOf(ids), indexedRows(reopened).keySet());

        // Recovery is idempotent: reopen again, same unique snapshot.
        StoreCatalog catalog3 = new StoreCatalog(dir, 4, 3);
        assertEquals(oldVersion, catalog3.getTable(name).snapshotInfo().version());
    }

    @Test
    void crashAfterManifestDurableButBeforeCurrentRepoint() {
        String name = "crash_durable_manifest";
        Path dir = createTableDir(name, 2, 3); // low threshold so compaction triggers
        StoreCatalog catalog = new StoreCatalog(dir, 2, 3);
        TableStore table = catalog.getTable(name);
        List<Long> ids = seedSegments(table, 3, 2); // 6 rows, 3 segments
        long oldVersion = table.snapshotInfo().version();

        table.setCrashHooks(new CrashHooks() {
            @Override
            public void afterManifestPersisted(Manifest pending) {
                LOG.severe("SIMULATED CRASH after durable manifest v" + pending.version()
                        + " but before CURRENT repoint");
                throw new JvmCrashError("afterManifestPersisted(compact)");
            }
        });
        assertThrows(CrashHooks.JvmCrashError.class, table::compact);

        // On restart the complete new manifest is the unique consistent snapshot
        // and recovery promotes it by repointing CURRENT.
        StoreCatalog catalog2 = new StoreCatalog(dir, 2, 3);
        TableStore reopened = catalog2.getTable(name);
        LOG.info(() -> "RECOVERED snapshot=v" + reopened.snapshotInfo().version()
                + " (expected v" + (oldVersion + 1) + ") segments="
                + reopened.snapshotInfo().segmentCount() + " rows="
                + reopened.snapshotInfo().rowCount()
                + " BASIS=highest complete manifest promoted; old orphan segments deleted");

        assertEquals(oldVersion + 1, reopened.snapshotInfo().version());
        assertEquals(1, reopened.snapshotInfo().segmentCount());
        assertEquals(6, reopened.snapshotInfo().rowCount());
        assertEquals(Set.copyOf(ids), indexedRows(reopened).keySet());
    }

    @Test
    void crashBeforeManifestPublishDuringWriteRollsBackTheUncommittedBatch() {
        String name = "crash_early_write";
        Path dir = createTableDir(name, 4, 3);
        StoreCatalog catalog = new StoreCatalog(dir, 4, 3);
        TableStore table = catalog.getTable(name);
        seedSegments(table, 2, 2); // 4 rows, snapshots v1..v2
        long stableVersion = table.snapshotInfo().version();

        table.setCrashHooks(new CrashHooks() {
            @Override
            public void beforeManifestPublish(Manifest pending) {
                if ("WRITE".equals(pending.operation())) {
                    LOG.severe("SIMULATED CRASH before write manifest for v" + pending.version());
                    throw new JvmCrashError("beforeManifestPublish(write)");
                }
            }
        });
        List<Row> doomed = List.of(Row.of(Map.of("id", 99L, "day", 1L, "v", "ghost")));
        assertThrows(CrashHooks.JvmCrashError.class, () -> table.write(doomed));

        StoreCatalog catalog2 = new StoreCatalog(dir, 4, 3);
        TableStore reopened = catalog2.getTable(name);
        LOG.info(() -> "RECOVERED snapshot=v" + reopened.snapshotInfo().version()
                + " rows=" + reopened.snapshotInfo().rowCount()
                + " BASIS=manifest never written; tmp segment removed; id=99 absent");
        assertEquals(stableVersion, reopened.snapshotInfo().version());
        assertEquals(4, reopened.snapshotInfo().rowCount());
        assertFalse(indexedRows(reopened).containsKey(99L));

        // key 99 is not a phantom: retrying the insert succeeds
        var retry = reopened.write(doomed);
        assertEquals(1, retry.acceptedCount(), retry.rejected().toString());
        assertTrue(indexedRows(reopened).containsKey(99L));
    }

    @Test
    void crashAfterWriteManifestDurableCommitsItOnRecovery() {
        String name = "crash_late_write";
        Path dir = createTableDir(name, 4, 3);
        StoreCatalog catalog = new StoreCatalog(dir, 4, 3);
        TableStore table = catalog.getTable(name);
        seedSegments(table, 2, 2);
        long stableVersion = table.snapshotInfo().version();

        table.setCrashHooks(new CrashHooks() {
            @Override
            public void afterManifestPersisted(Manifest pending) {
                if ("WRITE".equals(pending.operation())) {
                    LOG.severe("SIMULATED CRASH after durable write manifest v" + pending.version());
                    throw new JvmCrashError("afterManifestPersisted(write)");
                }
            }
        });
        List<Row> batch = List.of(Row.of(Map.of("id", 99L, "day", 1L, "v", "ghost")));
        assertThrows(CrashHooks.JvmCrashError.class, () -> table.write(batch));

        StoreCatalog catalog2 = new StoreCatalog(dir, 4, 3);
        TableStore reopened = catalog2.getTable(name);
        LOG.info(() -> "RECOVERED snapshot=v" + reopened.snapshotInfo().version()
                + " rows=" + reopened.snapshotInfo().rowCount()
                + " BASIS=durable transaction must be committed, not rolled back");
        assertEquals(stableVersion + 1, reopened.snapshotInfo().version());
        assertEquals(5, reopened.snapshotInfo().rowCount());
        assertTrue(indexedRows(reopened).containsKey(99L));

        // resubmitting the same key is now a true conflict
        var dup = reopened.write(batch);
        assertEquals(0, dup.acceptedCount());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.DUPLICATE_KEY,
                dup.rejected().get(0).reason());
    }

    /** Helper: create table files directly and return its data directory. */
    private Path createTableDir(String name, int threshold, int keep) {
        Path dataDir = tempDir(name);
        StoreCatalog catalog = new StoreCatalog(dataDir, threshold, keep);
        catalog.createTable(name, schema(), "day", 0L, 4L);
        return dataDir;
    }

    private Path tempDir(String suffix) {
        try {
            return Files.createTempDirectory("lcs-" + suffix + "-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
