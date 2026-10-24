package io.opensharing.catalog.local;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.common.sas.SasProtocol;
import com.azure.storage.file.datalake.DataLakeFileSystemClientBuilder;
import com.azure.storage.file.datalake.sas.DataLakeServiceSasSignatureValues;
import com.azure.storage.file.datalake.sas.PathSasPermission;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.CredentialAccessBoundary;
import com.google.auth.oauth2.DownscopedCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetLocation;
import io.opensharing.catalog.AssetPage;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableProperties;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * File-backed catalog for local testing: YAML lookup and placeholder, static, or environment
 * credentials.
 */
public final class LocalCatalogConnector implements CatalogConnector {

  public static final String NAME = "local";

  private static final Logger log = LoggerFactory.getLogger(LocalCatalogConnector.class);
  private static final Duration DEFAULT_TTL = Duration.ofHours(1);
  private static final String CLOUD_PLATFORM_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";
  private static final char[] ALPHANUMERIC =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

  private final Map<String, LocalCatalogFile.Asset> assetsByIdentifier;
  private final List<LocalCatalogFile.Principal> principals;
  private final LocalCatalogFile.Credentials credentials;
  private final SecureRandom random = new SecureRandom();

  public LocalCatalogConnector(LocalCatalogFile file) {
    this.credentials = file.credentials();
    this.principals = file.principals();
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
  public ResolvedAsset resolveAsset(Asset lookup, AuthContext auth) {
    return resolved(requireAsset(lookup, auth));
  }

  /**
   * Tables whose identifier is this schema plus one more dotted segment, sorted by identifier. The
   * whole listing is in memory, so a page token is just the offset of the page's first table.
   */
  @Override
  public AssetPage listChildren(Asset parent, int maxResults, String pageToken, AuthContext auth) {
    if (maxResults <= 0) {
      throw new IllegalArgumentException("maxResults must be positive");
    }
    if (parent.type() != AssetType.SCHEMA) {
      throw new UnsupportedAssetTypeException(
          "the " + NAME + " catalog only lists the contents of a SCHEMA, not a " + parent.type());
    }
    requireAsset(parent, auth);
    String prefix = parent.fullName().toLowerCase(Locale.ROOT) + ".";
    List<ResolvedAsset> children =
        assetsByIdentifier.values().stream()
            .filter(asset -> asset.type() == AssetType.TABLE)
            .filter(asset -> isChildOf(asset.identifier(), prefix))
            .filter(asset -> allows(asset, auth))
            .sorted(Comparator.comparing(asset -> asset.identifier().toLowerCase(Locale.ROOT)))
            .map(LocalCatalogConnector::resolved)
            .toList();
    int start = offset(pageToken, children.size());
    int end = (int) Math.min((long) start + maxResults, children.size());
    return new AssetPage(
        children.subList(start, end), end < children.size() ? Integer.toString(end) : null);
  }

  private static int offset(String pageToken, int size) {
    if (pageToken == null) {
      return 0;
    }
    try {
      int offset = Integer.parseInt(pageToken);
      if (offset >= 0 && offset <= size) {
        return offset;
      }
    } catch (NumberFormatException ignored) {
      // Reported below like any other token this connector did not issue.
    }
    throw new IllegalArgumentException("pageToken is invalid");
  }

  private static boolean isChildOf(String identifier, String schemaPrefix) {
    String folded = identifier.toLowerCase(Locale.ROOT);
    return folded.startsWith(schemaPrefix) && !folded.substring(schemaPrefix.length()).contains(".");
  }

  private static ResolvedAsset resolved(LocalCatalogFile.Asset asset) {
    return ResolvedAsset.builder(asset.type(), asset.identifier())
        .catalogAssetId(asset.catalogAssetId() != null ? asset.catalogAssetId() : asset.identifier())
        .location(new AssetLocation(asset.storageLocation(), asset.auxiliaryLocations()))
        .additionalProperties(
            asset.type() == AssetType.TABLE
                ? new TableProperties(
                    DataSourceFormat.fromWireName(asset.format()), asset.attributes())
                : null)
        .build();
  }

  /**
   * Empty {@code shareableBy} means anyone; otherwise it lists user ids. The user name is display
   * only and never grants access.
   */
  private static boolean allows(LocalCatalogFile.Asset asset, AuthContext auth) {
    if (asset.shareableBy().isEmpty()) {
      return true;
    }
    UserContext user = auth == null ? null : auth.user();
    return user != null && asset.shareableBy().contains(user.userId());
  }

  private LocalCatalogFile.Asset requireAsset(Asset lookup, AuthContext auth) {
    LocalCatalogFile.Asset asset = assetsByIdentifier.get(key(lookup.type(), lookup.fullName()));
    if (asset == null) {
      throw new AssetNotFoundException(lookup);
    }
    if (!allows(asset, auth)) {
      throw new AssetAccessDeniedException(lookup, auth == null ? null : auth.user());
    }
    return asset;
  }

  @Override
  public List<StorageCredentials> getStorageCredentials(
      CredentialRequest request, AuthContext auth) {
    LocalCatalogFile.Asset asset =
        requireAsset(new Asset(request.assetType(), request.assetFullName()), auth);
    String location = request.storageLocation();
    if (location == null || location.isBlank()) {
      location = asset.storageLocation();
    }
    if (location == null || location.isBlank()) {
      throw new CatalogException(
          "asset '"
              + request.assetFullName()
              + "' has no storage location to scope credentials to");
    }
    if (!covers(asset, location)) {
      throw new CatalogException(
          "storage location '"
              + location
              + "' is not part of asset '"
              + request.assetFullName()
              + "'");
    }
    Duration ttl = request.ttl() != null ? request.ttl() : configuredTtl();
    Instant expiration = Instant.now().plus(ttl);
    if (credentials.mode() == LocalCatalogFile.CredentialMode.ENV) {
      return List.of(envCredentials(location, expiration));
    }
    CloudProvider provider = credentials.provider();
    Map<String, String> values =
        credentials.mode() == LocalCatalogFile.CredentialMode.STATIC
            ? staticValues(provider)
            : fakeValues(provider, expiration);
    return List.of(new StorageCredentials(location, provider, values, expiration));
  }

  @Override
  public UserContext authorize(AuthContext auth, Privilege privilege) {
    if (principals.isEmpty()) {
      throw new UnsupportedOperationException(
          "the " + NAME + " catalog has no principals configured to authorize");
    }
    String token = auth == null || auth.user() == null ? null : auth.user().userAuthToken();
    if (token == null || token.isBlank()) {
      throw new CatalogAuthenticationException("missing bearer token");
    }
    LocalCatalogFile.Principal principal =
        principals.stream()
            .filter(candidate -> token.equals(candidate.bearerToken()))
            .findFirst()
            .orElseThrow(() -> new CatalogAuthenticationException("invalid bearer token"));
    if (privilege != null && !principal.privileges().contains(privilege)) {
      throw new CatalogAuthorizationException(
          "user '" + principal.userId() + "' does not hold " + privilege);
    }
    return new UserContext(principal.userId(), principal.bearerToken(), principal.userName());
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

  /**
   * Credentials minted from the environment, in the form a production catalog vends: AWS keys, an
   * Azure SAS token, or a Google OAuth token.
   */
  private static StorageCredentials envCredentials(String location, Instant expiration) {
    CloudProvider provider = providerFor(location);
    return switch (provider) {
      case AWS, R2 -> new StorageCredentials(location, provider, awsEnvValues(), expiration);
      case AZURE ->
          new StorageCredentials(
              location,
              provider,
              Map.of(StorageCredentials.SAS_TOKEN, directorySas(location, expiration)),
              expiration);
      case GCP -> googleCredentials(location, expiration);
    };
  }

  private static Map<String, String> awsEnvValues() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put(StorageCredentials.ACCESS_KEY_ID, requireEnv("AWS_ACCESS_KEY_ID"));
    values.put(StorageCredentials.SECRET_ACCESS_KEY, requireEnv("AWS_SECRET_ACCESS_KEY"));
    String region = firstEnv("AWS_REGION", "AWS_DEFAULT_REGION");
    if (region != null) {
      values.put(StorageCredentials.REGION, region);
    }
    String session = firstEnv("AWS_SESSION_TOKEN");
    if (session != null) {
      values.put(StorageCredentials.SESSION_TOKEN, session);
    }
    return values;
  }

  /**
   * A read and list SAS for the location's directory and everything under it, signed with
   * AZURE_TEST_ACCOUNT_KEY. Directory SAS tokens need a hierarchical namespace account.
   */
  private static String directorySas(String location, Instant expiration) {
    URI uri = URI.create(location);
    String container = uri.getUserInfo();
    String host = uri.getHost();
    if (container == null || host == null || !host.contains(".")) {
      throw new CatalogException(
          "Azure storage location '" + location + "' has no container or account host");
    }
    String account = host.substring(0, host.indexOf('.'));
    DataLakeServiceSasSignatureValues values =
        new DataLakeServiceSasSignatureValues(
                OffsetDateTime.ofInstant(expiration, ZoneOffset.UTC),
                new PathSasPermission().setReadPermission(true).setListPermission(true))
            .setProtocol(SasProtocol.HTTPS_ONLY);
    // Generating a SAS sends no request, so the client gets a transport that refuses them. The
    // directory client comes from the file system client: a builder path counts as one segment
    // in the SAS depth, so the token would not authenticate.
    return new DataLakeFileSystemClientBuilder()
        .httpClient(request -> Mono.error(new UnsupportedOperationException("no requests")))
        .endpoint("https://" + host)
        .fileSystemName(container)
        .credential(new StorageSharedKeyCredential(account, requireEnv("AZURE_TEST_ACCOUNT_KEY")))
        .buildClient()
        .getDirectoryClient(directory(uri, location))
        .generateSas(values);
  }

  /**
   * A Google OAuth token from application default credentials, which read
   * GOOGLE_APPLICATION_CREDENTIALS, downscoped to reading and listing objects under the location.
   * The token's own expiry wins when it comes first.
   */
  private static StorageCredentials googleCredentials(String location, Instant expiration) {
    URI uri = URI.create(location);
    String bucket = uri.getHost();
    if (bucket == null) {
      throw new CatalogException("GCS storage location '" + location + "' has no bucket");
    }
    String directory = directory(uri, location);
    String objects = "projects/_/buckets/" + bucket + "/objects/" + directory;
    String listPrefix = "api.getAttribute('storage.googleapis.com/objectListPrefix', '')";
    CredentialAccessBoundary boundary =
        CredentialAccessBoundary.newBuilder()
            .addRule(
                CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
                    .setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucket)
                    .addAvailablePermission("inRole:roles/storage.objectViewer")
                    .setAvailabilityCondition(
                        CredentialAccessBoundary.AccessBoundaryRule.AvailabilityCondition
                            .newBuilder()
                            // List prefixes include the trailing slash so table1 does not
                            // authorize listing table10.
                            .setExpression(
                                "resource.name == '" + objects + "'"
                                    + " || resource.name.startsWith('" + objects + "/')"
                                    + " || " + listPrefix + ".startsWith('" + directory + "/')")
                            .build())
                    .build())
            .build();
    AccessToken token;
    try {
      token =
          DownscopedCredentials.newBuilder()
              .setSourceCredential(
                  GoogleCredentials.getApplicationDefault().createScoped(CLOUD_PLATFORM_SCOPE))
              .setCredentialAccessBoundary(boundary)
              .build()
              .refreshAccessToken();
    } catch (IOException e) {
      throw new CatalogException(
          "Google application default credentials cannot mint an OAuth token", e);
    }
    Instant tokenExpiry =
        token.getExpirationTime() == null ? null : token.getExpirationTime().toInstant();
    return new StorageCredentials(
        location,
        CloudProvider.GCP,
        Map.of(StorageCredentials.OAUTH_TOKEN, token.getTokenValue()),
        tokenExpiry != null && tokenExpiry.isBefore(expiration) ? tokenExpiry : expiration);
  }

  /** The location's path without its leading and trailing slashes. */
  private static String directory(URI uri, String location) {
    String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
    if (path.isEmpty()) {
      throw new CatalogException(
          "storage location '" + location + "' has no directory to scope credentials to");
    }
    return path;
  }

  private static CloudProvider providerFor(String location) {
    String scheme = URI.create(location).getScheme();
    if (scheme == null) {
      throw new CatalogException("storage location '" + location + "' has no URI scheme");
    }
    return switch (scheme.toLowerCase(Locale.ROOT)) {
      case "s3", "s3a" -> CloudProvider.AWS;
      case "abfs", "abfss", "wasb", "wasbs" -> CloudProvider.AZURE;
      case "gs" -> CloudProvider.GCP;
      default ->
          throw new CatalogException(
              "cannot select environment credentials for storage scheme '" + scheme + "'");
    };
  }

  private static String requireEnv(String name) {
    String value = firstEnv(name);
    if (value == null) {
      throw new CatalogException(
          "local catalog credentials.mode is ENV but environment variable '" + name + "' is unset");
    }
    return value;
  }

  private static String firstEnv(String... names) {
    for (String name : names) {
      String value = System.getenv(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
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
