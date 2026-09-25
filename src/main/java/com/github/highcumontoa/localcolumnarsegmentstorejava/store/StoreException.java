package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

/** Base runtime exception of the store. */
public class StoreException extends RuntimeException {
    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
