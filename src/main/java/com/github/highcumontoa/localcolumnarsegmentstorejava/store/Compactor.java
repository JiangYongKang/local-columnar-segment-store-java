package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.io.IOException;

/**
 * Background merge: reduces the number of segment files of a partition to one
 * while preserving the exact row-level result. Delegates the atomic commit to
 * {@link TableStore}; concurrent-write conflicts surface as
 * {@link ConcurrentCommitException}.
 */
public final class Compactor {

    private final TableStore store;

    public Compactor(TableStore store) {
        this.store = store;
    }

    /** Compact one partition; returns the resulting snapshot version. */
    public long compactPartition(String partition) throws IOException {
        return store.compact(partition);
    }
}
