package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

import java.util.Map;

/**
 * @param index  zero-based position of the record in the submitted batch
 * @param row    the rejected payload
 * @param reason machine-readable rejection category
 * @param detail human-readable explanation (the decision basis)
 */
public record RejectedRecord(
        int index,
        Map<String, Object> row,
        RejectReason reason,
        String detail
) {
}
