package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

/** Distinguishable reasons why a record was rejected at write time. */
public enum RejectReason {
    MISSING_FIELD,
    TYPE_MISMATCH,
    PARTITION_VIOLATION,
    KEY_CONFLICT
}
