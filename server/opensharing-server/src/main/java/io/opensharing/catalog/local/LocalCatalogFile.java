package io.opensharing.catalog.local;

import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.DataSourceFormat;
import java.util.List;
import java.util.Map;

/** YAML/JSON description of a local catalog. */
public record LocalCatalogFile(
    Credentials credentials, List<Principal> principals, List<Asset> assets) {

  public LocalCatalogFile {
    assets = assets == null ? List.of() : List.copyOf(assets);
    principals = principals == null ? List.of() : List.copyOf(principals);
    credentials = credentials == null ? Credentials.defaults() : credentials;
  }

  public LocalCatalogFile(Credentials credentials, List<Asset> assets) {
    this(credentials, List.of(), assets);
  }

  public enum CredentialMode {
    FAKE,
    STATIC
  }

  /** Provider identity the local catalog accepts for admin APIs. */
  public record Principal(String bearerToken, String userId, String userName) {}

  public record Credentials(
      CloudProvider provider, CredentialMode mode, Integer ttlSeconds, Map<String, String> values) {

    public Credentials {
      provider = provider == null ? CloudProvider.AWS : provider;
      mode = mode == null ? CredentialMode.FAKE : mode;
      values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static Credentials defaults() {
      return new Credentials(CloudProvider.AWS, CredentialMode.FAKE, null, Map.of());
    }
  }

  public record Asset(
      String identifier,
      AssetType type,
      String storageLocation,
      String format,
      String catalogAssetId,
      List<String> auxiliaryLocations,
      List<String> sharableBy,
      Map<String, String> attributes) {

    public Asset {
      if (identifier == null || identifier.isBlank()) {
        throw new IllegalArgumentException("local catalog asset is missing 'identifier'");
      }
      type = type == null ? AssetType.TABLE : type;
      auxiliaryLocations = auxiliaryLocations == null ? List.of() : List.copyOf(auxiliaryLocations);
      sharableBy = sharableBy == null ? List.of() : List.copyOf(sharableBy);
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
      DataSourceFormat.fromWireName(format);
    }
  }
}
