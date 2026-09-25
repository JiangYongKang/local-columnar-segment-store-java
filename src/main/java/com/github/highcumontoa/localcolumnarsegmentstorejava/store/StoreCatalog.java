package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.TableAlreadyExistsException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.TableNotFoundException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the data directory and all open tables.
 *
 * <p>Opening the catalog recovers every table found on disk (each
 * {@link TableStore} constructor runs crash recovery). Table creation
 * atomically materializes the directory; tables are cached per JVM so that all
 * callers share one consistent in-memory view and one set of locks.
 */
public final class StoreCatalog {

    private static final int DEFAULT_COMPACT_THRESHOLD = 4;
    private static final int DEFAULT_MANIFEST_HISTORY_KEEP = 3;

    private final Path dataDir;
    private final int compactThreshold;
    private final int manifestHistoryKeep;
    private final Map<String, TableStore> tables = new ConcurrentHashMap<>();

    public StoreCatalog(Path dataDir) {
        this(dataDir, DEFAULT_COMPACT_THRESHOLD, DEFAULT_MANIFEST_HISTORY_KEEP);
    }

    public StoreCatalog(Path dataDir, int compactThreshold, int manifestHistoryKeep) {
        this.dataDir = dataDir;
        this.compactThreshold = compactThreshold;
        this.manifestHistoryKeep = manifestHistoryKeep;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new StoreException("cannot create data dir " + dataDir, e);
        }
        recoverAll();
    }

    private void recoverAll() {
        try (var paths = Files.list(dataDir)) {
            paths.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                if (Files.isDirectory(dir.resolve("meta"))) {
                    LOG.info("opening/recovering table " + name);
                    tables.put(name, TableStore.open(dir, compactThreshold, manifestHistoryKeep));
                }
            });
        } catch (IOException e) {
            throw new StoreException("cannot scan data dir " + dataDir, e);
        }
    }

    public List<String> tableNames() {
        return tables.keySet().stream().sorted().toList();
    }

    public TableStore createTable(String tableName, List<FieldDef> fields,
                                  String partitionColumn, Long lowerBound, Long upperBound) {
        validateTableName(tableName);
        Path tableDir = dataDir.resolve(tableName);
        // Atomic existence barrier: two concurrent creates cannot both proceed.
        try {
            Files.createDirectory(tableDir);
        } catch (IOException e) {
            if (Files.isDirectory(tableDir)) {
                throw new TableAlreadyExistsException(tableName);
            }
            throw new StoreException("cannot create table " + tableName, e);
        }
        TableSchema schema = new TableSchema(tableName, 1, fields,
                partitionColumn, lowerBound, upperBound);
        TableStore store = TableStore.create(tableDir, schema, compactThreshold, manifestHistoryKeep);
        tables.put(tableName, store);
        return store;
    }

    public TableStore getTable(String tableName) {
        TableStore store = tables.get(tableName);
        if (store == null) {
            throw new TableNotFoundException(tableName);
        }
        return store;
    }

    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName);
    }

    /** Drop a table and remove its files; returns false if it did not exist. */
    public boolean dropTable(String tableName) {
        TableStore store = tables.remove(tableName);
        if (store == null) {
            return false;
        }
        Path tableDir = dataDir.resolve(tableName);
        try (var walk = Files.walk(tableDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new StoreException("cannot delete " + p, e);
                }
            });
        } catch (IOException e) {
            throw new StoreException("cannot drop table " + tableName, e);
        }
        return true;
    }

    private static void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new StoreException("illegal table name: " + tableName
                    + " (must match [A-Za-z_][A-Za-z0-9_]*)");
        }
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(StoreCatalog.class.getName());
}
