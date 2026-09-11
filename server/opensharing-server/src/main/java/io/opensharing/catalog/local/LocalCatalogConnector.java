package io.opensharing.catalog.local;

import io.opensharing.catalog.AccessMode;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableFormat;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** File-backed catalog for local testing: YAML lookup and placeholder or static credentials. */
public final class LocalCatalogConnector implements CatalogConnector {

  public static final String NAME = "local";

  private static final Logger log = LoggerFactory.getLogger(LocalCatalogConnector.class);
  private static final Duration DEFAULT_TTL = Duration.ofHours(1);
  private static final char[] ALPHANUMERIC =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

  private final Map<String, LocalCatalogFile.Asset> assetsByIdentifier;
  private final LocalCatalogFile.Credentials credentials;
  private final SecureRandom random = new SecureRandom();

  public LocalCatalogConnector(LocalCatalogFile file) {
    this.credentials = file.credentials();
    this.assetsByIdentifier =
        file.assets().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    asset -> key(asset.type(), asset.identifier()),
                    asset -> asset,
                    (a, b) -> {
                      throw new CatalogException(
                          "duplicate asset '" + b.identifier() + "' in local catalog file");
                    }));
    if (credentials.mode() == LocalCatalogFile.CredentialMode.FAKE) {
      log.warn(
          "Local catalog connector is vending placeholder {} credentials that grant no access to "
              + "real storage. Use a real catalog connector for anything but local testing.",
          credentials.provider());
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller) {
    LocalCatalogFile.Asset asset = assetsByIdentifier.get(key(lookup.type(), lookup.identifier()));
    if (asset == null) {
      throw new AssetNotFoundException(lookup);
    }
    if (!allows(asset, caller)) {
      throw new AssetAccessDeniedException(lookup, caller);
    }
    return resolved(asset);
  }

  /** Tables whose identifier is this schema plus one more dotted segment. */
  @Override
  public List<ResolvedAsset> listChildren(AssetLookup parent, CatalogCaller caller) {
    if (parent.type() != AssetType.SCHEMA) {
      throw new UnsupportedAssetTypeException(
          "the " + NAME + " catalog only lists the contents of a SCHEMA, not a " + parent.type());
    }
    if (!assetsByIdentifier.containsKey(key(parent.type(), parent.identifier()))) {
      throw new AssetNotFoundException(parent);
    }
    String prefix = parent.identifier().toLowerCase(Locale.ROOT) + ".";
    return assetsByIdentifier.values().stream()
        .filter(asset -> asset.type() == AssetType.TABLE)
        .filter(asset -> isChildOf(asset.identifier(), prefix))
        .sorted(Comparator.comparing(asset -> asset.identifier().toLowerCase(Locale.ROOT)))
        .map(LocalCatalogConnector::resolved)
        .toList();
  }

  private static boolean isChildOf(String identifier, String schemaPrefix) {
    String folded = identifier.toLowerCase(Locale.ROOT);
    return folded.startsWith(schemaPrefix) && !folded.substring(schemaPrefix.length()).contains(".");
  }

  private static ResolvedAsset resolved(LocalCatalogFile.Asset asset) {
    return ResolvedAsset.builder(asset.type(), asset.identifier())
        .catalogAssetId(asset.catalogAssetId() != null ? asset.catalogAssetId() : asset.identifier())
        .storageLocation(asset.storageLocation())
        .metadataLocation(asset.metadataLocation())
        .format(TableFormat.fromWireName(asset.format()))
        .schema(asset.schema())
        .partitionColumns(asset.partitionColumns())
        .subtype(asset.subtype())
        .accessModes(accessModes(asset))
        .auxiliaryLocations(asset.auxiliaryLocations())
        .build();
  }

  /** Empty {@code sharableBy} means anyone; otherwise the caller name must match. */
  private static boolean allows(LocalCatalogFile.Asset asset, CatalogCaller caller) {
    if (asset.sharableBy().isEmpty()) {
      return true;
    }
    return asset.sharableBy().stream().anyMatch(name -> name.equalsIgnoreCase(caller.name()));
  }

  /** One credential prefix: the requested storage location. */
  @Override
  public List<StorageCredentials> getStorageCredentials(
      CredentialRequest request, CatalogCaller caller) {
    if (request.storageLocation() == null || request.storageLocation().isBlank()) {
      throw new CatalogException(
          "asset '" + request.identifier() + "' has no storage location to scope credentials to");
    }
    Duration ttl = request.ttl() != null ? request.ttl() : configuredTtl();
    Instant expiration = Instant.now().plus(ttl);
    CloudProvider provider = credentials.provider();
    Map<String, String> values =
        credentials.mode() == LocalCatalogFile.CredentialMode.STATIC
            ? staticValues(provider)
            : fakeValues(provider, expiration);
    return List.of(
        new StorageCredentials(request.storageLocation(), provider, values, expiration));
  }

  private Duration configuredTtl() {
    Integer seconds = credentials.ttlSeconds();
    return seconds == null ? DEFAULT_TTL : Duration.ofSeconds(seconds);
  }

  private Map<String, String> staticValues(CloudProvider provider) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : requiredKeys(provider)) {
      String value = credentials.values().get(key);
      if (value == null || value.isBlank()) {
        throw new CatalogException(
            "local catalog credentials.mode is STATIC but credentials.values is missing '"
                + key
                + "' for provider "
                + provider);
      }
      values.put(key, value);
    }
    return values;
  }

  private Map<String, String> fakeValues(CloudProvider provider, Instant expiration) {
    Map<String, String> values = new HashMap<>();
    switch (provider) {
      case AWS, R2 -> {
        values.put(
            StorageCredentialKeys.ACCESS_KEY_ID,
            "ASIA" + randomString(16).toUpperCase(Locale.ROOT));
        values.put(StorageCredentialKeys.SECRET_ACCESS_KEY, randomString(40));
        values.put(StorageCredentialKeys.SESSION_TOKEN, "local-fake-session-" + randomString(48));
      }
      case AZURE ->
          values.put(
              StorageCredentialKeys.SAS_TOKEN,
              "sv=2024-11-04&se=" + expiration + "&sp=rl&sig=" + randomString(32));
      case GCP ->
          values.put(StorageCredentialKeys.OAUTH_TOKEN, "ya29.local-fake-" + randomString(32));
    }
    return values;
  }

  private static List<String> requiredKeys(CloudProvider provider) {
    return switch (provider) {
      case AWS, R2 ->
          List.of(
              StorageCredentialKeys.ACCESS_KEY_ID,
              StorageCredentialKeys.SECRET_ACCESS_KEY,
              StorageCredentialKeys.SESSION_TOKEN);
      case AZURE -> List.of(StorageCredentialKeys.SAS_TOKEN);
      case GCP -> List.of(StorageCredentialKeys.OAUTH_TOKEN);
    };
  }

  /** Modes from the file, or {@code DIR} when the asset has a storage location. */
  private static Set<AccessMode> accessModes(LocalCatalogFile.Asset asset) {
    if (!asset.accessModes().isEmpty()) {
      return asset.accessModes().stream()
          .map(LocalCatalogFile::parseAccessMode)
          .collect(Collectors.toUnmodifiableSet());
    }
    boolean hasLocation =
        asset.storageLocation() != null && !asset.storageLocation().isBlank();
    return hasLocation ? Set.of(AccessMode.DIR) : Set.of();
  }

  private String randomString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]);
    }
    return sb.toString();
  }

  private static String key(AssetType type, String identifier) {
    return type + ":" + identifier.toLowerCase(Locale.ROOT);
  }
}
