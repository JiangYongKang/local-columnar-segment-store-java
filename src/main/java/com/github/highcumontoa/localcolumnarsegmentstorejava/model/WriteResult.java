package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

import java.util.List;

/**
 * Result of a batch write.
 *
 * <p>Every input record is either committed exactly once ({@link #acceptedCount()})
 * or appears in {@link #rejected()} with a distinguishable reason. Records are
 * never silently dropped. {@code snapshotVersion} is the newly published
 * snapshot; when the whole batch is rejected it is the snapshot version the
 * validation ran against and no new manifest was published.
 */
public record WriteResult(
        long snapshotVersion,
        int acceptedCount,
        List<RejectedRecord> rejected
) {

    public WriteResult {
        rejected = List.copyOf(rejected);
    }

    public static WriteResult committed(long snapshotVersion, int acceptedCount,
                                       List<RejectedRecord> rejected) {
        return new WriteResult(snapshotVersion, acceptedCount, rejected);
    }

    public static WriteResult nothing(long snapshotVersion, List<RejectedRecord> rejected) {
        return new WriteResult(snapshotVersion, 0, rejected);
    }

    public int rejectedCount() {
        return rejected.size();
    }
}
