package com.github.highcumontoa.localcolumnarsegmentstorejava.tests;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ConcurrentCommitException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ManifestStore;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.SegmentWriter;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Snapshot;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A crash in the middle of compaction must recover to exactly one consistent snapshot. */
class CrashRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(CrashRecoveryTest.class);
    private static final String PARTITION = "2026-09-26";

    @TempDir
    Path dir;

    @Test
    void crashMidCompactionRecoversSingleConsistentSnapshot() throws Exception {
        long committedVersion;
        List<Map<String, Object>> expectedRows;
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            for (int b = 0; b < 3; b++) {
                store.write(PARTITION, List.of(
                        TestSchemas.row(b * 2L, PARTITION, b * 2.0, "r" + b * 2),
                        TestSchemas.row(b * 2L + 1, PARTITION, b * 2.0 + 1, "r" + (b * 2 + 1))));
            }
            committedVersion = store.currentSnapshot().version();
            expectedRows = store.query(PARTITION);
            log.info("input: 3 batches committed, snapshot v{}, rows={}", committedVersion, expectedRows);

            // simulate an interrupted compaction:
            // 1) merged segment file written but never referenced (orphan)
            Snapshot snapshot = store.currentSnapshot();
            var orphan = new SegmentWriter().write(dir, snapshot.schema(), PARTITION, expectedRows);
            // 2) next manifest written but CURRENT never switched (crash between the two renames)
            String manifest = Files.readString(dir.resolve("manifest-" + committedVersion + ".json"))
                    .replace("\"version\" : " + committedVersion, "\"version\" : " + (committedVersion + 1));
            Files.writeString(dir.resolve("manifest-" + (committedVersion + 1) + ".json"), manifest);
            // 3) half-written tmp files left behind
            Files.writeString(dir.resolve("seg-deadbeef.col.tmp"), "partial");
            Files.writeString(dir.resolve("CURRENT.tmp"), Long.toString(committedVersion + 1));
            log.info("crash simulation: orphan segment {}, stray manifest v{}, tmp files left behind",
                    orphan.fileName(), committedVersion + 1);
        }

        // reopen: recovery must restore the single committed snapshot and clean debris
        try (TableStore store = new TableStore(dir)) {
            Snapshot recovered = store.currentSnapshot();
            List<Map<String, Object>> rows = store.query(PARTITION);
            log.info("judgement: recovered snapshot v{} (expected v{}), rows={}", 
                    recovered.version(), committedVersion, rows);
            assertEquals(committedVersion, recovered.version(),
                    "recovery must restore the last fully committed snapshot, not a half-finished one");
            assertEquals(expectedRows, rows, "no row may be lost, duplicated or partially visible");

            try (Stream<Path> files = Files.list(dir)) {
                List<String> names = files.map(f -> f.getFileName().toString()).sorted().toList();
                log.info("judgement: directory after recovery = {}", names);
                assertTrue(names.stream().noneMatch(n -> n.endsWith(".tmp")), "tmp debris must be cleaned");
                assertTrue(names.stream().noneMatch(n -> n.equals("manifest-" + (committedVersion + 1) + ".json")),
                        "uncommitted manifest must be removed");
                assertEquals(3, names.stream().filter(n -> n.startsWith("seg-")).count(),
                        "orphan merged segment must be removed, 3 committed segments remain");
            }

            // compaction can be re-run after recovery and converges safely
            long v = store.compact(PARTITION);
            List<Map<String, Object>> afterCompact = store.query(PARTITION);
            log.info("judgement: re-run compaction -> v{}, rows unchanged={}", v, afterCompact.equals(expectedRows));
            assertEquals(expectedRows, afterCompact);
        }
    }

    @Test
    void concurrentCommitConflictIsSurfacedNotSilentlyOverwritten() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            ManifestStore manifests = new ManifestStore(dir);
            Snapshot current = store.currentSnapshot();
            Snapshot next = new Snapshot(current.version() + 1, current.schema(), current.segments());
            manifests.commit(next, current.version());
            log.info("input: two commits racing for expectedVersion={}", current.version());
            ConcurrentCommitException conflict = assertThrows(ConcurrentCommitException.class,
                    () -> manifests.commit(next, current.version()));
            log.info("judgement: losing commit surfaced as: {}", conflict.getMessage());
            assertEquals(current.version() + 1, store.currentSnapshot().version(),
                    "the winning commit must remain intact");
        }
    }
}
