package io.opensharing.share;

import java.util.Map;

/** PATCH body for share metadata. Null fields are left unchanged. */
public record UpdateShareRequest(String displayName, String comment, Map<String, String> properties) {}
