package com.relay.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@code seed_workflows.json} on startup and upserts each workflow as {@code published}.
 * Idempotent: re-running refreshes the stored definition without creating duplicates.
 */
@Component
@Order(10)
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final WorkflowRepository workflows;

    public SeedLoader(RelayProperties properties, ObjectMapper objectMapper, WorkflowRepository workflows) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.workflows = workflows;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.getSeed().isEnabled()) {
            log.info("Seed loading disabled (relay.seed.enabled=false)");
            return;
        }
        Path file = Path.of(properties.getSeed().getDataDir(), "seed_workflows.json");
        if (!Files.exists(file)) {
            log.warn("Seed workflows file not found at {} — skipping seed load", file.toAbsolutePath());
            return;
        }
        JsonNode root = objectMapper.readTree(Files.readString(file));
        int loaded = 0;
        for (JsonNode def : root.path("workflows")) {
            upsertPublished(rewriteMockWorldUrls(def));
            loaded++;
        }
        log.info("Seeded {} workflow(s) as published from {}", loaded, file);
    }

    /**
     * The seed workflows hardcode {@code http://localhost:9210} in http_request URLs. When the mock
     * world is reachable at a different address (e.g. {@code http://mockworld:9210} inside Docker),
     * rewrite the definition so those nodes still hit it. A no-op for the default localhost setup.
     */
    private JsonNode rewriteMockWorldUrls(JsonNode def) {
        String base = properties.getMockWorld().getBaseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.equals("http://localhost:9210")) {
            return def;
        }
        try {
            String rewritten = objectMapper.writeValueAsString(def)
                    .replace("http://localhost:9210", normalized);
            return objectMapper.readTree(rewritten);
        } catch (Exception e) {
            log.warn("Could not rewrite mock-world URLs for workflow {}", def.path("id").asText(), e);
            return def;
        }
    }

    private void upsertPublished(JsonNode def) {
        String id = def.path("id").asText();
        String name = def.path("name").asText(id);
        String description = def.path("description").asText(null);

        Workflow workflow = workflows.findById(id).orElse(null);
        if (workflow == null) {
            workflow = new Workflow(id, name, description, WorkflowStatus.published, def);
        } else {
            workflow.setName(name);
            workflow.setDescription(description);
            workflow.setDefinition(def);
            workflow.setStatus(WorkflowStatus.published);
            workflow.touch();
        }
        workflows.save(workflow);
    }
}
