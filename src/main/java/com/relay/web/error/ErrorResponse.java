package com.relay.web.error;

/**
 * Canonical error envelope: {@code {"error": {"message": "...", "code": "..."}}}.
 */
public record ErrorResponse(Body error) {

    public record Body(String message, String code) {
    }

    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse(new Body(message, code));
    }
}
