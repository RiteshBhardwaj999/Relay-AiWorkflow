package com.relay.mockworld;

import com.relay.config.RelayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds a timeout-bounded {@link RestClient} pointed at the mock world. Every external call in
 * Relay must have a timeout; this is the single place that guarantee is configured for side effects.
 */
@Configuration
public class MockWorldConfig {

    @Bean
    public RestClient mockWorldRestClient(RelayProperties properties) {
        RelayProperties.MockWorld cfg = properties.getMockWorld();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        return RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
