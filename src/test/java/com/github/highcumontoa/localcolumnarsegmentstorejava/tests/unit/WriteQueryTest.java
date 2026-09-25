package com.github.highcumontoa.localcolumnarsegmentstorejava.tests.unit;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.IncompatibleSchemaException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectedRecord;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.WriteResult;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic write/query contract:
 * committed rows are queryable, and every invalid record comes back with a
 * distinguishable reason rather than being silently dropped.
 */
class WriteQueryTest {

    private static final Logger LOG = Logger.getLogger(WriteQueryTest.class.getName());

    @TempDir
    Path tempDir;

    private TableStore table;

    @BeforeEach
    void setUp() {
        StoreCatalog catalog = new StoreCatalog(tempDir, 4, 3);
        table = catalog.createTable("events", List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null),
                new FieldDef("day", DataType.LONG, false, false, true, null),
                new FieldDef("score", DataType.DOUBLE, true, false, false, 0.0),
                new FieldDef("label", DataType.STRING, true, false, false, "NA")
        ), "day", 0L, 9L);
    }

    @Test
    void validRowsCommitAndBecomeVisible() {
        List<Row> input = List.of(
                Row.of(Map.of("id", 1L, "day", 0L, "score", 1.5, "label", "a")),
                Row.of(Map.of("id", 2L, "day", 9L)) // defaults kick in
        );
        WriteResult result = table.write(input);
        LOG.info(() -> "INPUT=" + input + " SNAPSHOT=v" + result.snapshotVersion()
                + " ACCEPTED=" + result.acceptedCount()
                + " BASIS=all required fields present and types match");

        assertEquals(2, result.acceptedCount());
        assertEquals(1L, result.snapshotVersion());
        assertTrue(result.rejected().isEmpty());

        List<Row> all = table.query(null, null, null, null, null);
        assertEquals(2, all.size());
        LOG.info(() -> "QUERY FULL SNAPSHOT=v" + table.snapshotInfo().version()
                + " ROWS=" + all);

        Row second = all.get(1);
        assertEquals(0.0, second.values().get("score"));
        assertEquals("NA", second.values().get("label"));
    }

    @Test
    void rejectionReasonsAreDistinguishable() {
        List<Row> input = List.of(
                Row.of(Map.of("id", 10L, "day", 1L, "score", 1.0, "label", "ok")),
                // missing required field 'day'
                Row.of(Map.of("id", 11L, "score", 1.0, "label", "no-day")),
                // type mismatch: id sent as string
                Row.of(Map.of("id", "x", "day", 1L, "score", 1.0, "label", "bad-type")),
                // partition violation: above upper bound
                Row.of(Map.of("id", 12L, "day", 100L, "score", 1.0, "label", "bad-part")),
                // partition violation: below lower bound
                Row.of(Map.of("id", 13L, "day", -1L, "score", 1.0, "label", "bad-part2")),
                // duplicate key within batch
                Row.of(Map.of("id", 10L, "day", 2L, "score", 2.0, "label", "dup-batch")),
                // unknown column must not be silently ignored
                Row.of(Map.of("id", 14L, "day", 2L, "ghost", true))
        );
        WriteResult result = table.write(input);

        String summary = result.rejected().stream()
                .map(r -> r.index() + ":" + r.reason())
                .collect(Collectors.joining(","));
        LOG.info(() -> "INPUT=" + input + " SNAPSHOT=v" + result.snapshotVersion()
                + " ACCEPTED=" + result.acceptedCount()
                + " REJECTED=" + summary
                + " DETAIL=" + result.rejected().stream().map(RejectedRecord::detail).toList());

        assertEquals(1, result.acceptedCount());
        assertEquals(6, result.rejected().size());
        Map<Integer, RejectedRecord> byIndex = result.rejected().stream()
                .collect(Collectors.toMap(RejectedRecord::index, r -> r));
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.MISSING_FIELD,
                byIndex.get(1).reason());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.TYPE_MISMATCH,
                byIndex.get(2).reason());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.PARTITION_VIOLATION,
                byIndex.get(3).reason());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.PARTITION_VIOLATION,
                byIndex.get(4).reason());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.DUPLICATE_KEY,
                byIndex.get(5).reason());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.TYPE_MISMATCH,
                byIndex.get(6).reason());

        // Rejected rows are not visible; query sees only the one accepted row.
        assertEquals(1, table.query(null, null, null, null, null).size());

        // A later insert with the already committed key is a conflict, not an overwrite.
        WriteResult conflict = table.write(List.of(
                Row.of(Map.of("id", 10L, "day", 3L, "score", 9.9, "label", "dup-committed"))));
        LOG.info(() -> "CONFLICT-INPUT SNAPSHOT=v" + conflict.snapshotVersion()
                + " REJECTED=" + conflict.rejected()
                + " BASIS=key already committed, overwrite forbidden");
        assertEquals(0, conflict.acceptedCount());
        assertEquals(com.github.highcumontoa.localcolumnarsegmentstorejava.model.RejectReason.DUPLICATE_KEY,
                conflict.rejected().get(0).reason());
        // partition predicate applies to the partition column ('day'), not the key
        List<Row> rows = table.query(null, null, 1L, null, null);
        assertEquals(1, rows.size());
        assertEquals(10L, rows.get(0).values().get("id"));
        assertEquals("ok", rows.get(0).values().get("label")); // original preserved
    }

    @Test
    void tableWithoutKeyIsRejectedAtCreation() {
        StoreCatalog catalog = new StoreCatalog(tempDir.resolve("other"), 4, 3);
        IncompatibleSchemaException ex = assertThrows(IncompatibleSchemaException.class,
                () -> catalog.createTable("nokey", List.of(
                        new FieldDef("v", DataType.LONG, true, false, false, null)
                ), null, null, null));
        LOG.info(() -> "CREATE-REJECTED BASIS=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("key"));
    }
}
