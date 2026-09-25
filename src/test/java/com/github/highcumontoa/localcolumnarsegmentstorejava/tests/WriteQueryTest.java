package com.github.highcumontoa.localcolumnarsegmentstorejava.tests;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.RejectReason;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Write/query semantics and distinguishable rejection reasons. */
class WriteQueryTest {

    private static final Logger log = LoggerFactory.getLogger(WriteQueryTest.class);

    @TempDir
    Path dir;

    @Test
    void committedSnapshotIsQueryableAndReproducible() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            var input = List.of(
                    TestSchemas.row(1, "2026-09-26", 1.5, "a"),
                    TestSchemas.row(2, "2026-09-26", 2.5, "b"));
            log.info("input records: {}", input);
            WriteResult result = store.write("2026-09-26", input);
            log.info("write result: version={} accepted={} rejected={}",
                    result.snapshotVersion(), result.accepted(), result.rejected());
            assertEquals(0, result.rejected().size());

            List<Map<String, Object>> latest = store.query("2026-09-26");
            List<Map<String, Object>> pinned = store.query("2026-09-26", result.snapshotVersion());
            log.info("judgement: latest={} pinned@v{}={}", latest, result.snapshotVersion(), pinned);
            assertEquals(input, latest);
            assertEquals(input, pinned, "querying a pinned snapshot must reproduce the same rows");
        }
    }

    @Test
    void rejectionsCarryDistinguishableReasons() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            store.write("2026-09-26", List.of(TestSchemas.row(1, "2026-09-26", 1.5, "a")));

            Map<String, Object> missingField = Map.of("id", 2L, "dt", "2026-09-26", "value", 3.0);
            Map<String, Object> typeMismatch = TestSchemas.row(3, "2026-09-26", 3.0, "c");
            typeMismatch.put("value", "not-a-number");
            Map<String, Object> partitionViolation = TestSchemas.row(4, "2026-09-27", 4.0, "d");
            Map<String, Object> keyConflict = TestSchemas.row(1, "2026-09-26", 9.9, "dup");

            var input = List.of(missingField, typeMismatch, partitionViolation, keyConflict);
            log.info("input records: {}", input);
            WriteResult result = store.write("2026-09-26", input);
            log.info("rejections: {}", result.rejected());

            assertEquals(0, result.accepted());
            assertEquals(4, result.rejected().size());
            assertEquals(RejectReason.MISSING_FIELD, result.rejected().get(0).reason());
            assertEquals(RejectReason.TYPE_MISMATCH, result.rejected().get(1).reason());
            assertEquals(RejectReason.PARTITION_VIOLATION, result.rejected().get(2).reason());
            assertEquals(RejectReason.KEY_CONFLICT, result.rejected().get(3).reason());

            List<Map<String, Object>> rows = store.query("2026-09-26");
            log.info("judgement: only the pre-existing row remains visible: {}", rows);
            assertEquals(1, rows.size(), "rejected records must never be silently dropped into the snapshot");
            assertEquals(1L, rows.get(0).get("id"));
        }
    }

    @Test
    void duplicateKeyWithinBatchIsRejected() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            var input = List.of(
                    TestSchemas.row(1, "2026-09-26", 1.0, "first"),
                    TestSchemas.row(1, "2026-09-26", 2.0, "second"));
            log.info("input records: {}", input);
            WriteResult result = store.write("2026-09-26", input);
            log.info("write result: version={} accepted={} rejected={}",
                    result.snapshotVersion(), result.accepted(), result.rejected());
            assertEquals(1, result.accepted());
            assertEquals(1, result.rejected().size());
            assertEquals(RejectReason.KEY_CONFLICT, result.rejected().get(0).reason());
            assertTrue(store.query("2026-09-26").stream().anyMatch(r -> "first".equals(r.get("name"))));
        }
    }
}
