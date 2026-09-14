package io.opensharing.runtime;

/** Whether OpenSharing runs as its own process or inside a host such as Unity Catalog OSS. */
public enum HostingMode {
  /**
   * A standalone Spring Boot server. The deployment supplies {@code opensharing.catalog.*} and
   * {@code opensharing.admin.principals}; catalog calls go out over HTTP when configured for Unity
   * Catalog.
   */
  STANDALONE,
  /**
   * Embedded in a host process. The host registers a {@link io.opensharing.catalog.CatalogConnector}
   * bean (typically in-process, not HTTP) and may register a {@link ProviderIdentityResolver} so
   * provider-admin requests use the host's identity model instead of {@code opensharing.admin.principals}.
   */
  EMBEDDED
}
