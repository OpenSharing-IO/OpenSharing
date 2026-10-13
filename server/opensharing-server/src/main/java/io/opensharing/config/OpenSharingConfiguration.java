package io.opensharing.config;

import io.opensharing.catalog.CatalogConnector;
import io.opensharing.runtime.OpenSharing;

/** Builds the standalone server's {@link OpenSharing} from its settings. */
public final class OpenSharingConfiguration {

  private OpenSharingConfiguration() {}

  public static OpenSharing openSharing(CatalogConnector catalog) {
    return OpenSharing.builder().catalog(catalog).build();
  }
}
