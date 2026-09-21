package com.relay.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link AiProvider}. Set {@code relay.ai.provider=openrouter} (plus {@code api-key}
 * and {@code model}) to call a real OpenAI-compatible endpoint; otherwise the deterministic
 * {@link MockAiProvider} is used, so the app runs offline and engine tests stay reproducible.
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnProperty(prefix = "relay.ai", name = "provider", havingValue = "openrouter")
    public AiProvider openAiCompatibleAiProvider(RelayProperties properties, ObjectMapper mapper) {
        return new OpenAiCompatibleAiProvider(properties.getAi(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean(AiProvider.class)
    public AiProvider mockAiProvider(ObjectMapper mapper) {
        return new MockAiProvider(mapper);
    }
}
