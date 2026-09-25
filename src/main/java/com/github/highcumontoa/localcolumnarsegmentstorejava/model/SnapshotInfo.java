package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

/** Public view of the currently published snapshot. */
public record SnapshotInfo(
        String tableName,
        long version,
        int schemaVersion,
        int segmentCount,
        long rowCount
) {
}
