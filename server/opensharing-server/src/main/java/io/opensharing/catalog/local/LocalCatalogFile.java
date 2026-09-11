package io.opensharing.catalog.local;

import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.TableFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** YAML/JSON description of a local catalog. */
public record LocalCatalogFile(Credentials credentials, List<Asset> assets) {

  public LocalCatalogFile {
    assets = assets == null ? List.of() : List.copyOf(assets);
    credentials = credentials == null ? Credentials.defaults() : credentials;
  }

  public enum CredentialMode {
    FAKE,
    STATIC
  }

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
      String subtype,
      String storageLocation,
      String metadataLocation,
      String format,
      String schema,
      List<String> partitionColumns,
      List<String> accessModes,
      String catalogAssetId,
      List<String> auxiliaryLocations,
      List<String> sharableBy) {

    public Asset {
      if (identifier == null || identifier.isBlank()) {
        throw new IllegalArgumentException("local catalog asset is missing 'identifier'");
      }
      type = type == null ? AssetType.TABLE : type;
      partitionColumns = partitionColumns == null ? List.of() : List.copyOf(partitionColumns);
      accessModes = accessModes == null ? List.of() : List.copyOf(accessModes);
      auxiliaryLocations = auxiliaryLocations == null ? List.of() : List.copyOf(auxiliaryLocations);
      sharableBy = sharableBy == null ? List.of() : List.copyOf(sharableBy);
      TableFormat.fromWireName(format);
      accessModes.forEach(LocalCatalogFile::parseAccessMode);
    }
  }

  static AccessMode parseAccessMode(String value) {
    try {
      return AccessMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unsupported access mode '" + value + "'", e);
    }
  }
}
