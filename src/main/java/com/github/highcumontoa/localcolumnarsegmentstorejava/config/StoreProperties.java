package com.github.highcumontoa.localcolumnarsegmentstorejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable options of the local store. */
@ConfigurationProperties(prefix = "store")
public class StoreProperties {

    /** Root directory on local filesystem that holds all tables. */
    private String rootDir = "./data";

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }
}
