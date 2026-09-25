package com.github.highcumontoa.localcolumnarsegmentstorejava.tests;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared schema/fixture helpers for tests. */
final class TestSchemas {

    private TestSchemas() {
    }

    /** id (LONG, key), dt (STRING, partition), value (DOUBLE), name (STRING). */
    static Schema basicSchema() {
        return new Schema(1, List.of(
                new ColumnDef("id", ColumnType.LONG),
                new ColumnDef("dt", ColumnType.STRING),
                new ColumnDef("value", ColumnType.DOUBLE),
                new ColumnDef("name", ColumnType.STRING)),
                "id", "dt", Map.of());
    }

    static Map<String, Object> row(long id, String dt, double value, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("dt", dt);
        row.put("value", value);
        row.put("name", name);
        return row;
    }
}
