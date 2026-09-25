package com.github.highcumontoa.localcolumnarsegmentstorejava.store;

/**
 * Test-only crash injection points. Default hooks never fire, so production
 * behaviour is unaffected.
 *
 * <p>A hook throws {@link JvmCrashError}, an unchecked {@link Error} that is
 * deliberately <em>not</em> caught by store code, unwinding the operation the
 * same way a hard {@code kill -9} would. The test then reopens the table and
 * asserts recovery: on-disk state must already be consistent because every
 * visible state transition is an atomic file rename.
 */
public class CrashHooks {

    /** Fired after new segment files are finalized, before the manifest is persisted. */
    public void beforeManifestPublish(Manifest pending) {
        // no-op by default
    }

    /** Fired after the manifest file is durable, before CURRENT is repointed. */
    public void beforeCurrentRepoint(Manifest pending) {
        // no-op by default
    }

    /**
     * Fired after the manifest file has been atomically renamed into place but
     * before the CURRENT pointer is switched. Crashing here simulates a durable
     * new snapshot that is not yet referenced; recovery must deterministically
     * promote it as the unique consistent snapshot.
     */
    public void afterManifestPersisted(Manifest pending) {
        // no-op by default
    }

    /** Marker error simulating abrupt process termination. */
    public static final class JvmCrashError extends Error {
        public JvmCrashError(String where) {
            super("simulated JVM crash: " + where);
        }
    }
}
