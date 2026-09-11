package io.opensharing.catalog.local;

import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.TableFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-disk description of a catalog, used to run the sharing server without a real catalog.
 *
 * <pre>{@code
 * credentials:
 *   provider: AWS
 *   mode: FAKE
 *   ttlSeconds: 3600
 * assets:
 *   - identifier: main.sales
 *     type: SCHEMA
 *   - identifier: main.sales.orders
 *     type: TABLE
 *     storageLocation: s3://acme-lake/sales/orders/
 *     format: delta
 *     sharableBy: [alice@example.com]
 * }</pre>
 *
 * <p>The file is flat, so a schema holds whatever names it: {@code main.sales} contains {@code
 * main.sales.orders}. That is enough for a schema to be shared as a whole.
 */
public record LocalCatalogFile(Credentials credentials, List<Asset> assets) {

  public LocalCatalogFile {
    assets = assets == null ? List.of() : List.copyOf(assets);
    credentials = credentials == null ? Credentials.defaults() : credentials;
  }

  /** How this connector mints credentials. */
  public enum CredentialMode {
    /** Generate syntactically plausible values that grant no real access. */
    FAKE,
    /** Return the values configured under {@code credentials.values} verbatim. */
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

  /**
   * @param subtype the catalog's refinement of the type, e.g. {@code VIEW}
   * @param storageLocation where the bytes are. A {@code SCHEMA} needs none, since nothing is read
   *     from a schema itself.
   * @param metadataLocation pointer a client needs to interpret the bytes, e.g. an Iceberg metadata
   *     JSON
   * @param schema the asset's schema as this catalog states it, in whatever representation it uses
   * @param partitionColumns the columns the asset is partitioned by, in order
   * @param sharableBy principal names allowed to share this asset. Empty means anyone may.
   */
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
      // Parse the enum-valued fields here so a typo fails the file at load instead of at the
      // first request that touches this asset.
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
