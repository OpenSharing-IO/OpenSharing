package io.opensharing.catalog.local;

import io.opensharing.auth.UserContext;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
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
  public ResolvedAsset resolveAsset(AssetLookup lookup, UserContext user) {
    return resolved(requireAsset(lookup, user));
  }

  /** Tables whose identifier is this schema plus one more dotted segment. */
  @Override
  public List<ResolvedAsset> listChildren(AssetLookup parent, UserContext user) {
    if (parent.type() != AssetType.SCHEMA) {
      throw new UnsupportedAssetTypeException(
          "the " + NAME + " catalog only lists the contents of a SCHEMA, not a " + parent.type());
    }
    requireAsset(parent, user);
    String prefix = parent.identifier().toLowerCase(Locale.ROOT) + ".";
    return assetsByIdentifier.values().stream()
        .filter(asset -> asset.type() == AssetType.TABLE)
        .filter(asset -> isChildOf(asset.identifier(), prefix))
        .filter(asset -> allows(asset, user))
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
        .auxiliaryLocations(asset.auxiliaryLocations())
        .build();
  }

  /** Empty {@code sharableBy} means anyone; otherwise the user name must match. */
  private static boolean allows(LocalCatalogFile.Asset asset, UserContext user) {
    if (asset.sharableBy().isEmpty()) {
      return true;
    }
    return asset.sharableBy().stream().anyMatch(name -> name.equalsIgnoreCase(user.name()));
  }

  private LocalCatalogFile.Asset requireAsset(AssetLookup lookup, UserContext user) {
    LocalCatalogFile.Asset asset = assetsByIdentifier.get(key(lookup.type(), lookup.identifier()));
    if (asset == null) {
      throw new AssetNotFoundException(lookup);
    }
    if (!allows(asset, user)) {
      throw new AssetAccessDeniedException(lookup, user);
    }
    return asset;
  }

  @Override
  public List<StorageCredentials> getStorageCredentials(
      CredentialRequest request, UserContext user) {
    LocalCatalogFile.Asset asset =
        requireAsset(AssetLookup.of(request.assetType(), request.identifier()), user);
    String location = request.storageLocation();
    if (location == null || location.isBlank()) {
      location = asset.storageLocation();
    }
    if (location == null || location.isBlank()) {
      throw new CatalogException(
          "asset '" + request.identifier() + "' has no storage location to scope credentials to");
    }
    if (!covers(asset, location)) {
      throw new CatalogException(
          "storage location '"
              + location
              + "' is not part of asset '"
              + request.identifier()
              + "'");
    }
    Duration ttl = request.ttl() != null ? request.ttl() : configuredTtl();
    Instant expiration = Instant.now().plus(ttl);
    CloudProvider provider = credentials.provider();
    Map<String, String> values =
        credentials.mode() == LocalCatalogFile.CredentialMode.STATIC
            ? staticValues(provider)
            : fakeValues(provider, expiration);
    return List.of(new StorageCredentials(location, provider, values, expiration));
  }

  private static boolean covers(LocalCatalogFile.Asset asset, String location) {
    if (location.equals(asset.storageLocation())) {
      return true;
    }
    return asset.auxiliaryLocations().contains(location);
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
    if (provider == CloudProvider.AWS || provider == CloudProvider.R2) {
      copyIfPresent(values, StorageCredentials.SESSION_TOKEN);
      copyIfPresent(values, StorageCredentials.REGION);
    }
    return values;
  }

  private void copyIfPresent(Map<String, String> values, String key) {
    String value = credentials.values().get(key);
    if (value != null && !value.isBlank()) {
      values.put(key, value);
    }
  }

  private Map<String, String> fakeValues(CloudProvider provider, Instant expiration) {
    Map<String, String> values = new HashMap<>();
    switch (provider) {
      case AWS, R2 -> {
        values.put(
            StorageCredentials.ACCESS_KEY_ID,
            "ASIA" + randomString(16).toUpperCase(Locale.ROOT));
        values.put(StorageCredentials.SECRET_ACCESS_KEY, randomString(40));
        values.put(StorageCredentials.SESSION_TOKEN, "local-fake-session-" + randomString(48));
      }
      case AZURE ->
          values.put(
              StorageCredentials.SAS_TOKEN,
              "sv=2024-11-04&se=" + expiration + "&sp=rl&sig=" + randomString(32));
      case GCP ->
          values.put(StorageCredentials.OAUTH_TOKEN, "ya29.local-fake-" + randomString(32));
    }
    return values;
  }

  private static List<String> requiredKeys(CloudProvider provider) {
    return switch (provider) {
      case AWS, R2 ->
          List.of(StorageCredentials.ACCESS_KEY_ID, StorageCredentials.SECRET_ACCESS_KEY);
      case AZURE -> List.of(StorageCredentials.SAS_TOKEN);
      case GCP -> List.of(StorageCredentials.OAUTH_TOKEN);
    };
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
