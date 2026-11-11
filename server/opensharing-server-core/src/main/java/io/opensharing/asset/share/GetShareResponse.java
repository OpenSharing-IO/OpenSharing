package io.opensharing.asset.share;

/**
 * Protocol {@code GET /shares/{share}} wraps the share in a single field:
 *
 * <pre>{@code
 * {"share": {"id": "<share id>", "name": "<share name>", ...}}
 * }</pre>
 */
public record GetShareResponse(ProtocolShare share) {}
