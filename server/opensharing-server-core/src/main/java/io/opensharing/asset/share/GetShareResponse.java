package io.opensharing.asset.share;

import io.opensharing.share.ShareResponse;

/**
 * Protocol {@code GET /shares/{share}} wraps the share in a single field:
 *
 * <pre>{@code
 * {"share": {"id": "<share id>", "name": "<share name>", ...}}
 * }</pre>
 */
public record GetShareResponse(ShareResponse share) {}
