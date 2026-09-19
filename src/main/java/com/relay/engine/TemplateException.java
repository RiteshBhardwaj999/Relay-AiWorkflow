package com.relay.engine;

/**
 * Raised when a {@code {{...}}} template references a path that cannot be resolved. Fails the
 * current step with a clear message rather than silently substituting empty text.
 */
public class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }
}
