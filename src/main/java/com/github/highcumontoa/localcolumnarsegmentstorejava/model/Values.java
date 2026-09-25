package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

import java.util.Map;

/**
 * Type checking and coercion rules between JSON-deserialized Java values and
 * the four supported {@link DataType}s.
 *
 * <p>Accepted representations (deliberately strict to keep query results
 * reproducible - no silent numeric widening between LONG and DOUBLE):
 * <ul>
 *   <li>LONG: {@link Long}, {@link Integer} (exact integers only)</li>
 *   <li>DOUBLE: {@link Double}, {@link Float} (exact decimals only)</li>
 *   <li>STRING: {@link String}</li>
 *   <li>BOOLEAN: {@link Boolean}</li>
 * </ul>
 */
public final class Values {

    private Values() {
    }

    /** Whether the given Java value can be stored as the given type. */
    public static boolean isAssignable(DataType type, Object value) {
        if (value == null) {
            return true;
        }
        return switch (type) {
            case LONG -> value instanceof Long || value instanceof Integer;
            case DOUBLE -> value instanceof Double || value instanceof Float;
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    /**
     * Coerce an externally supplied value to the canonical representation.
     *
     * @throws IllegalArgumentException if the value cannot be assigned to the type
     */
    public static Object coerce(DataType type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case LONG -> {
                if (value instanceof Long l) {
                    yield l;
                }
                if (value instanceof Integer i) {
                    yield i.longValue();
                }
                throw new IllegalArgumentException("expected LONG (integer), got "
                        + value.getClass().getSimpleName() + "(" + value + ")");
            }
            case DOUBLE -> {
                if (value instanceof Double d) {
                    yield d;
                }
                if (value instanceof Float f) {
                    yield f.doubleValue();
                }
                throw new IllegalArgumentException("expected DOUBLE, got "
                        + value.getClass().getSimpleName() + "(" + value + ")");
            }
            case STRING -> {
                if (value instanceof String s) {
                    yield s;
                }
                throw new IllegalArgumentException("expected STRING, got "
                        + value.getClass().getSimpleName());
            }
            case BOOLEAN -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                throw new IllegalArgumentException("expected BOOLEAN, got "
                        + value.getClass().getSimpleName());
            }
        };
    }

    /** Composite key string for duplicate detection within a batch / table. */
    public static String compositeKey(TableSchema schema, Map<String, Object> coercedValues) {
        StringBuilder sb = new StringBuilder();
        for (FieldDef key : schema.keyFields()) {
            if (!sb.isEmpty()) {
                sb.append((char) 1);
            }
            sb.append(coercedValues.get(key.name()));
        }
        return sb.toString();
    }
}
