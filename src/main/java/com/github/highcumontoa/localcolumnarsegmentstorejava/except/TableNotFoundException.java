package com.github.highcumontoa.localcolumnarsegmentstorejava.except;

/** Thrown when a referenced table does not exist. */
public class TableNotFoundException extends StoreException {
    public TableNotFoundException(String tableName) { super(tableName); }
}
