package com.github.highcumontoa.localcolumnarsegmentstorejava.tests.unit;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Partition boundary semantics:
 * range bounds are inclusive, boundary rows belong to the correct side,
 * whole-segment pruning and row-level filtering agree, and results stay
 * correct across segments written before/after an additive schema change.
 */
class PartitionBoundaryTest {

    private static final Logger LOG = Logger.getLogger(PartitionBoundaryTest.class.getName());

    private TableStore table;

    @BeforeEach
    void setUp() {
        Path dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("lcs-boundary-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        StoreCatalog catalog = new StoreCatalog(dir, 4, 3);
        table = catalog.createTable("p", List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null),
                new FieldDef("day", DataType.LONG, false, false, true, null)
        ), "day", 0L, 10L);
    }

    private void write(long... ids) {
        List<Row> rows = new java.util.ArrayList<>();
        for (long id : ids) {
            rows.add(Row.of(Map.of("id", id, "day", id)));
        }
        var r = table.write(rows);
        LOG.info(() -> "WROTE ids=" + java.util.Arrays.toString(ids)
                + " snapshot=v" + r.snapshotVersion());
    }

    @Test
    void boundariesAreInclusiveOnBothSides() {
        write(0L, 1L, 5L, 9L, 10L);

        assertRange(0L, 0L, List.of(0L));    // exact lower bound
        assertRange(10L, 10L, List.of(10L)); // exact upper bound
        assertRange(1L, 9L, List.of(1L, 5L, 9L));
        assertRange(5L, 5L, List.of(5L));
        assertRange(0L, 10L, List.of(0L, 1L, 5L, 9L, 10L));
        assertRange(null, 4L, List.of(0L, 1L)); // open low
        assertRange(6L, null, List.of(9L, 10L)); // open high
    }

    @Test
    void boundariesHoldAcrossSegmentsAndCompaction() {
        // 5 separate snapshots/segments
        write(0L);
        write(2L, 3L);
        write(5L);
        write(7L, 8L);
        write(10L);

        List<Long> expected = List.of(2L, 3L, 5L, 7L, 8L);
        assertRange(2L, 8L, expected);

        table.compact();
        LOG.info("compacted; re-checking boundary query against single merged segment");
        assertRange(2L, 8L, expected);
        // equality right at a boundary
        assertEq(10L, List.of(10L));
        assertEq(0L, List.of(0L));
        // out of declared range matches nothing
        assertTrue(table.query(11L, 100L, null, null, null).isEmpty());
    }

    @Test
    void boundaryRowsSurviveSchemaEvolution() {
        write(0L, 10L); // boundary rows in old segments
        table.addColumn("tag", DataType.STRING, false, "default-tag");
        var r = table.write(List.of(Row.of(Map.of("id", 5L, "day", 5L, "tag", "mid"))));
        LOG.info(() -> "post-evolution write snapshot=v" + r.snapshotVersion());

        List<Row> rows = table.query(0L, 10L, null, null, null);
        LOG.info(() -> "FULL RANGE after evolution: " + rows);
        assertEquals(3, rows.size());
        assertEquals("default-tag", rows.get(0).values().get("tag"));
        assertEquals("default-tag", rows.get(1).values().get("tag"));
        assertEquals("mid", rows.get(2).values().get("tag"));

        assertEq(10L, List.of(10L));
    }

    @Test
    void invalidRangeAndUnpartitionedTableAreRejected() {
        StoreException badRange = assertThrows(StoreException.class,
                () -> table.query(9L, 1L, null, null, null));
        LOG.info(() -> "BAD-RANGE BASIS=" + badRange.getMessage());
        assertTrue(badRange.getMessage().contains("low 9 > high 1"));

        Path dir2;
        try {
            dir2 = java.nio.file.Files.createTempDirectory("lcs-nopart-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        StoreCatalog catalog2 = new StoreCatalog(dir2, 4, 3);
        TableStore noPart = catalog2.createTable("n", List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null)
        ), null, null, null);
        noPart.write(List.of(Row.of(Map.of("id", 1L))));
        StoreException ex = assertThrows(StoreException.class,
                () -> noPart.query(null, null, 1L, null, null));
        LOG.info(() -> "NO-PARTITION BASIS=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("no partition column"));
    }

    private void assertRange(Long low, Long high, List<Long> expectedIds) {
        List<Row> rows = table.query(low, high, null, List.of("id"), null);
        List<Long> ids = rows.stream().map(r -> (Long) r.values().get("id"))
                .sorted().collect(Collectors.toList());
        LOG.info(() -> "RANGE [" + low + "," + high + "] SNAPSHOT=v"
                + table.snapshotInfo().version() + " -> " + ids
                + " BASIS=inclusive bounds, segment pruning + row filter");
        assertEquals(expectedIds, ids);
    }

    private void assertEq(long eq, List<Long> expectedIds) {
        List<Row> rows = table.query(null, null, eq, List.of("id"), null);
        List<Long> ids = rows.stream().map(r -> (Long) r.values().get("id"))
                .sorted().collect(Collectors.toList());
        LOG.info(() -> "EQ day=" + eq + " -> " + ids);
        assertEquals(expectedIds, ids);
    }
}
