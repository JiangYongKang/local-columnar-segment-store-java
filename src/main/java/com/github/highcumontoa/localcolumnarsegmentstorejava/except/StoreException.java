package com.github.highcumontoa.localcolumnarsegmentstorejava.except;

/** Base class for all store errors. */
public class StoreException extends RuntimeException {
    public StoreException(String message) { super(message); }
    public StoreException(String message, Throwable cause) { super(message, cause); }
}
