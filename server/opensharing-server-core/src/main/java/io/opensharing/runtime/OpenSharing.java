package io.opensharing.runtime;

import io.opensharing.Transactions;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.schema.ProtocolSchemas;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.ProtocolTables;
import io.opensharing.asset.table.delta.DeltaKernel;
import io.opensharing.asset.table.delta.DeltaTableMetadataReader;
import io.opensharing.asset.table.delta.DeltaTableQueryReader;
import io.opensharing.asset.table.signer.UrlSigners;
import io.opensharing.auth.AuthContext;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * OpenSharing assembled from what its host supplies. The standalone server builds one from its
 * settings. An embedding host, such as a data catalog, builds one directly, serves it through its
 * own HTTP stack, and authenticates callers itself.
 */
public final class OpenSharing {

  private final CatalogConnector catalog;
  private final String protocolBasePath;
  private final String activationBasePath;
  private final ShareService shares;
  private final RecipientService recipients;
  private final ProtocolShares protocolShares;
  private final ProtocolSchemas protocolSchemas;
  private final ProtocolTables protocolTables;

  private OpenSharing(
      CatalogConnector catalog,
      String serverId,
      Transactions tx,
      String publicUrl,
      String protocolBasePath,
      String activationBasePath) {
    this.catalog = catalog;
    this.protocolBasePath = protocolBasePath;
    this.activationBasePath = activationBasePath;
    String profileEndpoint = publicUrl + protocolBasePath;
    String activationBaseUrl = publicUrl + activationBasePath;
    ShareStore shareStore = new ShareStore(tx);
    SharedDataObjectStore objectStore = new SharedDataObjectStore(tx);
    SharePermissionStore permissionStore = new SharePermissionStore(tx);
    RecipientStore recipientStore = new RecipientStore(tx);
    this.shares =
        new ShareService(
            catalog, serverId, tx, shareStore, objectStore, permissionStore, recipientStore);
    this.recipients =
        new RecipientService(
            catalog, serverId, recipientStore, activationBaseUrl, profileEndpoint);
    this.protocolShares = new ProtocolShares(shareStore, permissionStore);
    this.protocolSchemas = new ProtocolSchemas(protocolShares, objectStore);
    DeltaKernel kernel = new DeltaKernel(catalog);
    this.protocolTables =
        new ProtocolTables(
            catalog,
            serverId,
            kernel,
            new DeltaTableMetadataReader(kernel),
            new DeltaTableQueryReader(kernel, new UrlSigners()),
            protocolShares,
            objectStore);
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

  /** The path under which the sharing protocol is served. */
  public String protocolBasePath() {
    return protocolBasePath;
  }

  /** The unauthenticated path under which recipients redeem activation codes. */
  public String activationBasePath() {
    return activationBasePath;
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
    private String serverId;
    private Transactions transactions;
    private String publicUrl;
    private String protocolBasePath = "/api/1.0/opensharing";
    private String activationBasePath = "/api/1.0/opensharing/activations";

    private Builder() {}

    /** The host's catalog integration (required). */
    public Builder catalog(CatalogConnector catalog) {
      this.catalog = catalog;
      return this;
    }

    /**
     * The identity OpenSharing names itself by in every catalog call, as {@link
     * AuthContext#serverId}, for catalogs that trust it to act for the users it calls for. A host
     * that runs OpenSharing inside its catalog leaves it unset.
     */
    public Builder serverId(String serverId) {
      this.serverId = serverId;
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

    /**
     * The address recipients reach the host at, such as {@code https://sharing.example.com}
     * (required). Activation links and profile files point recipients to it.
     */
    public Builder publicUrl(String publicUrl) {
      this.publicUrl = publicUrl;
      return this;
    }

    /** The path under which the sharing protocol is served. */
    public Builder protocolBasePath(String protocolBasePath) {
      this.protocolBasePath = protocolBasePath;
      return this;
    }

    /** The unauthenticated path under which recipients redeem activation codes. */
    public Builder activationBasePath(String activationBasePath) {
      this.activationBasePath = activationBasePath;
      return this;
    }

    public OpenSharing build() {
      if (catalog == null) {
        throw new IllegalStateException("OpenSharing requires a CatalogConnector from the host");
      }
      if (transactions == null) {
        throw new IllegalStateException("OpenSharing requires Transactions from the host");
      }
      String normalizedPublicUrl = normalizePublicUrl(publicUrl);
      return new OpenSharing(
          catalog,
          serverId,
          transactions,
          normalizedPublicUrl,
          protocolBasePath,
          activationBasePath);
    }

    private static String normalizePublicUrl(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "publicUrl is required; set it to the address recipients reach this server at");
      }
      String url = value.trim();
      URI uri;
      try {
        uri = new URI(url);
      } catch (URISyntaxException e) {
        uri = null;
      }
      if (uri == null
          || !("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        throw new IllegalArgumentException(
            "invalid value '" + value + "' for publicUrl; expected an http or https URL");
      }
      return url.replaceAll("/+$", "");
    }
  }
}
