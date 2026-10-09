package io.opensharing.runtime;

import io.opensharing.Transactions;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientService;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.recipient.RecipientTokenSettings;
import io.opensharing.share.SharePermissionStore;
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
  private final RecipientService recipients;

  private OpenSharing(
      CatalogConnector catalog,
      Transactions tx,
      RecipientTokenSettings recipientTokens,
      Pagination pagination) {
    this.catalog = catalog;
    RecipientStore recipientStore = new RecipientStore(tx);
    this.shares =
        new ShareService(
            new ShareStore(tx),
            new SharedDataObjectStore(tx),
            new SharePermissionStore(tx),
            recipientStore,
            catalog,
            pagination);
    this.recipients = new RecipientService(recipientStore, recipientTokens, pagination);
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

  public RecipientService recipients() {
    return recipients;
  }

  public static final class Builder {

    private CatalogConnector catalog;
    private Transactions transactions;
    private RecipientTokenSettings recipientTokens = RecipientTokenSettings.DEFAULTS;
    private Pagination pagination = Pagination.DEFAULTS;

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

    /** Lifetimes of issued recipient tokens, {@link RecipientTokenSettings#DEFAULTS} if unset. */
    public Builder recipientTokens(RecipientTokenSettings recipientTokens) {
      this.recipientTokens = recipientTokens;
      return this;
    }

    /** Page sizes of the list APIs, {@link Pagination#DEFAULTS} if unset. */
    public Builder pagination(Pagination pagination) {
      this.pagination = pagination;
      return this;
    }

    public OpenSharing build() {
      if (catalog == null) {
        throw new IllegalStateException("OpenSharing requires a CatalogConnector from the host");
      }
      if (transactions == null) {
        throw new IllegalStateException("OpenSharing requires Transactions from the host");
      }
      return new OpenSharing(catalog, transactions, recipientTokens, pagination);
    }
  }
}
