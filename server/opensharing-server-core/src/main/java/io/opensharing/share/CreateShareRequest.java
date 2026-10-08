package io.opensharing.share;

import java.util.Map;

/** POST body to create a share. The authenticated caller becomes the owner. */
public record CreateShareRequest(
    String name, String displayName, String comment, Map<String, String> properties) {}
