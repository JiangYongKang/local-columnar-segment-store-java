package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

/** Thrown when a schema change is not backward compatible (drop/rename/type narrowing). */
public class IncompatibleSchemaChangeException extends StoreException {
    public IncompatibleSchemaChangeException(String message) {
        super(message);
    }
}
