package com.relay.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.catalog.NodeCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link WorkflowValidator} using the real node catalog and seed workflows.
 * No Spring context or Docker required.
 */
class WorkflowValidatorTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static WorkflowValidator validator;
    static JsonNode seedRoot;

    @BeforeAll
    static void setUp() throws Exception {
        Path dataDir = Path.of("relay-capstone-pack", "data");
        JsonNode catalogJson = MAPPER.readTree(Files.readString(dataDir.resolve("node_catalog.json")));
        validator = new WorkflowValidator(NodeCatalog.fromJson(catalogJson));
        seedRoot = MAPPER.readTree(Files.readString(dataDir.resolve("seed_workflows.json")));
    }

    @Test
    void allSeedWorkflowsAreValid() {
        for (JsonNode wf : seedRoot.path("workflows")) {
            List<String> errors = validator.validate(wf);
            assertThat(errors)
                    .as("seed workflow %s should be valid", wf.path("id").asText())
                    .isEmpty();
        }
    }

    @Test
    void rejectsUnknownNodeType() {
        JsonNode def = wrap("""
                {"id":"n1","type":"teleport","params":{},"next":null}
                """);
        assertThat(validator.validate(def)).anyMatch(e -> e.contains("unknown type 'teleport'"));
    }

    @Test
    void rejectsMissingRequiredParam() {
        JsonNode def = wrap("""
                {"id":"n1","type":"notify","params":{"channel":"chat"},"next":null}
                """);
        assertThat(validator.validate(def)).anyMatch(e -> e.contains("missing required param"));
    }

    @Test
    void rejectsReferenceToNonexistentNode() {
        JsonNode def = wrap("""
                {"id":"n1","type":"delay","params":{"seconds":1},"next":"ghost"}
                """);
        assertThat(validator.validate(def)).anyMatch(e -> e.contains("references unknown node 'ghost'"));
    }

    @Test
    void rejectsBadEntryPoint() throws Exception {
        JsonNode def = MAPPER.readTree("""
                {"id":"wf_x","name":"x","trigger":{"type":"manual"},"entry":"missing",
                 "nodes":[{"id":"n1","type":"delay","params":{"seconds":1},"next":null}]}
                """);
        assertThat(validator.validate(def)).anyMatch(e -> e.contains("entry 'missing'"));
    }

    @Test
    void rejectsUnknownTriggerAndBadEnum() throws Exception {
        JsonNode def = MAPPER.readTree("""
                {"id":"wf_x","name":"x","trigger":{"type":"carrier_pigeon"},"entry":"n1",
                 "nodes":[{"id":"n1","type":"notify","params":{"channel":"telegram","to":"#x","message":"hi"},"next":null}]}
                """);
        List<String> errors = validator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("unknown trigger type 'carrier_pigeon'"));
        assertThat(errors).anyMatch(e -> e.contains("param 'channel' must be one of"));
    }

    /** Wrap a single node into a minimal, otherwise-valid manual workflow entered at that node. */
    private JsonNode wrap(String nodeJson) {
        try {
            return MAPPER.readTree("""
                    {"id":"wf_x","name":"x","trigger":{"type":"manual"},"entry":"n1","nodes":[%s]}
                    """.formatted(nodeJson.strip()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
