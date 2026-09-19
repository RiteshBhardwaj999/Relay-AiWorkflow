package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Calls an arbitrary HTTP endpoint with template-resolved method/url/headers/body. Non-GET requests
 * are side effects and carry the stable idempotency key. The step fails on any non-2xx response.
 * Output: {@code {status, body}} where body is parsed JSON when possible.
 */
@Component
public class HttpRequestExecutor implements NodeExecutor {

    private final RestClient http;
    private final ObjectMapper mapper;

    public HttpRequestExecutor(RestClient genericRestClient, ObjectMapper mapper) {
        this.http = genericRestClient;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "http_request";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        JsonNode params = ctx.resolvedParams();
        String method = params.path("method").asText(null);
        String url = params.path("url").asText(null);
        if (method == null || url == null) {
            return ExecResult.fail("http_request requires 'method' and 'url'");
        }
        boolean mutating = !"GET".equalsIgnoreCase(method);
        String key = ctx.idempotencyKey();

        try {
            RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method.toUpperCase()))
                    .uri(URI.create(url));
            JsonNode headers = params.path("headers");
            if (headers.isObject()) {
                headers.fields().forEachRemaining(e -> spec.header(e.getKey(), e.getValue().asText()));
            }
            if (mutating) {
                spec.header("Idempotency-Key", key);
            }
            JsonNode body = params.path("body");
            if (mutating && body.isObject()) {
                spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
            }
            ResponseEntity<String> response = spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toEntity(String.class);

            int status = response.getStatusCode().value();
            JsonNode parsedBody = parse(response.getBody());
            if (status < 200 || status >= 300) {
                return ExecResult.fail("http_request " + method + " " + url + " returned " + status + ": " + parsedBody);
            }
            ObjectNode output = mapper.createObjectNode();
            output.put("status", status);
            output.set("body", parsedBody);
            ExecResult result = ExecResult.continueTo(output, ctx.next());
            return mutating ? result.withIdempotencyKey(key) : result;
        } catch (Exception e) {
            return ExecResult.fail("http_request " + method + " " + url + " error: " + e.getMessage());
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.getNodeFactory().textNode(raw);
        }
    }
}
