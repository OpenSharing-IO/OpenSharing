package io.opensharing.runtime;

import io.opensharing.Transactions;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.schema.ProtocolSchemas;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.asset.table.delta.DeltaKernel;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.recipient.RecipientService;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.recipient.RecipientTokenEntity;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.SharePermissionEntity;
import io.opensharing.share.SharePermissionStore;
import io.opensharing.share.ShareService;
import io.opensharing.share.ShareStore;
import java.util.List;

/**
 * OpenSharing assembled from what its host supplies. The standalone server builds one from its
 * settings. An embedding host, such as Unity Catalog, builds one directly, serves it through
 * its own HTTP stack, and authenticates callers itself.
 */
public final class OpenSharing {

  private final CatalogConnector catalog;
  private final ShareService shares;
  private final RecipientService recipients;
  private final ProtocolShares protocolShares;
  private final ProtocolSchemas protocolSchemas;
  private final ProtocolTables protocolTables;

  private OpenSharing(CatalogConnector catalog, Transactions tx) {
    this.catalog = catalog;
    ShareStore shareStore = new ShareStore(tx);
    SharedDataObjectStore objectStore = new SharedDataObjectStore(tx);
    SharePermissionStore permissionStore = new SharePermissionStore(tx);
    RecipientStore recipientStore = new RecipientStore(tx);
    this.shares =
        new ShareService(catalog, tx, shareStore, objectStore, permissionStore, recipientStore);
    this.recipients = new RecipientService(catalog, recipientStore);
    this.protocolShares = new ProtocolShares(shareStore, permissionStore);
    this.protocolSchemas = new ProtocolSchemas(protocolShares, objectStore);
    this.protocolTables =
        new ProtocolTables(catalog, new DeltaKernel(catalog), protocolShares, objectStore);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The entity classes the host's {@link Transactions} must register. */
  public static List<Class<?>> entities() {
    return List.of(
        ShareEntity.class,
        SharedDataObjectEntity.class,
        RecipientEntity.class,
        RecipientTokenEntity.class,
        SharePermissionEntity.class);
  }

  public CatalogConnector catalog() {
    return catalog;
  }

  public ShareService shares() {
    return shares;
  }

  public RecipientService recipients() {
    return recipients;
  }

  /** The shares recipients see through the sharing protocol. */
  public ProtocolShares protocolShares() {
    return protocolShares;
  }

  /** The schemas recipients see in their shares through the sharing protocol. */
  public ProtocolSchemas protocolSchemas() {
    return protocolSchemas;
  }

  /** The tables recipients see in their shares through the sharing protocol. */
  public ProtocolTables protocolTables() {
    return protocolTables;
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

    /**
     * How OpenSharing reaches the host's database. OpenSharing keeps its own tables there and runs
     * every read and write through these transactions, so the host's persistence setup must
     * register the classes from {@link OpenSharing#entities}. Must be set before {@link #build}.
     */
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
