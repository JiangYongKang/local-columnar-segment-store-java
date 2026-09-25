package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

/** Distinguishable rejection reasons for an invalid record. */
public enum RejectReason {
    MISSING_FIELD,
    TYPE_MISMATCH,
    PARTITION_VIOLATION,
    DUPLICATE_KEY
}
