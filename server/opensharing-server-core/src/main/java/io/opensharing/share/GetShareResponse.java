package io.opensharing.share;

/** Protocol {@code GET /shares/{share}} wraps the share in a single field. */
public record GetShareResponse(ShareResponse share) {}
