package com.github.highcumontoa.localcolumnarsegmentstorejava.tests;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Compactor;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Snapshot;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrent writers plus a background compactor must not lose, duplicate or partially expose rows. */
class ConcurrentWriteCompactTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentWriteCompactTest.class);
    private static final String PARTITION = "2026-09-26";
    private static final int WRITERS = 4;
    private static final int BATCHES_PER_WRITER = 25;
    private static final int ROWS_PER_BATCH = 4;

    @TempDir
    Path dir;

    @Test
    void concurrentWritesAndCompactionStayConsistent() throws Exception {
        TableStore store = new TableStore(dir);
        store.create(TestSchemas.basicSchema());
        Compactor compactor = new Compactor(store);

        int totalRows = WRITERS * BATCHES_PER_WRITER * ROWS_PER_BATCH;
        log.info("input: {} writers x {} batches x {} rows = {} rows, partition {}",
                WRITERS, BATCHES_PER_WRITER, ROWS_PER_BATCH, totalRows, PARTITION);

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + 1);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < WRITERS; w++) {
            final int writer = w;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int b = 0; b < BATCHES_PER_WRITER; b++) {
                        List<Map<String, Object>> batch = new ArrayList<>();
                        for (int r = 0; r < ROWS_PER_BATCH; r++) {
                            long id = ((long) writer * BATCHES_PER_WRITER + b) * ROWS_PER_BATCH + r;
                            batch.add(TestSchemas.row(id, PARTITION, id * 1.0, "w" + writer));
                        }
                        var result = store.write(PARTITION, batch);
                        if (!result.rejected().isEmpty()) {
                            failures.add(new IllegalStateException("unexpected rejections: " + result.rejected()));
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 12; i++) {
                    long version = compactor.compactPartition(PARTITION);
                    log.info("compaction round {} -> snapshot v{}", i, version);
                    Thread.sleep(5);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }));

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES), "all tasks must finish");
        assertTrue(failures.isEmpty(), "no writer/compactor failures expected: " + failures);

        // judgement 1: every accepted row is visible exactly once
        List<Map<String, Object>> rows = store.query(PARTITION);
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            assertTrue(ids.add(row.get("id")), "duplicate id visible: " + row.get("id"));
        }
        log.info("judgement: visible rows={}, distinct ids={}, expected={}", rows.size(), ids.size(), totalRows);
        assertEquals(totalRows, ids.size(), "no row may be lost or duplicated");

        // judgement 2: a final compaction converges to one segment without changing rows
        long finalVersion = compactor.compactPartition(PARTITION);
        Snapshot snapshot = store.currentSnapshot();
        long segmentCount = snapshot.segments().stream().filter(m -> m.partition().equals(PARTITION)).count();
        List<Map<String, Object>> afterCompact = store.query(PARTITION);
        log.info("judgement: final snapshot v{}, segments for partition={}, rows unchanged={}",
                finalVersion, segmentCount, afterCompact.size() == rows.size());
        assertEquals(1, segmentCount, "compaction must converge the partition to a single segment");
        assertEquals(ids, new HashSet<>(afterCompact.stream().map(r -> r.get("id")).toList()));
        store.close();
    }
}
