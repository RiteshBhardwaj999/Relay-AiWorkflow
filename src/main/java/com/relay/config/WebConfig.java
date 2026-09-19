package com.relay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Relay's servlet filters.
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<BearerAuthFilter> bearerAuthFilter(RelayProperties properties,
                                                                     ObjectMapper objectMapper) {
        FilterRegistrationBean<BearerAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BearerAuthFilter(properties, objectMapper));
        registration.addUrlPatterns("/workflows/*", "/workflows", "/runs/*", "/approvals/*", "/approvals");
        registration.setOrder(1);
        return registration;
    }
}
