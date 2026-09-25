package com.github.highcumontoa.localcolumnarsegmentstorejava.except;

/** Thrown when a concurrent conflict must be surfaced instead of silently overwritten. */
public class ConcurrentConflictException extends StoreException {
    public ConcurrentConflictException(String message) { super(message); }
}
