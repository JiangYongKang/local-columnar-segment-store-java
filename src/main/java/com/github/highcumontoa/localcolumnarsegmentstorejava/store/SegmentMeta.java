package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

/** Metadata of one immutable segment file. */
public record SegmentMeta(String segmentId, String partition, long rowCount, long schemaVersion) {
    public String fileName() {
        return "seg-" + segmentId + ".col";
    }
}
