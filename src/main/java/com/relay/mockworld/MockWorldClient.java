package com.relay.mockworld;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Typed client for the mock world. Every mutating call carries an {@code Idempotency-Key} so crash
 * recovery never doubles a side effect, and every call is timeout-bounded (see the RestClient bean).
 * Non-2xx responses are returned (not thrown) so the executor can decide how to react.
 */
@Component
public class MockWorldClient {

    /** Outcome of a mock-world call. */
    public record Result(int status, JsonNode body, boolean replayed) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    private final RestClient client;
    private final ObjectMapper mapper;

    public MockWorldClient(RestClient mockWorldRestClient, ObjectMapper mapper) {
        this.client = mockWorldRestClient;
        this.mapper = mapper;
    }

    public Result post(String path, JsonNode body, String idempotencyKey) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize mock-world request body", e);
        }
        ResponseEntity<String> response = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (idempotencyKey != null) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .body(json)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);

        boolean replayed = "true".equalsIgnoreCase(response.getHeaders().getFirst("x-mockworld-replayed"));
        return new Result(response.getStatusCode().value(), parse(response.getBody()), replayed);
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.getNodeFactory().textNode(raw);
        }
    }
}
