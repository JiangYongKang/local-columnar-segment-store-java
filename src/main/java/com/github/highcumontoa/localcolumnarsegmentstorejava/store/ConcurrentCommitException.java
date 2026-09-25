package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

/** Thrown when a commit loses the race against another writer/compactor; never silently overwritten. */
public class ConcurrentCommitException extends StoreException {
    public ConcurrentCommitException(String message) {
        super(message);
    }
}
