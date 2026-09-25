package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentFiles;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.IncompatibleSchemaException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.DataType;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.FieldDef;
import com.github.highcumontoa.localcolumnarsegmentstorejava.model.TableSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Persists every schema version as {@code meta/schema-v{N}.json} and enforces
 * the compatibility rules for schema evolution.
 *
 * <p>Compatibility contract (only additive evolution is allowed, so query
 * results against old data remain reproducible forever):
 * <ol>
 *   <li>adding a column is allowed only when it is nullable <em>or</em> carries
 *       a type-correct default value — that default is the stable value seen by
 *       all historical rows;</li>
 *   <li>dropping, renaming, reordering semantics or narrowing/changing the type
 *       of any existing column is always rejected with a distinguishable
 *       {@link IncompatibleSchemaException};</li>
 *   <li>the partition/key flags and partition bounds are immutable.</li>
 * </ol>
 */
public final class SchemaStore {

    /** Column names accepted on the wire and used verbatim as file names. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Path metaDir;
    private final ObjectMapper mapper;

    public SchemaStore(Path tableDir, ObjectMapper mapper) {
        this.metaDir = tableDir.resolve(SegmentFiles.metaDir());
        this.mapper = mapper;
    }

    /** Persist the first schema version (table creation). */
    public void initialize(TableSchema schema) {
        try {
            Files.createDirectories(metaDir);
        } catch (IOException e) {
            throw new StoreException("cannot create meta dir " + metaDir, e);
        }
        for (FieldDef f : schema.fields()) {
            validateName(f.name());
        }
        writeAtomically(schema);
    }

    /** Load a specific schema version from disk. */
    public TableSchema load(int version) {
        Path file = metaDir.resolve(SegmentFiles.schemaFileName(version));
        if (!Files.exists(file)) {
            throw new StoreException("schema version " + version + " does not exist");
        }
        try {
            return mapper.readValue(file.toFile(), TableSchema.class);
        } catch (IOException e) {
            throw new StoreException("cannot read " + file, e);
        }
    }

    /** All versions present on disk, ascending. */
    public List<Integer> availableVersions() {
        List<Integer> versions = new ArrayList<>();
        try (var paths = Files.list(metaDir)) {
            paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("schema-v") && n.endsWith(".json")
                            && !n.endsWith(".json.tmp"))
                    .forEach(n -> versions.add(Integer.parseInt(
                            n.substring("schema-v".length(), n.length() - ".json".length()))));
        } catch (IOException e) {
            throw new StoreException("cannot list schemas in " + metaDir, e);
        }
        versions.sort(Integer::compareTo);
        return versions;
    }

    /**
     * Add one column and persist a new schema version.
     *
     * @throws IncompatibleSchemaException with a specific reason on any violation
     */
    public TableSchema addColumn(TableSchema current, String name, DataType type,
                                 boolean nullable, Object defaultValue) {
        validateName(name);
        if (current.field(name) != null) {
            throw new IncompatibleSchemaException("ADD_COLUMN rejected: column '" + name
                    + "' already exists (renaming/redefining an existing column is not allowed)");
        }
        if (!nullable && defaultValue == null) {
            throw new IncompatibleSchemaException("ADD_COLUMN rejected: new column '" + name
                    + "' must be nullable or declare a default value, otherwise historical rows"
                    + " could not be given a stable value");
        }
        if (defaultValue != null && !com.github.highcumontoa.localcolumnarsegmentstorejava.model.Values
                .isAssignable(type, defaultValue)) {
            throw new IncompatibleSchemaException("ADD_COLUMN rejected: default value for '" + name
                    + "' does not match type " + type);
        }
        List<FieldDef> fields = new ArrayList<>(current.fields());
        fields.add(new FieldDef(name, type, nullable, false, false, defaultValue));
        TableSchema next = new TableSchema(current.tableName(), current.version() + 1, fields,
                current.partitionColumn(), current.lowerBound(), current.upperBound());
        writeAtomically(next);
        return next;
    }

    /** Always rejected: dropping a column would make historical reads ambiguous. */
    public TableSchema dropColumn(TableSchema current, String name) {
        if (current.field(name) == null) {
            throw new IncompatibleSchemaException("DROP_COLUMN rejected: column '" + name + "' does not exist");
        }
        throw new IncompatibleSchemaException("DROP_COLUMN rejected: dropping column '" + name
                + "' is incompatible (historical segments still contain it); add a new column instead");
    }

    /** Always rejected: rename cannot be distinguished from drop+add and breaks reproducibility. */
    public TableSchema renameColumn(TableSchema current, String oldName, String newName) {
        validateName(newName);
        if (current.field(oldName) == null) {
            throw new IncompatibleSchemaException("RENAME_COLUMN rejected: column '" + oldName + "' does not exist");
        }
        throw new IncompatibleSchemaException("RENAME_COLUMN rejected: renaming '" + oldName + "' -> '"
                + newName + "' is incompatible (column identity is the name)");
    }

    /** Always rejected: type changes may widen incorrectly or narrow away data. */
    public TableSchema changeColumnType(TableSchema current, String name, DataType newType) {
        FieldDef fd = current.field(name);
        if (fd == null) {
            throw new IncompatibleSchemaException("CHANGE_TYPE rejected: column '" + name + "' does not exist");
        }
        if (fd.type() == newType) {
            throw new IncompatibleSchemaException("CHANGE_TYPE rejected: column '" + name + "' already has type " + newType);
        }
        throw new IncompatibleSchemaException("CHANGE_TYPE rejected: changing type of '" + name + "' from "
                + fd.type() + " to " + newType + " is incompatible; historical values cannot be reinterpreted");
    }

    private void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IncompatibleSchemaException("illegal column name: " + name
                    + " (must match [A-Za-z_][A-Za-z0-9_]*)");
        }
    }

    private void writeAtomically(TableSchema schema) {
        Path target = metaDir.resolve(SegmentFiles.schemaFileName(schema.version()));
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), schema);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StoreException("cannot persist schema v" + schema.version(), e);
        }
    }
}
