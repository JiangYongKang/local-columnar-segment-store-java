package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.localcolumnarsegmentstorejava.codec.SegmentFiles;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.StoreException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Reads and atomically publishes {@link Manifest}s.
 *
 * <p>Snapshot publication is a two-step atomic switch:
 * <ol>
 *   <li>{@code manifest-v{N}.json.tmp} is fully written, fsynced and renamed
 *       atomically to {@code manifest-v{N}.json}; after this the snapshot is
 *       durable but not yet visible;</li>
 *   <li>{@code CURRENT.tmp} is written and atomically renamed to
 *       {@code CURRENT}; only this rename makes the new snapshot visible.</li>
 * </ol>
 * Readers always resolve snapshots through {@code CURRENT}, so they observe
 * either the previous complete snapshot or the next complete snapshot, never a
 * torn intermediate state. The two {@link CrashHooks} injection points let
 * tests kill the process between these steps deterministically.
 */
public final class ManifestStore {

    private final Path metaDir;
    private final ObjectMapper mapper;
    private volatile CrashHooks crashHooks = new CrashHooks();

    public ManifestStore(Path tableDir, ObjectMapper mapper) {
        this.metaDir = tableDir.resolve(SegmentFiles.metaDir());
        this.mapper = mapper;
    }

    public void setCrashHooks(CrashHooks hooks) {
        this.crashHooks = hooks == null ? new CrashHooks() : hooks;
    }

    /** The unique visible snapshot, or null when the table has no commit yet. */
    public Manifest current() {
        Path currentFile = metaDir.resolve(SegmentFiles.CURRENT);
        if (!Files.exists(currentFile)) {
            return null;
        }
        try {
            String name = Files.readString(currentFile, StandardCharsets.UTF_8).trim();
            Path manifestFile = metaDir.resolve(name);
            if (!Files.exists(manifestFile)) {
                throw new StoreException("CURRENT points to missing manifest " + name);
            }
            return mapper.readValue(manifestFile.toFile(), Manifest.class);
        } catch (IOException e) {
            throw new StoreException("cannot read CURRENT in " + metaDir, e);
        }
    }

    public Manifest load(long version) {
        Path file = metaDir.resolve(SegmentFiles.manifestFileName(version));
        try {
            return mapper.readValue(file.toFile(), Manifest.class);
        } catch (IOException e) {
            throw new StoreException("cannot read manifest v" + version, e);
        }
    }

    /** Snapshot versions whose manifest file exists on disk, ascending. */
    public List<Long> listManifestVersions() {
        try (var paths = Files.list(metaDir)) {
            return paths
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("manifest-v") && n.endsWith(".json"))
                    .filter(n -> !n.endsWith(".json.tmp"))
                    .map(n -> Long.parseLong(
                            n.substring("manifest-v".length(), n.length() - ".json".length())))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new StoreException("cannot list manifests in " + metaDir, e);
        }
    }

    /**
     * Durably write the manifest file (step 1). After this returns the
     * manifest exists on disk but the old CURRENT still points elsewhere.
     */
    public void persistManifest(Manifest manifest) {
        Path target = metaDir.resolve(SegmentFiles.manifestFileName(manifest.version()));
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                out.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            }
            tryMoveAtomic(tmp, target);
        } catch (IOException e) {
            throw new StoreException("cannot persist manifest v" + manifest.version(), e);
        }
    }

    /**
     * Publish an already persisted manifest by atomically repointing CURRENT.
     * Crash hooks bracket the two publication steps so tests can simulate
     * "files durable, pointer not switched" and "pointer switched" states.
     */
    public void publish(Manifest manifest) {
        crashHooks.beforeManifestPublish(manifest);

        persistManifest(manifest);
        crashHooks.afterManifestPersisted(manifest);
        crashHooks.beforeCurrentRepoint(manifest);

        Path current = metaDir.resolve(SegmentFiles.CURRENT);
        Path tmp = current.resolveSibling(current.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, SegmentFiles.manifestFileName(manifest.version()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            tryMoveAtomic(tmp, current);
        } catch (IOException e) {
            throw new StoreException("cannot repoint CURRENT to v" + manifest.version(), e);
        }
    }

    /** Remove old manifest files beyond the newest {@code keep} that have a backup. */
    public void pruneHistory(int keep) {
        try (var paths = Files.list(metaDir)) {
            List<Path> files = paths
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("manifest-v") && n.endsWith(".json");
                    })
                    .sorted()
                    .toList();
            for (int i = 0; i < files.size() - keep; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            throw new StoreException("cannot prune manifest history", e);
        }
    }

    private static void tryMoveAtomic(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            // Some filesystems emulate via fallback; local ext4/tmpfs support ATOMIC_MOVE.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
