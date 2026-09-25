package com.github.highcumontoa.localcolumnarsegmentstorejava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end smoke test of the REST API against a local temp directory. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestApiTest {

    private static final Logger log = LoggerFactory.getLogger(RestApiTest.class);

    @TempDir
    static Path storeDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("store.root-dir", () -> storeDir.toString());
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void writeQueryCompactOverHttp() {
        var create = Map.of(
                "columns", List.of(
                        Map.of("name", "id", "type", "LONG"),
                        Map.of("name", "dt", "type", "STRING"),
                        Map.of("name", "value", "type", "DOUBLE"),
                        Map.of("name", "name", "type", "STRING")),
                "keyColumn", "id",
                "partitionColumn", "dt");
        var created = rest.postForEntity("/tables/events", create, Map.class);
        log.info("create table: {}", created.getBody());
        assertEquals(201, created.getStatusCode().value());

        var write = Map.of("partition", "2026-09-26", "records", List.of(
                Map.of("id", 1, "dt", "2026-09-26", "value", 1.5, "name", "a"),
                Map.of("id", 2, "dt", "2026-09-26", "value", 2.5, "name", "b")));
        var written = rest.postForEntity("/tables/events/write", write, Map.class);
        log.info("write: {}", written.getBody());
        assertEquals(2, ((Number) written.getBody().get("accepted")).intValue());

        var queried = rest.getForEntity("/tables/events/query?partition=2026-09-26", List.class);
        log.info("query: {}", queried.getBody());
        assertEquals(2, queried.getBody().size());

        var addColumn = Map.of("name", "score", "type", "INT", "defaultValue", 0);
        var evolved = rest.postForEntity("/tables/events/schema/columns", addColumn, Map.class);
        log.info("add column: {}", evolved.getBody());
        assertEquals(200, evolved.getStatusCode().value());

        var compacted = rest.postForEntity("/tables/events/compact?partition=2026-09-26", null, Map.class);
        log.info("compact: {}", compacted.getBody());
        assertTrue(((Number) compacted.getBody().get("snapshotVersion")).longValue() > 0);

        var afterCompact = rest.getForEntity("/tables/events/query?partition=2026-09-26", List.class);
        log.info("judgement: rows after compact = {}", afterCompact.getBody());
        assertEquals(2, afterCompact.getBody().size());
        assertEquals(0, ((Map<String, Object>) afterCompact.getBody().get(0)).get("score"));
    }
}
