package com.github.highcumontoa.localcolumnarsegmentstorejava.api;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ConcurrentCommitException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.IncompatibleSchemaChangeException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Schema;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.WriteResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API for the local columnar store. */
@RestController
@RequestMapping("/tables")
public class StoreController {

    private final StoreService service;

    public StoreController(StoreService service) {
        this.service = service;
    }

    public record ColumnSpec(String name, String type) {
    }

    public record CreateTableRequest(List<ColumnSpec> columns, String keyColumn, String partitionColumn) {
    }

    public record WriteRequest(String partition, List<Map<String, Object>> records) {
    }

    public record AddColumnRequest(String name, String type, Object defaultValue) {
    }

    @PostMapping("/{table}")
    public ResponseEntity<?> createTable(@PathVariable String table, @RequestBody CreateTableRequest request)
            throws IOException {
        List<ColumnDef> columns = new ArrayList<>();
        for (ColumnSpec spec : request.columns()) {
            columns.add(new ColumnDef(spec.name(), ColumnType.valueOf(spec.type().toUpperCase())));
        }
        Schema schema = new Schema(1, columns, request.keyColumn(), request.partitionColumn(), Map.of());
        service.createTable(table, schema);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("table", table, "schemaVersion", 1));
    }

    @PostMapping("/{table}/write")
    public WriteResult write(@PathVariable String table, @RequestBody WriteRequest request) throws IOException {
        return service.write(table, request.partition(), request.records());
    }

    @GetMapping("/{table}/query")
    public List<Map<String, Object>> query(@PathVariable String table, @RequestParam String partition,
                                           @RequestParam(required = false) Long version) throws IOException {
        return service.query(table, partition, version);
    }

    @PostMapping("/{table}/schema/columns")
    public Schema addColumn(@PathVariable String table, @RequestBody AddColumnRequest request) throws IOException {
        return service.addColumn(table,
                new ColumnDef(request.name(), ColumnType.valueOf(request.type().toUpperCase())),
                request.defaultValue());
    }

    @PostMapping("/{table}/compact")
    public Map<String, Object> compact(@PathVariable String table, @RequestParam String partition)
            throws IOException {
        long version = service.compact(table, partition);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("table", table);
        body.put("partition", partition);
        body.put("snapshotVersion", version);
        return body;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IncompatibleSchemaChangeException.class)
    public ResponseEntity<?> incompatible(IncompatibleSchemaChangeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "INCOMPATIBLE_SCHEMA_CHANGE", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ConcurrentCommitException.class)
    public ResponseEntity<?> conflict(ConcurrentCommitException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONCURRENT_COMMIT", "message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(StoreException.class)
    public ResponseEntity<?> storeError(StoreException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "STORE_ERROR", "message", e.getMessage()));
    }
}
