package io.opensharing.asset.share;

import io.opensharing.share.ShareResponse;

/** Protocol {@code GET /shares/{share}} wraps the share in a single field. */
public record GetShareResponse(ShareResponse share) {}
