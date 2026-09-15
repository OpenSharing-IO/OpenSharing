package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;

/** Whether OpenSharing runs as its own process or inside a host catalog. */
public enum HostingMode {
  /**
   * A standalone Spring Boot server. {@code opensharing.catalog.*} selects the catalog connector
   * shipped with the reference server.
   */
  STANDALONE,
  /**
   * Embedded in a host process. The host registers a {@link CatalogConnector} bean rather than
   * using the standalone catalog configuration.
   */
  EMBEDDED
}
