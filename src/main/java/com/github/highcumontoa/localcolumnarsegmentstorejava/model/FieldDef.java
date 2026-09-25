package com.github.highcumontoa.localcolumnarsegmentstorejava.model;

/**
 * Definition of one column.
 *
 * @param name         column name
 * @param type         declared type
 * @param nullable     whether NULL is allowed
 * @param key          whether the column is part of the (composite) primary key
 * @param partition    whether this is the partition column
 * @param defaultValue default applied to historical rows when the column is added later
 */
public record FieldDef(
        String name,
        DataType type,
        boolean nullable,
        boolean key,
        boolean partition,
        Object defaultValue
) {
}
