package com.github.highcumontoa.localcolumnarsegmentstorejava.web;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.service.StoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface of the local columnar segment store.
 *
 * <ul>
 *   <li>PUT  /api/tables/{name}            create table</li>
 *   <li>GET  /api/tables                   list tables</li>
 *   <li>GET  /api/tables/{name}/snapshot   current snapshot info</li>
 *   <li>POST /api/tables/{name}/rows       batch write (per-record rejections)</li>
 *   <li>GET  /api/tables/{name}/rows       query with partition bounds</li>
 *   <li>POST /api/tables/{name}/columns    add column (only compatible change)</li>
 *   <li>POST /api/tables/{name}/columns/drop   rejected by design</li>
 *   <li>POST /api/tables/{name}/columns/rename rejected by design</li>
 *   <li>POST /api/tables/{name}/columns/type   rejected by design</li>
 *   <li>POST /api/tables/{name}/compact    run compaction</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class StoreController {

    private final StoreService service;

    public StoreController(StoreService service) {
        this.service = service;
    }

    @GetMapping("/tables")
    public Map<String, Object> list() {
        return Map.of("tables", service.tableNames());
    }

    @org.springframework.web.bind.annotation.PutMapping("/tables/{name}")
    public Map<String, Object> create(@PathVariable String name,
                                      @RequestBody StoreDtos.CreateTableRequest req) {
        List<FieldDef> fields = req.fields() == null ? List.of() : req.fields().stream()
                .map(d -> new FieldDef(d.name(), d.type(), d.nullable(), d.key(), d.partition(), d.defaultValue()))
                .toList();
        String tableName = req.tableName() != null ? req.tableName() : name;
        var api = service.createTable(tableName, fields, req.partitionColumn(),
                req.lowerBound(), req.upperBound());
        return Map.of("snapshot", api.snapshot(), "schema", api.schema());
    }

    @GetMapping("/tables/{name}/snapshot")
    public Map<String, Object> snapshot(@PathVariable String name) {
        var api = service.table(name);
        return Map.of("snapshot", api.snapshot(), "schema", api.schema());
    }

    @PostMapping("/tables/{name}/rows")
    public Map<String, Object> write(@PathVariable String name,
                                     @RequestBody StoreDtos.WriteRequest req) {
        List<Row> rows = req.rows() == null ? List.of() : req.rows().stream().map(Row::of).toList();
        var result = service.table(name).write(rows);
        return Map.of(
                "snapshotVersion", result.snapshotVersion(),
                "accepted", result.acceptedCount(),
                "rejectedCount", result.rejectedCount(),
                "rejected", result.rejected());
    }

    @GetMapping("/tables/{name}/rows")
    public Map<String, Object> query(@PathVariable String name,
                                     @RequestParam(required = false) Long low,
                                     @RequestParam(required = false) Long high,
                                     @RequestParam(required = false) Long eq,
                                     @RequestParam(required = false) List<String> columns,
                                     @RequestParam(required = false) Integer schemaVersion) {
        var rows = service.table(name).query(low, high, eq, columns, schemaVersion);
        return Map.of("count", rows.size(), "rows", rows);
    }

    @PostMapping("/tables/{name}/columns")
    public Map<String, Object> addColumn(@PathVariable String name,
                                         @RequestBody StoreDtos.AddColumnRequest req) {
        var manifest = service.table(name).addColumn(req.name(), req.type(),
                req.nullable(), req.defaultValue());
        return Map.of("manifest", manifest);
    }

    @PostMapping("/tables/{name}/columns/drop")
    public Map<String, Object> dropColumn(@PathVariable String name,
                                          @RequestBody StoreDtos.DropColumnRequest req) {
        var manifest = service.table(name).dropColumn(req.name());
        return Map.of("manifest", manifest);
    }

    @PostMapping("/tables/{name}/columns/rename")
    public Map<String, Object> renameColumn(@PathVariable String name,
                                            @RequestBody StoreDtos.RenameColumnRequest req) {
        var manifest = service.table(name).renameColumn(req.oldName(), req.newName());
        return Map.of("manifest", manifest);
    }

    @PostMapping("/tables/{name}/columns/type")
    public Map<String, Object> changeType(@PathVariable String name,
                                          @RequestBody StoreDtos.ChangeTypeRequest req) {
        var manifest = service.table(name).changeColumnType(req.name(), req.newType());
        return Map.of("manifest", manifest);
    }

    @PostMapping("/tables/{name}/compact")
    public Map<String, Object> compact(@PathVariable String name) {
        var manifest = service.table(name).compact();
        return Map.of("manifest", manifest);
    }
}
