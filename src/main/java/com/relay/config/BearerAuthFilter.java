package com.relay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.web.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Guards the platform APIs ({@code /workflows}, {@code /runs}, {@code /approvals}) with a
 * static Bearer token. Webhook endpoints ({@code /hooks/**}) are intentionally excluded — they
 * are authenticated per-workflow with {@code X-Relay-Secret} in the trigger layer.
 *
 * <p>If {@code relay.auth.token} is blank the filter is a no-op, which keeps local demos simple.
 */
public class BearerAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PREFIXES = Set.of("/workflows", "/runs", "/approvals");

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;

    public BearerAuthFilter(RelayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getAuth().isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return PROTECTED_PREFIXES.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String expected = properties.getAuth().getToken();
        if (header == null || !header.startsWith("Bearer ")
                || !header.substring("Bearer ".length()).equals(expected)) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of("Missing or invalid bearer token", "unauthorized"));
    }
}
