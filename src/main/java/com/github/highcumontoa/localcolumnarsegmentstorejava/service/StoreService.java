package com.github.highcumontoa.localcolumnarsegmentstorejava.service;

import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.Row;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.SnapshotInfo;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.WriteResult;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.Manifest;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import com.github.highcumontoa.localcolumnarsegmentstorejava.store.TableStore;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application-facing facade over the single {@link StoreCatalog}. */
@Service
public class StoreService {

    private final StoreCatalog catalog;

    public StoreService(StoreCatalog catalog) {
        this.catalog = catalog;
    }

    public List<String> tableNames() {
        return catalog.tableNames();
    }

    public TableStoreApi createTable(String tableName, List<FieldDef> fields,
                                     String partitionColumn, Long lowerBound, Long upperBound) {
        return new TableStoreApi(catalog.createTable(tableName, fields, partitionColumn, lowerBound, upperBound));
    }

    public TableStoreApi table(String name) {
        return new TableStoreApi(catalog.getTable(name));
    }

    /** Immutable handle exposing one table's operations to the web layer. */
    public static final class TableStoreApi {
        private final TableStore store;

        TableStoreApi(TableStore store) {
            this.store = store;
        }

        public TableSchema schema() {
            return store.currentSchema();
        }

        public SnapshotInfo snapshot() {
            return store.snapshotInfo();
        }

        public WriteResult write(List<Row> rows) {
            return store.write(rows);
        }

        public List<Row> query(Long low, Long high, Long eq, List<String> columns, Integer schemaVersion) {
            return store.query(low, high, eq, columns, schemaVersion);
        }

        public Manifest addColumn(String name, DataType type, boolean nullable, Object defaultValue) {
            return store.addColumn(name, type, nullable, defaultValue);
        }

        public Manifest dropColumn(String name) {
            return store.dropColumn(name);
        }

        public Manifest renameColumn(String oldName, String newName) {
            return store.renameColumn(oldName, newName);
        }

        public Manifest changeColumnType(String name, DataType type) {
            return store.changeColumnType(name, type);
        }

        public Manifest compact() {
            return store.compact();
        }
    }
}
