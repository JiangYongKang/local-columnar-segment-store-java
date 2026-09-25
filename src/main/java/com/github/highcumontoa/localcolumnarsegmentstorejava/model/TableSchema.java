package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.IncompatibleSchemaException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable schema of one table at a specific schema version.
 *
 * <p>Validation rules enforced at construction:
 * <ul>
 *   <li>field names unique and non-empty;</li>
 *   <li>at least one key column;</li>
 *   <li>at most one partition column, and it must be {@link DataType#LONG};</li>
 *   <li>partition bounds (if both present) satisfy lower &lt;= upper;</li>
 *   <li>declared defaults match the declared type.</li>
 * </ul>
 */
public record TableSchema(
        String tableName,
        int version,
        List<FieldDef> fields,
        String partitionColumn,
        Long lowerBound,
        Long upperBound
) {

    public TableSchema {
        Objects.requireNonNull(tableName, "tableName");
        fields = List.copyOf(fields);
        validate(tableName, fields, partitionColumn, lowerBound, upperBound);
    }

    private static void validate(String tableName, List<FieldDef> fields,
                                 String partitionColumn, Long lowerBound, Long upperBound) {
        if (fields.isEmpty()) {
            throw new IncompatibleSchemaException("table " + tableName + ": schema must declare at least one field");
        }
        List<String> names = new ArrayList<>();
        boolean hasKey = false;
        boolean hasPartition = false;
        for (FieldDef f : fields) {
            if (f.name() == null || f.name().isBlank()) {
                throw new IncompatibleSchemaException("table " + tableName + ": empty field name");
            }
            if (names.contains(f.name())) {
                throw new IncompatibleSchemaException("table " + tableName + ": duplicate field " + f.name());
            }
            names.add(f.name());
            if (f.key()) {
                hasKey = true;
            }
            if (f.partition()) {
                hasPartition = true;
            }
            if (f.defaultValue() != null && !Values.isAssignable(f.type(), f.defaultValue())) {
                throw new IncompatibleSchemaException("table " + tableName + ": default for field " + f.name()
                        + " has wrong type, expected " + f.type() + " but got "
                        + f.defaultValue().getClass().getSimpleName());
            }
        }
        if (!hasKey) {
            throw new IncompatibleSchemaException("table " + tableName + ": at least one key column is required");
        }
        if (partitionColumn != null) {
            if (!hasPartition) {
                throw new IncompatibleSchemaException("table " + tableName + ": partition column "
                        + partitionColumn + " is not marked partition=true");
            }
            FieldDef pc = fields.stream().filter(f -> f.name().equals(partitionColumn)).findFirst().orElseThrow();
            if (pc.type() != DataType.LONG) {
                throw new IncompatibleSchemaException("table " + tableName + ": partition column must be LONG");
            }
        }
        if (lowerBound != null && upperBound != null && lowerBound > upperBound) {
            throw new IncompatibleSchemaException("table " + tableName
                    + ": partition lowerBound must be <= upperBound");
        }
    }

    public FieldDef field(String name) {
        for (FieldDef f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public List<FieldDef> keyFields() {
        List<FieldDef> keys = new ArrayList<>();
        for (FieldDef f : fields) {
            if (f.key()) {
                keys.add(f);
            }
        }
        return Collections.unmodifiableList(keys);
    }

    public FieldDef partitionField() {
        return partitionColumn == null ? null : field(partitionColumn);
    }
}
