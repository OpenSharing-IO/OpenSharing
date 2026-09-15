package io.opensharing.share;

import io.opensharing.http.AdminJson;
import java.util.Map;

/** Updates a share's metadata. Only non-null fields are applied. */
@AdminJson
public record UpdateShareRequest(String displayName, String comment, Map<String, String> properties) {}
