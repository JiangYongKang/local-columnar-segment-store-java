package com.github.highcumontoa.localcolumnarsegmentstorejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

/** Configurable options (prefix: store.*). */
@ConfigurationProperties(prefix = "store")
public class StoreProperties {

    private Path dataDir = Path.of("data");
    private int compactSegmentThreshold = 4;
    private int manifestHistoryKeep = 3;

    public Path getDataDir() { return dataDir; }
    public void setDataDir(Path dataDir) { this.dataDir = dataDir; }

    public int getCompactSegmentThreshold() { return compactSegmentThreshold; }
    public void setCompactSegmentThreshold(int compactSegmentThreshold) {
        this.compactSegmentThreshold = compactSegmentThreshold;
    }

    public int getManifestHistoryKeep() { return manifestHistoryKeep; }
    public void setManifestHistoryKeep(int manifestHistoryKeep) {
        this.manifestHistoryKeep = manifestHistoryKeep;
    }
}
