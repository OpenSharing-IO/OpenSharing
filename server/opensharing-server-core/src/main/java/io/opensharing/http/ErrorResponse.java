package io.opensharing.http;

/** Error body returned by every non-2xx protocol response. */
public record ErrorResponse(String errorCode, String message) {}
