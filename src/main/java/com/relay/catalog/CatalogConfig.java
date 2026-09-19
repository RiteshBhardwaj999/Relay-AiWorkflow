package com.relay.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the node catalog once at startup and exposes it as a singleton bean.
 */
@Configuration
public class CatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogConfig.class);

    @Bean
    public NodeCatalog nodeCatalog(RelayProperties properties, ObjectMapper objectMapper) {
        Path file = Path.of(properties.getSeed().getDataDir(), "node_catalog.json");
        if (!Files.exists(file)) {
            throw new IllegalStateException("Node catalog not found at " + file.toAbsolutePath()
                    + " — set relay.seed.data-dir to the directory containing node_catalog.json");
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            NodeCatalog catalog = NodeCatalog.fromJson(root);
            log.info("Loaded node catalog: {} node types, {} trigger types",
                    catalog.nodeTypes().size(), catalog.triggerTypes().size());
            return catalog;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read node catalog from " + file, e);
        }
    }
}
