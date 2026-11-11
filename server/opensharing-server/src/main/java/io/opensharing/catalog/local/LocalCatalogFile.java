package io.opensharing.catalog.local;

import io.opensharing.auth.Privilege;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.DataSourceFormat;
import java.util.List;
import java.util.Map;

/** YAML/JSON description of a local catalog. */
public record LocalCatalogFile(
    Credentials credentials,
    List<String> adminUserIds,
    List<Principal> principals,
    List<Asset> assets) {

  public LocalCatalogFile {
    assets = assets == null ? List.of() : List.copyOf(assets);
    principals = principals == null ? List.of() : List.copyOf(principals);
    adminUserIds = adminUserIds == null ? List.of() : List.copyOf(adminUserIds);
    credentials = credentials == null ? Credentials.defaults() : credentials;
  }

  public LocalCatalogFile(Credentials credentials, List<Asset> assets) {
    this(credentials, null, List.of(), assets);
  }

  public enum CredentialMode {
    FAKE,
    ENV
  }

  /** Provider identity the local catalog accepts for admin APIs, holding only its privileges. */
  public record Principal(
      String bearerToken, String userId, String userName, List<Privilege> privileges) {

    public Principal {
      if (userId == null || userId.isBlank()) {
        throw new IllegalArgumentException("local catalog principal is missing 'userId'");
      }
      privileges = privileges == null ? List.of() : List.copyOf(privileges);
    }
  }

  public record Credentials(CloudProvider provider, CredentialMode mode, Integer ttlSeconds) {

    public Credentials {
      provider = provider == null ? CloudProvider.AWS : provider;
      mode = mode == null ? CredentialMode.FAKE : mode;
    }

    public static Credentials defaults() {
      return new Credentials(CloudProvider.AWS, CredentialMode.FAKE, null);
    }
  }

  public record Asset(
      String identifier,
      AssetType type,
      String storageLocation,
      String format,
      String catalogAssetId,
      List<String> auxiliaryLocations,
      List<String> shareableBy,
      Map<String, String> attributes) {

    public Asset {
      if (identifier == null || identifier.isBlank()) {
        throw new IllegalArgumentException("local catalog asset is missing 'identifier'");
      }
      type = type == null ? AssetType.TABLE : type;
      auxiliaryLocations = auxiliaryLocations == null ? List.of() : List.copyOf(auxiliaryLocations);
      shareableBy = shareableBy == null ? List.of() : List.copyOf(shareableBy);
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
      DataSourceFormat.fromWireName(format);
    }
  }
}
