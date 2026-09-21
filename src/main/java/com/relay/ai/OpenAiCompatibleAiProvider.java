package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Calls an OpenAI-compatible chat-completions endpoint (OpenRouter and most open-source model
 * hosts). The schema is appended to the prompt to steer the model toward conforming JSON, and
 * {@code response_format: json_object} is requested when enabled. Validation and the one-shot repair
 * retry still happen in the {@code ai} executor, so a stray bit of prose or an extra field is caught
 * regardless of the model. Non-2xx responses raise an exception so the engine retries with backoff.
 */
public class OpenAiCompatibleAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiProvider.class);

    private final RelayProperties.Ai config;
    private final ObjectMapper mapper;
    private final RestClient client;

    public OpenAiCompatibleAiProvider(RelayProperties.Ai config, ObjectMapper mapper) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("relay.ai.provider=openrouter requires relay.ai.api-key "
                    + "(set RELAY_AI_API_KEY)");
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException("relay.ai.provider=openrouter requires relay.ai.model "
                    + "(set RELAY_AI_MODEL, e.g. meta-llama/llama-3.1-8b-instruct)");
        }
        this.config = config;
        this.mapper = mapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(config.getTimeoutMs()));
        this.client = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .build();
        log.info("AI provider: OpenAI-compatible endpoint {} model {}", config.getBaseUrl(), config.getModel());
    }

    @Override
    public Completion complete(String prompt, JsonNode outputSchema) {
        ObjectNode body = buildRequest(prompt, outputSchema);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize AI request", e);
        }

        ResponseEntity<String> response = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    h.setBearerAuth(config.getApiKey());
                    // OpenRouter attribution headers (optional but recommended)
                    h.set("HTTP-Referer", "https://github.com/airtribe-projects/relay-capstone");
                    h.set("X-Title", "Relay");
                })
                .body(json)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);

        int status = response.getStatusCode().value();
        if (status < 200 || status >= 300) {
            // Thrown → the engine treats it as a transient failure and retries with backoff.
            throw new AiProviderException("AI provider returned " + status + ": " + truncate(response.getBody()));
        }
        return parseCompletion(response.getBody());
    }

    private ObjectNode buildRequest(String prompt, JsonNode outputSchema) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", 0);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        String content = prompt;
        if (outputSchema != null && outputSchema.isObject()) {
            content = prompt + "\n\nRespond with ONLY a JSON object (no prose, no markdown fences) "
                    + "that conforms to this JSON Schema:\n" + outputSchema;
        }
        userMsg.put("content", content);
        if (config.isJsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        return body;
    }

    private Completion parseCompletion(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            return new Completion(content, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new AiProviderException("Could not parse AI response: " + e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** Raised on transport/HTTP failures; surfaces as a retryable node failure in the engine. */
    public static class AiProviderException extends RuntimeException {
        public AiProviderException(String message) {
            super(message);
        }
    }
}
