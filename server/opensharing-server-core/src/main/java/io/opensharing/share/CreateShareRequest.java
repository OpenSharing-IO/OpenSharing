package io.opensharing.share;

import io.opensharing.http.AdminJson;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** The share is owned by the principal that creates it. */
@AdminJson
public record CreateShareRequest(
    @NotBlank String name, String displayName, String comment, Map<String, String> properties) {}
