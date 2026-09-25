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

/** Partition boundary queries: rows land only in their exact partition. */
class PartitionBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(PartitionBoundaryTest.class);

    @TempDir
    Path dir;

    @Test
    void rowsAreVisibleOnlyInTheirOwnPartition() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            var day1 = List.of(TestSchemas.row(1, "2026-09-30", 1.0, "end-of-month"),
                               TestSchemas.row(2, "2026-09-30", 2.0, "b"));
            var day2 = List.of(TestSchemas.row(3, "2026-10-01", 3.0, "start-of-month"));
            log.info("input: partition 2026-09-30 <- {}, partition 2026-10-01 <- {}", day1, day2);
            long v1 = store.write("2026-09-30", day1).snapshotVersion();
            long v2 = store.write("2026-10-01", day2).snapshotVersion();

            List<Map<String, Object>> q1 = store.query("2026-09-30");
            List<Map<String, Object>> q2 = store.query("2026-10-01");
            List<Map<String, Object>> qEmpty = store.query("2026-10-02");
            log.info("judgement: v{}..v{}; 2026-09-30 -> {}, 2026-10-01 -> {}, 2026-10-02 -> {}",
                    v1, v2, q1, q2, qEmpty);
            assertEquals(2, q1.size());
            assertEquals(1, q2.size());
            assertTrue(q1.stream().allMatch(r -> "2026-09-30".equals(r.get("dt"))),
                    "no row may leak across the partition boundary");
            assertTrue(q2.stream().allMatch(r -> "2026-10-01".equals(r.get("dt"))));
            assertTrue(qEmpty.isEmpty(), "unknown partition must return an empty result");
        }
    }

    @Test
    void recordOnWrongSideOfBoundaryIsRejected() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            var input = List.of(
                    TestSchemas.row(1, "2026-09-30", 1.0, "in-boundary"),
                    TestSchemas.row(2, "2026-10-01", 2.0, "next-day"),
                    TestSchemas.row(3, "2026-09-29", 3.0, "previous-day"));
            log.info("input: target partition 2026-09-30, records {}", input);
            WriteResult result = store.write("2026-09-30", input);
            log.info("judgement: accepted={} rejected={}", result.accepted(), result.rejected());
            assertEquals(1, result.accepted());
            assertEquals(2, result.rejected().size());
            assertTrue(result.rejected().stream().allMatch(r -> r.reason() == RejectReason.PARTITION_VIOLATION));
            assertEquals(1, store.query("2026-09-30").size());
        }
    }

    @Test
    void compactingOnePartitionLeavesOthersUntouched() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            store.write("2026-09-30", List.of(TestSchemas.row(1, "2026-09-30", 1.0, "a")));
            store.write("2026-09-30", List.of(TestSchemas.row(2, "2026-09-30", 2.0, "b")));
            store.write("2026-10-01", List.of(TestSchemas.row(3, "2026-10-01", 3.0, "c")));
            List<Map<String, Object>> otherBefore = store.query("2026-10-01");

            long v = store.compact("2026-09-30");
            var snapshot = store.currentSnapshot();
            long segs0930 = snapshot.segments().stream().filter(m -> m.partition().equals("2026-09-30")).count();
            long segs1001 = snapshot.segments().stream().filter(m -> m.partition().equals("2026-10-01")).count();
            log.info("judgement: compact -> v{}; segments(2026-09-30)={}, segments(2026-10-01)={}",
                    v, segs0930, segs1001);
            assertEquals(1, segs0930, "compacted partition converges to one segment");
            assertEquals(1, segs1001, "other partition's segment is untouched");
            assertEquals(otherBefore, store.query("2026-10-01"));
            assertEquals(2, store.query("2026-09-30").size());
        }
    }
}
