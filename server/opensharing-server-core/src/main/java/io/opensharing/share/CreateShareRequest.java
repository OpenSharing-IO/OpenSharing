package io.opensharing.share;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** POST body to create a share. The authenticated caller becomes the owner. */
public record CreateShareRequest(
    @NotBlank String name, String displayName, String comment, Map<String, String> properties) {}
