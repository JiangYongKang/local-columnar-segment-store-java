package com.github.highcumontoa.localcolumnarsegmentstorejava.tests.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end REST verification on a random local port: create, write with
 * per-record rejections, partition query, additive evolution, incompatible
 * change rejection and compaction. No external services are used.
 */
@SpringBootTest(properties = "store.data-dir=${java.io.tmpdir}/lcs-rest-${random.uuid}")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
class RestApiTest {

    @Autowired
    private MockMvc mvc;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String createBody;
    private static String writeBody;

    @BeforeAll
    static void bodies() {
        createBody = """
                {
                  "tableName": "events",
                  "fields": [
                    {"name": "id", "type": "LONG", "nullable": false, "key": true},
                    {"name": "day", "type": "LONG", "nullable": false, "partition": true},
                    {"name": "label", "type": "STRING", "nullable": true}
                  ],
                  "partitionColumn": "day",
                  "lowerBound": 0,
                  "upperBound": 5
                }
                """;
        writeBody = """
                {
                  "rows": [
                    {"id": 1, "day": 0, "label": "a"},
                    {"id": 2, "day": 5, "label": "b"},
                    {"id": 3, "day": 9, "label": "bad-partition"},
                    {"id": "oops", "day": 1, "label": "bad-type"}
                  ]
                }
                """;
    }

    @Test
    void a_createTable() throws Exception {
        mvc.perform(put("/api/tables/events").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.version").value(0))
                .andExpect(jsonPath("$.schema.version").value(1));

        // duplicate create -> 409 distinguishable code
        mvc.perform(put("/api/tables/events").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TABLE_ALREADY_EXISTS"));
    }

    @Test
    void b_writeReportsDistinguishableRejections() throws Exception {
        mvc.perform(post("/api/tables/events/rows").contentType(MediaType.APPLICATION_JSON).content(writeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejectedCount").value(2))
                .andExpect(jsonPath("$.rejected[0].reason").value("PARTITION_VIOLATION"))
                .andExpect(jsonPath("$.rejected[1].reason").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.snapshotVersion").value(1));
    }

    @Test
    void c_queryRespectsPartitionBounds() throws Exception {
        mvc.perform(get("/api/tables/events/rows").param("eq", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(1));

        mvc.perform(get("/api/tables/events/rows").param("low", "0").param("high", "5"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void d_schemaEvolutionAndRejection() throws Exception {
        // compatible add
        mvc.perform(post("/api/tables/events/columns").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "region", "type": "STRING", "nullable": false, "defaultValue": "earth"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.schemaVersion").value(2));

        // historical rows read the stable default
        mvc.perform(get("/api/tables/events/rows").param("eq", "5"))
                .andExpect(jsonPath("$.rows[0].region").value("earth"));

        // incompatible type change -> 422 with explicit code and reason
        mvc.perform(post("/api/tables/events/columns/type").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "id", "newType": "STRING"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INCOMPATIBLE_SCHEMA"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("CHANGE_TYPE rejected")));
    }

    @Test
    void e_compaction() throws Exception {
        // add a second segment first
        mvc.perform(post("/api/tables/events/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rows": [{"id": 10, "day": 2, "label": "c", "region": "mars"}]}
                                """))
                .andExpect(jsonPath("$.snapshotVersion").value(3));

        mvc.perform(post("/api/tables/events/compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.segments.length()").value(1))
                .andExpect(jsonPath("$.manifest.rowCount").value(3));

        mvc.perform(get("/api/tables/events/snapshot"))
                .andExpect(jsonPath("$.snapshot.segmentCount").value(1));

        // table not found -> 404
        mvc.perform(get("/api/tables/ghost/snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TABLE_NOT_FOUND"));
    }
}
