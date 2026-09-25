package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentMeta;
import java.util.List;

/**
 * Immutable catalog of one committed snapshot.
 *
 * @param version       monotonically increasing snapshot version
 * @param schemaVersion schema version active at publish time
 * @param segments      segments visible in this snapshot
 * @param rowCount      total logical row count across segments
 * @param operation     WRITE | COMPACT | RECOVER
 * @param timestampMs   publish wall-clock timestamp
 */
public record Manifest(
        long version,
        int schemaVersion,
        List<SegmentMeta> segments,
        long rowCount,
        String operation,
        long timestampMs
) {
}
