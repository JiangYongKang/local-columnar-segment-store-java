package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.util.List;
import java.util.Map;

/**
 * Table schema: ordered columns, a primary key column and a partition column.
 * Column defaults are used when old segments lack a newly added column.
 */
public record Schema(long version, List<ColumnDef> columns, String keyColumn, String partitionColumn,
                     Map<String, Object> defaults) {

    public ColumnDef column(String name) {
        for (ColumnDef c : columns) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    public boolean hasColumn(String name) {
        return column(name) != null;
    }
}
