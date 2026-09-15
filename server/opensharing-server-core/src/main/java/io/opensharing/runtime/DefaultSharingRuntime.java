package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;

final class DefaultSharingRuntime implements SharingRuntime {

  private final HostingMode hostingMode;
  private final CatalogConnector catalogConnector;

  DefaultSharingRuntime(HostingMode hostingMode, CatalogConnector catalogConnector) {
    this.hostingMode = hostingMode;
    this.catalogConnector = catalogConnector;
  }

  @Override
  public HostingMode hostingMode() {
    return hostingMode;
  }

  @Override
  public CatalogConnector catalogConnector() {
    return catalogConnector;
  }
}
