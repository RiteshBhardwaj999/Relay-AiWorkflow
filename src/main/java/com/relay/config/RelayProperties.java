package com.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Central configuration for Relay. Bound from the {@code relay.*} tree in application.yml.
 */
@ConfigurationProperties(prefix = "relay")
public class RelayProperties {

    private final Auth auth = new Auth();
    @NestedConfigurationProperty
    private final MockWorld mockWorld = new MockWorld();
    private final Seed seed = new Seed();

    public Auth getAuth() {
        return auth;
    }

    public MockWorld getMockWorld() {
        return mockWorld;
    }

    public Seed getSeed() {
        return seed;
    }

    /** Startup loading of the provided node catalog and seed workflows. */
    public static class Seed {
        private boolean enabled = true;
        private String dataDir = "relay-capstone-pack/data";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }
    }

    /** Bearer-token auth for the platform APIs. A blank token disables the check (dev/demo). */
    public static class Auth {
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isEnabled() {
            return token != null && !token.isBlank();
        }
    }

    /** Connection settings for the external mock world simulator. */
    public static class MockWorld {
        private String baseUrl = "http://localhost:9210";
        private int timeoutMs = 5000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
