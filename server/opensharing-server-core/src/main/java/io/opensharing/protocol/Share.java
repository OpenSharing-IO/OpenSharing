package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** A share as returned by {@code GET /shares} and {@code GET /shares/{share}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Share(
    String name, String id, String displayName, String comment, Map<String, String> properties) {}
