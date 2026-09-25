package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.util.List;

/** An immutable committed view of a table: schema + visible segments. */
public record Snapshot(long version, Schema schema, List<SegmentMeta> segments) {
}
