package io.opensharing.catalog.local;

import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableFormat;
import io.opensharing.catalog.UnsupportedAssetTypeException;
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

/**
 * Catalog backed by a declarative file. It exists so the sharing server can be exercised
 * end-to-end without a running catalog or cloud account: asset resolution is a lookup and
 * credential vending returns either configured static values or generated placeholders.
 */
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

  /**
   * A file-backed catalog has no hierarchy of its own, so containment is read off the identifiers: an
   * asset belongs to a schema when its identifier is the schema's plus one more level. That keeps the
   * file flat, which is the point of it.
   */
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

  /**
   * The file's {@code sharableBy} names who may share an asset, and every request names somebody, so
   * the same list answers both times it is consulted: when a provider adds the asset, and on each read
   * of it, which is asked for the owner of the share being read through. Taking a principal off the
   * list therefore stops what they already shared as well as what they might share next, which is the
   * revocation a real catalog would apply. The credential that arrives with the name is ignored: this
   * file authenticates nobody, it only recognizes names.
   */
  private static boolean allows(LocalCatalogFile.Asset asset, CatalogCaller caller) {
    if (asset.sharableBy().isEmpty()) {
      return true;
    }
    return asset.sharableBy().stream().anyMatch(name -> name.equalsIgnoreCase(caller.name()));
  }

  /**
   * This connector scopes to the one location it was asked about, so the list has one element.
   *
   * <p>The caller is ignored, and not only because this file authenticates nobody: minting is always
   * preceded by a resolution of the same asset as the same caller, which is where {@code sharableBy}
   * is consulted, so asking it again here would ask a question already answered.
   */
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

  /**
   * What the file says, or directory access for anything with a location: this connector vends for
   * every location it is given, real or fake, so there is no location it cannot offer the mode for.
   */
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
