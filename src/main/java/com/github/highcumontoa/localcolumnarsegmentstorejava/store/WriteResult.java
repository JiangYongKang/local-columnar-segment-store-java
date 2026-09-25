package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.util.List;

/** Outcome of a write batch: snapshot version committed (or -1) plus per-record rejections. */
public record WriteResult(long snapshotVersion, int accepted, List<RejectedRecord> rejected) {
}
