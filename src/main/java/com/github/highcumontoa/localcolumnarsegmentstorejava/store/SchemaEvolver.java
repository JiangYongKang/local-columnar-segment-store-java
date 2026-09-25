package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Schema compatibility rules:
 *  - ADD COLUMN with a non-null default of matching type: compatible.
 *    Historical segments lack the column and are read back with the default,
 *    so pre/post-evolution queries stay reproducible.
 *  - DROP / RENAME / TYPE CHANGE (narrowing or widening): incompatible,
 *    always rejected with {@link IncompatibleSchemaChangeException}.
 */
public final class SchemaEvolver {

    public Schema addColumn(Schema schema, ColumnDef def, Object defaultValue) {
        if (def == null || def.name() == null || def.name().isBlank()) {
            throw new IncompatibleSchemaChangeException("new column must have a non-blank name");
        }
        if (schema.hasColumn(def.name())) {
            throw new IncompatibleSchemaChangeException("column already exists: " + def.name());
        }
        if (defaultValue == null) {
            throw new IncompatibleSchemaChangeException(
                    "new column '" + def.name() + "' requires a non-null default for stable historical reads");
        }
        if (!typeMatches(def.type(), defaultValue)) {
            throw new IncompatibleSchemaChangeException(
                    "default value of column '" + def.name() + "' does not match type " + def.type());
        }
        var columns = new ArrayList<>(schema.columns());
        columns.add(def);
        var defaults = new LinkedHashMap<>(schema.defaults());
        defaults.put(def.name(), defaultValue);
        return new Schema(schema.version() + 1, columns, schema.keyColumn(), schema.partitionColumn(), defaults);
    }

    public Schema dropColumn(Schema schema, String name) {
        throw new IncompatibleSchemaChangeException(
                "DROP COLUMN '" + name + "' is incompatible: historical segments still contain it");
    }

    public Schema renameColumn(Schema schema, String oldName, String newName) {
        throw new IncompatibleSchemaChangeException(
                "RENAME COLUMN '" + oldName + "' -> '" + newName + "' is incompatible: historical segments keep the old name");
    }

    public Schema changeType(Schema schema, String name, ColumnType newType) {
        ColumnDef existing = schema.column(name);
        String detail = existing == null ? "unknown column" : existing.type() + " -> " + newType;
        throw new IncompatibleSchemaChangeException(
                "TYPE CHANGE of '" + name + "' (" + detail + ") is incompatible: no narrowing or widening is allowed");
    }

    static boolean typeMatches(ColumnType type, Object value) {
        return switch (type) {
            case INT, LONG, DOUBLE -> value instanceof Number;
            case STRING -> value instanceof String;
            case BOOL -> value instanceof Boolean;
        };
    }
}
