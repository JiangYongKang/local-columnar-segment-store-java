package com.github.highcumontoa.localcolumnarsegmentstorejava.codec;

/**
 * Central on-disk naming convention for one table directory:
 * <pre>
 *   table/
 *     meta/
 *       schema-v{N}.json      every schema version ever committed
 *       manifest-v{N}.json    every published snapshot
 *       CURRENT               one line: manifest-v{N}.json
 *       next-seq              one long: next segment id to allocate
 *     segments/
 *       seg-{id}/             immutable segment directory
 *         meta.json           {@link SegmentMeta}
 *         col-{name}.bin      one column file per materialized column
 *       seg-{id}.tmp/         in-progress segments (orphans after crash)
 * </pre>
 */
public final class SegmentFiles {

    public static final String META_JSON = "meta.json";
    public static final String CURRENT = "CURRENT";
    public static final String NEXT_SEQ = "next-seq";

    private SegmentFiles() {
    }

    public static String segmentsDir() {
        return "segments";
    }

    public static String metaDir() {
        return "meta";
    }

    public static String segmentDirName(long id) {
        return "seg-" + id;
    }

    public static String tmpSegmentDirName(long id) {
        return "seg-" + id + ".tmp";
    }

    /**
     * Map a column name to its file name. Column names come from schema
     * definitions (alphanumeric + underscore validated elsewhere), so no
     * escaping is needed; the {@code col-} prefix / {@code .bin} suffix make
     * files self-describing.
     */
    public static String columnFileName(String columnName) {
        return "col-" + columnName + ".bin";
    }

    public static String manifestFileName(long version) {
        return "manifest-v" + version + ".json";
    }

    public static String schemaFileName(int version) {
        return "schema-v" + version + ".json";
    }
}
