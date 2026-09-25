package com.github.highcumontoa.localcolumnarsegmentstorejava.config;

import com.github.highcumontoa.localcolumnarsegmentstorejava.store.StoreCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the single on-disk {@link StoreCatalog} from {@link StoreProperties}. */
@Configuration
@EnableConfigurationProperties(StoreProperties.class)
public class StoreConfig {

    @Bean
    public StoreCatalog storeCatalog(StoreProperties properties) {
        return new StoreCatalog(properties.getDataDir());
    }
}
