package com.github.highcumontoa.localcolumnarsegmentstorejava.tests;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.IncompatibleSchemaChangeException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Schema;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Schema evolution: compatible add-column with defaults; incompatible changes rejected. */
class SchemaEvolutionTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaEvolutionTest.class);

    @TempDir
    Path dir;

    @Test
    void addColumnWithDefaultKeepsHistoryReadableAndReproducible() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            store.create(TestSchemas.basicSchema());
            var historical = List.of(TestSchemas.row(1, "2026-09-26", 1.5, "a"));
            long v1 = store.write("2026-09-26", historical).snapshotVersion();
            List<Map<String, Object>> before = store.query("2026-09-26");
            log.info("input historical rows: {}, snapshot v{}, readback={}", historical, v1, before);

            Schema evolved = store.evolveAddColumn(new ColumnDef("score", ColumnType.INT), 0);
            log.info("evolved schema to version {}: {}", evolved.version(), evolved.columns());

            // historical snapshot stays reproducible with the old schema view
            List<Map<String, Object>> pinnedOld = store.query("2026-09-26", v1);
            log.info("judgement: pinned v{} readback after evolution = {}", v1, pinnedOld);
            assertEquals(before, pinnedOld, "pinned snapshot must reproduce pre-evolution results");

            // latest view fills the default for old segments
            List<Map<String, Object>> latest = store.query("2026-09-26");
            log.info("judgement: latest readback with default = {}", latest);
            assertEquals(0, latest.get(0).get("score"), "old segment must read the new column as its default");

            // new writes must supply the column (or fall back to its default)
            Map<String, Object> newRow = new LinkedHashMap<>(TestSchemas.row(2, "2026-09-26", 2.5, "b"));
            newRow.put("score", 7);
            var result = store.write("2026-09-26", List.of(newRow));
            log.info("write after evolution: version={} accepted={}", result.snapshotVersion(), result.accepted());
            assertEquals(1, result.accepted());
            assertEquals(7, store.query("2026-09-26").get(1).get("score"));
        }
    }

    @Test
    void incompatibleChangesAreRejected() throws Exception {
        try (TableStore store = new TableStore(dir)) {
            Schema schema = TestSchemas.basicSchema();
            store.create(schema);
            var evolver = store.schemaEvolver();

            var drop = assertThrows(IncompatibleSchemaChangeException.class,
                    () -> evolver.dropColumn(schema, "name"));
            var rename = assertThrows(IncompatibleSchemaChangeException.class,
                    () -> evolver.renameColumn(schema, "name", "label"));
            var narrow = assertThrows(IncompatibleSchemaChangeException.class,
                    () -> evolver.changeType(schema, "id", ColumnType.INT));
            var widen = assertThrows(IncompatibleSchemaChangeException.class,
                    () -> evolver.changeType(schema, "value", ColumnType.LONG));
            var nullDefault = assertThrows(IncompatibleSchemaChangeException.class,
                    () -> evolver.addColumn(schema, new ColumnDef("x", ColumnType.STRING), null));
            var badDefault = assertThrows(IncompatibleSchemaChangeException.class,
                    () -> evolver.addColumn(schema, new ColumnDef("y", ColumnType.INT), "not-a-number"));
            log.info("judgement: rejected reasons: drop='{}' rename='{}' narrow='{}' widen='{}' nullDefault='{}' badDefault='{}'",
                    drop.getMessage(), rename.getMessage(), narrow.getMessage(),
                    widen.getMessage(), nullDefault.getMessage(), badDefault.getMessage());
        }
    }
}
