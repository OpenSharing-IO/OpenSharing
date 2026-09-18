package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** A share exposed to a recipient by the protocol API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Share(
    String name, String id, String displayName, String comment, Map<String, String> properties) {}
