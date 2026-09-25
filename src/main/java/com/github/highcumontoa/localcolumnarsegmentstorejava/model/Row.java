package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One logical row: column name -&gt; value (Java representation of {@link DataType}).
 *
 * <p>The map is copied and insertion order preserved so that batch input order
 * (which determines per-record rejection indices) stays stable in logs and
 * error reports.
 */
public final class Row {

    private final Map<String, Object> values;

    private Row(Map<String, Object> values) {
        // unmodifiableMap (not Map.copyOf) because nullable columns legitimately
        // carry null values, which Map.copyOf rejects.
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Row of(Map<String, Object> values) {
        return new Row(new LinkedHashMap<>(values));
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public Map<String, Object> values() {
        return values;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static Row fromMap(Map<String, Object> values) {
        return new Row(new LinkedHashMap<>(values));
    }

    @Override
    public String toString() {
        return "Row" + values;
    }
}
