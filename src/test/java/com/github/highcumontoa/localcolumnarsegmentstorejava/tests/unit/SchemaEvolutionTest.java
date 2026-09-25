package com.github.highcumontoa.localcolumnarsegmentstorejava.tests.unit;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.IncompatibleSchemaException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema evolution contract:
 * only nullable/defaulted column addition is allowed; every incompatible
 * change is rejected explicitly, and historical query results stay
 * reproducible when replayed against a fixed schema version.
 */
class SchemaEvolutionTest {

    private static final Logger LOG = Logger.getLogger(SchemaEvolutionTest.class.getName());

    @TempDir
    Path tempDir;

    private TableStore table;

    @BeforeEach
    void setUp() {
        StoreCatalog catalog = new StoreCatalog(tempDir, 4, 3);
        table = catalog.createTable("metrics", List.of(
                new FieldDef("id", DataType.LONG, false, true, false, null),
                new FieldDef("day", DataType.LONG, false, false, true, null),
                new FieldDef("v", DataType.DOUBLE, true, false, false, null)
        ), "day", 0L, 100L);
    }

    @Test
    void addedColumnGivesStableDefaultToHistoricalRows() {
        table.write(List.of(
                Row.of(Map.of("id", 1L, "day", 10L, "v", 1.0)),
                Row.of(Map.of("id", 2L, "day", 11L, "v", 2.0))
        ));

        // Add a column WITH default: old rows must read that default forever.
        var manifest = table.addColumn("region", DataType.STRING, false, "earth");
        LOG.info(() -> "ADD-COLUMN region DEFAULT=earth PUBLISHED=v" + manifest.version()
                + " schema=v" + manifest.schemaVersion()
                + " BASIS=column nullable=false but type-correct default supplied");
        assertEquals(2, table.currentSchema().version());

        table.write(List.of(Row.of(Map.of("id", 3L, "day", 12L, "v", 3.0, "region", "mars"))));

        List<Row> rows = table.query(0L, 100L, null, null, null);
        LOG.info(() -> "QUERY schema=v2 SNAPSHOT=v" + table.snapshotInfo().version() + " ROWS=" + rows);
        assertEquals("earth", rows.get(0).values().get("region"));
        assertEquals("earth", rows.get(1).values().get("region"));
        assertEquals("mars", rows.get(2).values().get("region"));

        // Replaying against schema v1 projects only the original columns; the
        // conclusion of a historical query is byte-stable.
        List<Row> historical = table.query(0L, 100L, null, List.of("id", "day", "v"), 1);
        LOG.info(() -> "HISTORICAL-QUERY schema=v1 ROWS=" + historical);
        assertEquals(3, historical.size());
        assertFalse(historical.get(0).values().containsKey("region"));
        assertEquals(1.0, historical.get(0).values().get("v"));
    }

    @Test
    void nullableColumnWithoutDefaultIsAllowed() {
        var m = table.addColumn("note", DataType.STRING, true, null);
        LOG.info(() -> "ADD-COLUMN note nullable PUBLISHED=v" + m.version());
        table.write(List.of(Row.of(Map.of("id", 1L, "day", 1L, "v", 1.0))));
        Row row = table.query(null, null, 1L, null, null).get(0);
        assertNull(row.values().get("note"));
    }

    @Test
    void nonNullableColumnWithoutDefaultIsRejected() {
        IncompatibleSchemaException ex = assertThrows(IncompatibleSchemaException.class,
                () -> table.addColumn("mandatory", DataType.STRING, false, null));
        LOG.info(() -> "ADD-COLUMN-REJECTED BASIS=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("nullable or declare a default"));
        assertEquals(1, table.currentSchema().version(), "schema must not advance after rejection");
    }

    @Test
    void wrongTypedDefaultIsRejected() {
        IncompatibleSchemaException ex = assertThrows(IncompatibleSchemaException.class,
                () -> table.addColumn("flag", DataType.BOOLEAN, false, "not-a-bool"));
        LOG.info(() -> "ADD-COLUMN-REJECTED BASIS=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("does not match type BOOLEAN"));
    }

    @Test
    void incompatibleChangesAreAllRejected() {
        // duplicate add
        expectRejected("duplicate add",
                () -> table.addColumn("v", DataType.STRING, true, null), "already exists");

        // drop column
        expectRejected("drop",
                () -> table.dropColumn("v"), "DROP_COLUMN rejected");

        // rename column
        expectRejected("rename",
                () -> table.renameColumn("v", "value2"), "RENAME_COLUMN rejected");

        // narrow/widen type
        expectRejected("type change",
                () -> table.changeColumnType("id", DataType.STRING), "CHANGE_TYPE rejected");

        TableSchema after = table.currentSchema();
        assertEquals(1, after.version(), "no incompatible change may advance the schema version");
        LOG.info(() -> "ALL-INCOMPATIBLE-CHANGES-REJECTED schema stays v" + after.version()
                + " fields=" + after.fields().stream().map(FieldDef::name).toList());
    }

    private void expectRejected(String label, Runnable op, String messageFragment) {
        IncompatibleSchemaException ex = assertThrows(IncompatibleSchemaException.class, op::run);
        assertTrue(ex.getMessage().contains(messageFragment),
                label + " -> unexpected message: " + ex.getMessage());
        LOG.info(() -> label + " -> REJECTED BASIS=" + ex.getMessage());
    }
}
