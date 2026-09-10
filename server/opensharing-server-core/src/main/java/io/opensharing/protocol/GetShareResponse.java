package io.opensharing.protocol;

/** {@code GET /shares/{share}} wraps the share in a single-field object. */
public record GetShareResponse(Share share) {}
