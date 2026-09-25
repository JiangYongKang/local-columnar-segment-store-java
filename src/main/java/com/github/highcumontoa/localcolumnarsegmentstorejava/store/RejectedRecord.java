package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import java.util.Map;

/** A record that was rejected, with a distinguishable reason. */
public record RejectedRecord(Map<String, Object> record, RejectReason reason, String detail) {
}
