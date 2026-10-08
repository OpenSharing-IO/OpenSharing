package io.opensharing.runtime;

import io.opensharing.catalog.CatalogConnector;

/**
 * OpenSharing assembled from what its host supplies. The standalone server builds one from its
 * Spring beans. An embedding host, such as Unity Catalog, builds one directly, serves it through
 * its own HTTP stack, and authenticates callers itself.
 */
public final class OpenSharing {

  private final CatalogConnector catalog;

  private OpenSharing(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  public static Builder builder() {
    return new Builder();
  }

  public CatalogConnector catalog() {
    return catalog;
  }

  public static final class Builder {

    private CatalogConnector catalog;

    private Builder() {}

    /** The host's catalog integration (required). */
    public Builder catalog(CatalogConnector catalog) {
      this.catalog = catalog;
      return this;
    }

    public OpenSharing build() {
      if (catalog == null) {
        throw new IllegalStateException("OpenSharing requires a CatalogConnector from the host");
      }
      return new OpenSharing(catalog);
    }
  }
}
