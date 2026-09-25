package com.github.highcumontoa.localcolumnarsegmentstorejava.except;

/** Thrown when creating a table that already exists. */
public class TableAlreadyExistsException extends StoreException {
    public TableAlreadyExistsException(String tableName) { super(tableName); }
}
