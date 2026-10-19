package io.opensharing.http;

/**
 * Error body returned by every non-2xx protocol response:
 *
 * <pre>{@code
 * {"errorCode": "<error code>", "message": "<message>"}
 * }</pre>
 */
public record ErrorResponse(String errorCode, String message) {}
