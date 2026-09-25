package com.github.highcumontoa.localcolumnarsegmentstorejava.api;

import com.github.highcumontoa.localcolumnarsegmentstorejava.config.StoreProperties;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.ColumnDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Schema;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.WriteResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Spring service exposing the table store over the configured root directory. */
@Service
public class StoreService {

    private final Path rootDir;

    public StoreService(StoreProperties properties) {
        this.rootDir = Path.of(properties.getRootDir());
    }

    public void createTable(String table, Schema schema) throws IOException {
        try (TableStore store = open(table)) {
            store.create(schema);
        }
    }

    public WriteResult write(String table, String partition, List<Map<String, Object>> records) throws IOException {
        try (TableStore store = open(table)) {
            return store.write(partition, records);
        }
    }

    public List<Map<String, Object>> query(String table, String partition, Long version) throws IOException {
        try (TableStore store = open(table)) {
            return version == null ? store.query(partition) : store.query(partition, version);
        }
    }

    public Schema addColumn(String table, ColumnDef def, Object defaultValue) throws IOException {
        try (TableStore store = open(table)) {
            return store.evolveAddColumn(def, defaultValue);
        }
    }

    public long compact(String table, String partition) throws IOException {
        try (TableStore store = open(table)) {
            return store.compact(partition);
        }
    }

    TableStore open(String table) throws IOException {
        return new TableStore(rootDir.resolve(table));
    }
}
