package io.opensharing.runtime;

import io.opensharing.Transactions;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.share.ShareService;
import io.opensharing.share.ShareStore;

/**
 * OpenSharing assembled from what its host supplies. The standalone server builds one from its
 * Spring beans. An embedding host, such as Unity Catalog, builds one directly, serves it through
 * its own HTTP stack, and authenticates callers itself.
 */
public final class OpenSharing {

  private final CatalogConnector catalog;
  private final ShareService shares;

  private OpenSharing(CatalogConnector catalog, Transactions tx) {
    this.catalog = catalog;
    this.shares = new ShareService(new ShareStore(tx));
  }

  public static Builder builder() {
    return new Builder();
  }

  public CatalogConnector catalog() {
    return catalog;
  }

  public ShareService shares() {
    return shares;
  }

  public static final class Builder {

    private CatalogConnector catalog;
    private Transactions transactions;

    private Builder() {}

    /** The host's catalog integration (required). */
    public Builder catalog(CatalogConnector catalog) {
      this.catalog = catalog;
      return this;
    }

    /** The host's database transactions (required). */
    public Builder transactions(Transactions transactions) {
      this.transactions = transactions;
      return this;
    }

    public OpenSharing build() {
      if (catalog == null) {
        throw new IllegalStateException("OpenSharing requires a CatalogConnector from the host");
      }
      if (transactions == null) {
        throw new IllegalStateException("OpenSharing requires Transactions from the host");
      }
      return new OpenSharing(catalog, transactions);
    }
  }
}
