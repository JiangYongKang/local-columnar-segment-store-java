package com.github.highcumontoa.localcolumnarsegmentstorejava.tests.unit;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectedRecord;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent writers interleaved with a background compactor.
 *
 * <p>Invariants checked after every run (repeated 5 times for local
 * reproducibility):
 * <ul>
 *   <li>every globally unique key is visible exactly once (no loss/duplication);</li>
 *   <li>accepted + rejected counts reconcile per batch;</li>
 *   <li>queries never throw while segments are merged underneath them;</li>
 *   <li>final row count equals the number of globally unique keys.</li>
 * </ul>
 * The log prints submitted input, published snapshot versions and the decision
 * basis for every batch outcome.
 */
class ConcurrentWriteCompactTest {

    private static final Logger LOG = Logger.getLogger(ConcurrentWriteCompactTest.class.getName());

    @TempDir
    Path tempDir;

    @RepeatedTest(5)
    void concurrentWritersInterleavedWithCompaction() throws Exception {
        Path dataDir = Files.createTempDirectory(tempDir, "cc-");
        StoreCatalog catalog = new StoreCatalog(dataDir, 3, 3);
        TableStore table = catalog.createTable("t", List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null),
                new FieldDef("day", DataType.LONG, false, false, true, null),
                new FieldDef("payload", DataType.STRING, true, false, false, "p")
        ), "day", 0L, 9L);

        int writerCount = 6;
        int perWriter = 40;          // 240 globally unique keys

        ExecutorService writers = Executors.newFixedThreadPool(writerCount);
        ExecutorService bg = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean compactorRunning =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch compactorDone = new CountDownLatch(1);
        List<Long> acceptedGlobal = Collections.synchronizedList(new ArrayList<>());
        List<RejectedRecord> rejectedGlobal = Collections.synchronizedList(new ArrayList<>());
        List<Long> snapshotVersions = Collections.synchronizedList(new ArrayList<>());

        for (int w = 0; w < writerCount; w++) {
            final long base = (long) w * 1000;
            writers.submit(() -> {
                await(start);
                for (int i = 0; i < perWriter; i++) {
                    long id = base + i;
                    Row row = Row.of(Map.of("id", id, "day", (long) (id % 10), "payload", "p" + id));
                    var result = table.write(List.of(row));
                    snapshotVersions.add(result.snapshotVersion());
                    if (result.acceptedCount() == 1) {
                        acceptedGlobal.add(id);
                    } else {
                        rejectedGlobal.addAll(result.rejected());
                    }
                }
            });
        }

        // Background compactor hammers compact() concurrently with the writers.
        // It uses a cooperative stop gate so the test can prove no merge is in
        // flight before performing the final convergence assertion.
        bg.submit(() -> {
            await(start);
            compactorRunning.set(true);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    table.compact();
                    Thread.sleep(2);
                }
            } catch (InterruptedException expectedOnShutdown) {
                // cooperative stop
            } finally {
                compactorRunning.set(false);
                compactorDone.countDown();
            }
        });
        // Background reader: queries must always observe a consistent snapshot.
        bg.submit(() -> {
            await(start);
            for (int i = 0; i < 200; i++) {
                List<Row> rows = table.query(0L, 9L, null, null, null);
                Set<Long> ids = rows.stream().map(r -> (Long) r.values().get("id"))
                        .collect(Collectors.toSet());
                assertEquals(rows.size(), ids.size(), "reader observed duplicate rows mid-flight");
                sleep(1);
            }
        });

        start.countDown();
        writers.shutdown();
        assertTrue(writers.awaitTermination(60, TimeUnit.SECONDS), "writers did not finish in time");
        bg.shutdownNow();
        assertTrue(bg.awaitTermination(10, TimeUnit.SECONDS), "background workers did not stop");
        assertTrue(compactorDone.await(10, TimeUnit.SECONDS), "compactor did not reach its stop gate");
        assertFalse(compactorRunning.get(), "no background merge may be in flight past this point");

        int expectedUnique = writerCount * perWriter;
        List<Row> finalRows = table.query(0L, 9L, null, null, null);
        Set<Long> visibleIds = finalRows.stream().map(r -> (Long) r.values().get("id"))
                .collect(Collectors.toSet());

        LOG.info(() -> "CONCURRENT-RUN submitted=" + expectedUnique
                + " accepted=" + acceptedGlobal.size()
                + " rejected=" + rejectedGlobal.size()
                + " visibleRows=" + finalRows.size()
                + " distinctIds=" + visibleIds.size()
                + " finalSnapshot=v" + table.snapshotInfo().version()
                + " finalSegments=" + table.snapshotInfo().segmentCount()
                + " snapshotsObserved=" + new HashSet<>(snapshotVersions)
                + " BASIS=commit lock serializes writes/compaction; visible=snapshot at CURRENT");

        assertEquals(expectedUnique, acceptedGlobal.size(),
                "all globally unique keys must be accepted; rejections=" + rejectedGlobal);
        assertEquals(expectedUnique, finalRows.size(), "no row lost or duplicated");
        assertEquals(expectedUnique, visibleIds.size(), "visible ids must be unique");
        assertTrue(acceptedGlobal.size() + rejectedGlobal.size() == expectedUnique,
                "accepted + rejected must reconcile with submitted records");

        // All writers and background merges are provably finished. One final
        // compaction converges the file count; the real invariants are the row
        // multiset and key uniqueness, not the exact transient segment count.
        table.compact();
        List<Row> afterCompact = table.query(0L, 9L, null, null, null);
        assertEquals(expectedUnique, afterCompact.size());
        Set<Long> afterIds = afterCompact.stream().map(r -> (Long) r.values().get("id"))
                .collect(Collectors.toSet());
        assertEquals(expectedUnique, afterIds.size(), "compaction lost or duplicated rows");
        assertEquals(1, table.snapshotInfo().segmentCount(),
                "quiescent compaction must converge to one segment");
    }

    @RepeatedTest(3)
    void duplicateKeysFromConcurrentBatchesAreReportedAsConflicts() throws Exception {
        Path dataDir = Files.createTempDirectory(tempDir, "dup-");
        StoreCatalog catalog = new StoreCatalog(dataDir, 4, 3);
        TableStore table = catalog.createTable("u", List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null),
                new FieldDef("day", DataType.LONG, false, false, true, null)
        ), "day", 0L, 9L);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> accepted = Collections.synchronizedList(new ArrayList<>());
        List<String> conflictDetails = Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < 4; w++) {
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 25; i++) {
                    // All four writers race on the SAME key space [0..24].
                    var r = table.write(List.of(Row.of(Map.of("id", (long) i, "day", (long) (i % 10)))));
                    accepted.add(r.acceptedCount());
                    r.rejected().forEach(rr -> conflictDetails.add(rr.detail()));
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        int totalAccepted = accepted.stream().mapToInt(Integer::intValue).sum();
        LOG.info(() -> "CONFLICT-RUN accepted=" + totalAccepted + " conflicts=" + conflictDetails.size()
                + " visibleRows=" + table.query(0L, 9L, null, null, null).size()
                + " BASIS=winner commits first, losers get DUPLICATE_KEY, never overwrite");
        assertEquals(25, totalAccepted);
        assertEquals(75, conflictDetails.size());
        assertTrue(conflictDetails.stream().allMatch(d -> d.contains("already exists")));
        assertEquals(25, table.query(0L, 9L, null, null, null).size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
