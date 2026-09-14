package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;

/** Read-only view of how this OpenSharing assembly is hosted. */
public interface SharingRuntime {

  HostingMode hostingMode();

  CatalogConnector catalogConnector();
}
