package com.github.highcumontoa.localcolumnarsegmentstorejava.web;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;

import java.util.List;
import java.util.Map;

/** Request/response payloads for the REST API (package scoped to the web layer). */
final class StoreDtos {

    private StoreDtos() {
    }

    record FieldDto(String name, DataType type, boolean nullable, boolean key,
                    boolean partition, Object defaultValue) {
    }

    record CreateTableRequest(String tableName, List<FieldDto> fields,
                              String partitionColumn, Long lowerBound, Long upperBound) {
    }

    /** Each element is one record: column name -&gt; JSON value. */
    record WriteRequest(List<Map<String, Object>> rows) {
    }

    record AddColumnRequest(String name, DataType type, boolean nullable, Object defaultValue) {
    }

    record RenameColumnRequest(String oldName, String newName) {
    }

    record ChangeTypeRequest(String name, DataType newType) {
    }

    record DropColumnRequest(String name) {
    }
}
