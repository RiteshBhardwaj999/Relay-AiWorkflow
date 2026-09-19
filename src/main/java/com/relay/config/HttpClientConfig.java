package com.relay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * A general-purpose, timeout-bounded {@link RestClient} for the {@code http_request} node (arbitrary
 * URLs). Distinct from the mock-world client, which is pinned to the mock-world base URL.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient genericRestClient(RelayProperties properties) {
        int timeout = properties.getMockWorld().getTimeoutMs();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return RestClient.builder().requestFactory(factory).build();
    }
}
