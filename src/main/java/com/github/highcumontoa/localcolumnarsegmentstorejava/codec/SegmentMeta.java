package com.github.highcumontoa.localcolumnarsegmentstorejava.codec;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;

import java.util.Map;

/**
 * Metadata of an immutable segment file group (segment dir).
 *
 * @param segmentId     unique monotonically increasing id
 * @param schemaVersion schema version used to interpret the columns
 * @param columns       ordered materialized column names
 * @param columnTypes   column name -&gt; stored type (frozen at write time)
 * @param rowCount      number of rows
 * @param minPartition  minimum partition value (inclusive), or null
 * @param maxPartition  maximum partition value (inclusive), or null
 */
public record SegmentMeta(
        long segmentId,
        int schemaVersion,
        java.util.List<String> columns,
        Map<String, DataType> columnTypes,
        int rowCount,
        Long minPartition,
        Long maxPartition
) {
}
