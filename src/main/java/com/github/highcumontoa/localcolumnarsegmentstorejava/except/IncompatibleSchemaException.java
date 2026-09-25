package com.github.highcumontoa.localcolumnarsegmentstorejava.except;

/** Thrown when a proposed schema change is not forward compatible. */
public class IncompatibleSchemaException extends StoreException {
    public IncompatibleSchemaException(String message) { super(message); }
}
