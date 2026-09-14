package io.opensharing.protocol;

/** A schema inside a share. Schemas are derived from the assets shared under them. */
public record Schema(String name, String share) {}
