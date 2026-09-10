package io.opensharing.catalog.unity;

import io.opensharing.asset.storage.StoragePaths;
import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogAuthorizationException;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.CatalogPrincipal;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableFormat;
import io.opensharing.catalog.UnsupportedAssetTypeException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalog backed by open-source Unity Catalog, over its REST API.
 *
 * <p>Three calls carry it, which is the whole integration: {@code GET /tables/{full_name}} to find
 * out where a table lives, {@code POST /temporary-table-credentials} to be let into that storage, and
 * {@code GET /tables?catalog_name=&schema_name=} to say what a shared schema holds today. Each is made
 * with the caller's own credential as a bearer token, so Unity Catalog decides whether a provider may
 * share a table and whether a share owner may still be read through, and this connector never has to
 * form an opinion about privileges it cannot see.
 *
 * <p>What it deliberately does not do:
 *
 * <ul>
 *   <li><b>Iceberg.</b> Unity Catalog's {@code DataSourceFormat} has no Iceberg member, so a table
 *       shared through here is Delta or Parquet. Iceberg tables in a Unity Catalog are reached
 *       through its own Iceberg REST endpoint, which is a different connector's job.
 *   <li><b>Volumes, models.</b> Unity Catalog holds them and has credential endpoints for them, but
 *       this server shares tables and schemas, so asking for anything else is refused here rather
 *       than answered with something the layers above would not know what to do with.
 *   <li><b>Columns.</b> The table's schema comes back on every response and is not read: nothing in
 *       this server uses a catalog-stated schema, since a Delta table's own log is the authority on
 *       its shape. The partition columns are kept, being cheap and stated plainly.
 *   <li><b>Access modes.</b> Which of {@code dir} and {@code url} a table can be read by is settled
 *       above, from the format and what this build can serve, and no catalog has a say in it.
 * </ul>
 */
public final class UnityCatalogConnector implements CatalogConnector {

  public static final String NAME = "unity";

  private static final Logger log = LoggerFactory.getLogger(UnityCatalogConnector.class);

  /** The most tables Unity Catalog will return in one page of a listing. */
  private static final String PAGE_SIZE = "50";

  /**
   * A ceiling on a schema listing, so that a catalog answering with a page token forever cannot walk
   * this server out of memory. Ten thousand tables in one schema is already past what anyone would
   * sensibly share as a whole.
   */
  private static final int MAX_PAGES = 200;

  private final UnityCatalogClient client;

  public UnityCatalogConnector(URI uri, Duration connectTimeout, Duration requestTimeout) {
    this(new UnityCatalogClient(uri, connectTimeout, requestTimeout));
  }

  /**
   * @param serverSecret this connector's own identity for on-behalf-of catalog access (a
   *     recipient's read, made as the share owner with no token of theirs to present) — see {@link
   *     CatalogCaller.Credential.OnBehalfOf}. Null if this connector never needs to make one, which
   *     is only true of a deployment that never serves a recipient read against this catalog.
   */
  public UnityCatalogConnector(
      URI uri, Duration connectTimeout, Duration requestTimeout, String serverSecret) {
    this(new UnityCatalogClient(uri, connectTimeout, requestTimeout, serverSecret));
  }

  UnityCatalogConnector(UnityCatalogClient client) {
    this.client = client;
    log.info("Unity Catalog connector will resolve assets at {}", client.baseUri());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@code GET /opensharing/authorize} — the one call this connector makes with no
   * {@link CatalogCaller} of its own to authenticate as, because producing one is the point.
   */
  @Override
  public CatalogPrincipal authorize(String bearerToken, String privilege) {
    return client
        .authorize(bearerToken, privilege)
        .filter(UnityCatalogClient.AuthorizeResult::authorized)
        .map(result -> new CatalogPrincipal(result.userId(), result.userName()))
        .orElseThrow(
            () ->
                new CatalogAuthorizationException(
                    "the Unity Catalog does not recognize this bearer token"
                        + (privilege == null ? "" : ", or it may not " + privilege)));
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller) {
    return switch (lookup.type()) {
      case TABLE -> table(lookup, caller);
      case SCHEMA -> schema(lookup, caller);
      case VOLUME, MODEL, SKILL ->
          throw new UnsupportedAssetTypeException(
              "the "
                  + NAME
                  + " catalog connector resolves tables and schemas, not a "
                  + lookup.type());
    };
  }

  private ResolvedAsset table(AssetLookup lookup, CatalogCaller caller) {
    String fullName = qualifiedName(lookup, 3, "catalog.schema.table");
    UnityCatalogApi.TableInfo info =
        ask(
            lookup,
            caller,
            () ->
                client.get(
                    "/tables/" + UnityCatalogClient.encode(fullName),
                    Map.of(),
                    caller,
                    UnityCatalogApi.TableInfo.class));
    String refused = unshareable(info);
    if (refused != null) {
      throw new UnsupportedAssetTypeException("'" + lookup.identifier() + "' " + refused);
    }
    return resolved(lookup.identifier(), info);
  }

  /**
   * A schema resolves to nothing physical, and is meant to: the grant stands for whatever tables the
   * catalog holds in it when someone asks, which is what {@link #listChildren} answers.
   */
  private ResolvedAsset schema(AssetLookup lookup, CatalogCaller caller) {
    String fullName = qualifiedName(lookup, 2, "catalog.schema");
    UnityCatalogApi.SchemaInfo info =
        ask(
            lookup,
            caller,
            () ->
                client.get(
                    "/schemas/" + UnityCatalogClient.encode(fullName),
                    Map.of(),
                    caller,
                    UnityCatalogApi.SchemaInfo.class));
    return ResolvedAsset.builder(AssetType.SCHEMA, lookup.identifier())
        .catalogAssetId(info.schemaId())
        .build();
  }

  /**
   * The tables of a schema, page by page, leaving out the ones this server could not serve if a
   * recipient asked for them.
   *
   * <p>Leaving them out rather than failing the listing is the difference between this and adding a
   * table by name: a provider who names a table is told why it cannot be shared, but a provider who
   * shares a schema is offering whatever is in it, and one unreadable table among a hundred should not
   * take the other ninety-nine down with it.
   */
  @Override
  public List<ResolvedAsset> listChildren(AssetLookup parent, CatalogCaller caller) {
    if (parent.type() != AssetType.SCHEMA) {
      throw new UnsupportedAssetTypeException(
          "the " + NAME + " catalog only lists the contents of a SCHEMA, not a " + parent.type());
    }
    String[] parts = qualifiedName(parent, 2, "catalog.schema").split("\\.");
    List<ResolvedAsset> tables = new ArrayList<>();
    String pageToken = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      String token = pageToken;
      UnityCatalogApi.ListTablesResponse response =
          ask(
              parent,
              caller,
              () ->
                  client.get(
                      "/tables",
                      UnityCatalogClient.query(
                          "catalog_name", parts[0],
                          "schema_name", parts[1],
                          "max_results", PAGE_SIZE,
                          "page_token", token),
                      caller,
                      UnityCatalogApi.ListTablesResponse.class));
      collect(parent, response, tables);
      pageToken = response.nextPageToken();
      if (pageToken == null || pageToken.isBlank() || pageToken.equals(token)) {
        return tables;
      }
    }
    throw new CatalogException(
        "'"
            + parent.identifier()
            + "' holds more tables than this server will list for one shared schema; share its "
            + "tables individually");
  }

  /**
   * The shareable tables of one page, each counted once. A schema holds no two tables of the same
   * name, so a name arriving twice is a catalog whose pages overlap — one that repeats a page token
   * hands out the same page again — and listing it twice would show a recipient two of the same
   * table.
   */
  private void collect(
      AssetLookup parent, UnityCatalogApi.ListTablesResponse page, List<ResolvedAsset> into) {
    if (page.tables() == null) {
      return;
    }
    Set<String> listed = into.stream().map(ResolvedAsset::identifier).collect(Collectors.toSet());
    for (UnityCatalogApi.TableInfo info : page.tables()) {
      String identifier = info.fullName();
      String refused =
          identifier == null
              ? "is named only in part by the catalog"
              : listed.contains(identifier) ? "was listed by an earlier page already" : unshareable(info);
      if (refused != null) {
        log.debug(
            "Leaving '{}' out of the tables of '{}': it {}",
            identifier == null ? info.name() : identifier,
            parent.identifier(),
            refused);
        continue;
      }
      into.add(resolved(identifier, info));
    }
  }

  /**
   * Why a recipient could not be served this table, or null when they could.
   *
   * <p>One judgement, made in one place, because the two callers want it phrased the same way and
   * differ only in what they do with the answer: refuse the table, or pass over it.
   */
  private static String unshareable(UnityCatalogApi.TableInfo info) {
    if (isBlank(info.storageLocation())) {
      String type = isBlank(info.tableType()) ? "a table type" : "a " + info.tableType();
      return "is "
          + type
          + " with no storage location in Unity Catalog, so there is nothing to point a recipient at";
    }
    if (format(info.dataSourceFormat()) == null) {
      return "is "
          + (isBlank(info.dataSourceFormat()) ? "of no stated format" : info.dataSourceFormat())
          + " in Unity Catalog, and this server shares Delta and Parquet tables";
    }
    return null;
  }

  private static ResolvedAsset resolved(String identifier, UnityCatalogApi.TableInfo info) {
    return ResolvedAsset.builder(AssetType.TABLE, identifier)
        .catalogAssetId(info.tableId())
        .storageLocation(info.storageLocation())
        .format(format(info.dataSourceFormat()))
        .partitionColumns(partitionColumns(info))
        .subtype(info.tableType())
        .accessModes(directoryAccess(info))
        .build();
  }

  /**
   * Directory access is offered for a table Unity Catalog will mint a credential this server can read
   * for, which is a table on one of the three clouds below.
   *
   * <p>It is not offered for a table on the filesystem the server runs on: Unity Catalog holds no
   * grant to hand out for a local path, and a mode a recipient cannot get credentials for is not one
   * to advertise. Such a table is still served by url, the mode that suits it anyway.
   *
   * <p>Nor for storage this build would not understand the answer about. Unity Catalog mints in five
   * shapes and this reads three of them, so a table on Cloudflare R2 would be accepted into a share,
   * listed, and then fail on every vend — advertising a mode is a promise, and one that cannot be
   * kept is worse than one never made. The remaining shape, an Azure AAD token, cannot be told apart
   * by scheme from the delegation SAS that is read, so a catalog configured to mint those is a vend
   * that still fails; nothing at resolve time distinguishes it.
   */
  private static Set<AccessMode> directoryAccess(UnityCatalogApi.TableInfo info) {
    return VENDABLE_SCHEMES.contains(schemeOf(info.storageLocation()))
        ? Set.of(AccessMode.DIR)
        : Set.of();
  }

  /** The schemes whose minted credential {@link #credentials} knows how to read. */
  private static final Set<String> VENDABLE_SCHEMES =
      Set.of("s3", "s3a", "s3n", "abfs", "abfss", "wasb", "wasbs", "gs");

  private static String schemeOf(String location) {
    if (isBlank(location)) {
      return "";
    }
    int end = location.indexOf(':');
    return end < 0 ? "" : location.substring(0, end).toLowerCase(Locale.ROOT);
  }

  /**
   * @return null for a format this server cannot share, including one it has never heard of, which is
   *     a table to leave alone rather than a reason to fail
   */
  private static TableFormat format(String dataSourceFormat) {
    if (isBlank(dataSourceFormat)) {
      return null;
    }
    return switch (dataSourceFormat.trim().toUpperCase(Locale.ROOT)) {
      case "DELTA" -> TableFormat.DELTA;
      case "PARQUET" -> TableFormat.PARQUET;
      default -> null;
    };
  }

  /** Unity Catalog states partitioning as an index on each column that has one. */
  private static List<String> partitionColumns(UnityCatalogApi.TableInfo info) {
    if (info.columns() == null) {
      return List.of();
    }
    return info.columns().stream()
        .filter(column -> column.partitionIndex() != null && column.partitionIndex() >= 0)
        .sorted(Comparator.comparingInt(UnityCatalogApi.ColumnInfo::partitionIndex))
        .map(UnityCatalogApi.ColumnInfo::name)
        .toList();
  }

  @Override
  public List<StorageCredentials> getStorageCredentials(
      CredentialRequest request, CatalogCaller caller) {
    if (request.assetType() != AssetType.TABLE) {
      throw new UnsupportedAssetTypeException(
          "the "
              + NAME
              + " catalog connector vends credentials for a TABLE, not a "
              + request.assetType());
    }
    if (isBlank(request.storageLocation())) {
      throw new CatalogException(
          "asset '" + request.identifier() + "' has no storage location to scope credentials to");
    }
    if (isBlank(request.catalogAssetId())) {
      throw new CatalogException(
          "Unity Catalog mints credentials for a table id, and none is recorded for '"
              + request.identifier()
              + "'");
    }
    AssetLookup lookup = AssetLookup.of(request.assetType(), request.identifier());
    UnityCatalogApi.TemporaryCredentials minted =
        ask(
            lookup,
            caller,
            () ->
                client.post(
                    "/temporary-table-credentials",
                    new UnityCatalogApi.GenerateTemporaryTableCredential(
                        request.catalogAssetId(), operation(request.operation())),
                    caller,
                    UnityCatalogApi.TemporaryCredentials.class));
    StorageCredentials vended = credentials(request, minted);
    return vended == null ? List.of() : List.of(vended);
  }

  private static String operation(StorageOperation operation) {
    return switch (operation) {
      case READ -> "READ";
    };
  }

  /**
   * One grant, for the one location that was asked about.
   *
   * <p>An expiry is passed on only if Unity Catalog states one. Where it does not, none is invented:
   * a made-up expiry either cuts a working credential short or outlasts a dead one, and the layers
   * above already know what to do with a grant of unstated length.
   *
   * @return null when every credential block is empty and the table is on local storage, which is
   *     Unity Catalog saying nothing is needed rather than that anything failed — its own reader
   *     takes the same answer and opens the file
   */
  private static StorageCredentials credentials(
      CredentialRequest request, UnityCatalogApi.TemporaryCredentials minted) {
    // Zero counts as unstated rather than as 1970: a catalog that serializes an unset long instead
    // of omitting it would otherwise hand the recipient a credential already expired.
    Instant expiration =
        minted.expirationTime() == null || minted.expirationTime() == 0L
            ? null
            : Instant.ofEpochMilli(minted.expirationTime());
    if (minted.awsTempCredentials() != null) {
      UnityCatalogApi.AwsCredentials aws = minted.awsTempCredentials();
      return stated(
          request,
          new StorageCredentials(
              prefix(request, minted),
              CloudProvider.AWS,
              values(
                  StorageCredentialKeys.ACCESS_KEY_ID, aws.accessKeyId(),
                  StorageCredentialKeys.SECRET_ACCESS_KEY, aws.secretAccessKey(),
                  StorageCredentialKeys.SESSION_TOKEN, aws.sessionToken()),
              expiration));
    }
    if (minted.azureUserDelegationSas() != null) {
      return stated(
          request,
          new StorageCredentials(
              prefix(request, minted),
              CloudProvider.AZURE,
              values(StorageCredentialKeys.SAS_TOKEN, minted.azureUserDelegationSas().sasToken()),
              expiration));
    }
    if (minted.gcpOauthToken() != null) {
      return stated(
          request,
          new StorageCredentials(
              prefix(request, minted),
              CloudProvider.GCP,
              values(StorageCredentialKeys.OAUTH_TOKEN, minted.gcpOauthToken().oauthToken()),
              expiration));
    }
    if (StoragePaths.isLocal(request.storageLocation())) {
      return null;
    }
    log.error(
        "The Unity Catalog vended no credentials for '{}' on {}, which is what it answers when it "
            + "holds no storage configuration covering that location",
        request.identifier(),
        request.storageLocation());
    throw new CatalogException("the Unity Catalog vended no credentials for this table");
  }

  /**
   * A credential block the catalog sent but put nothing in. Refused here, so the complaint is about
   * the catalog's answer; left alone it becomes a credential holding no values, and the read gets as
   * far as signing a url before failing about a missing field. Which table, and which shape came back
   * empty, are for the log — a recipient reaches this too.
   */
  private static StorageCredentials stated(
      CredentialRequest request, StorageCredentials credentials) {
    if (credentials.credentials().isEmpty()) {
      log.error(
          "The Unity Catalog minted a {} credential for '{}' with nothing in it",
          credentials.provider(),
          request.identifier());
      throw new CatalogException("the Unity Catalog minted a credential with nothing in it");
    }
    return credentials;
  }

  /**
   * What the grant is scoped to. Unity Catalog names the path it minted for, which can be broader than
   * the table — an external location covering a whole prefix — and the broader answer is the more
   * useful one to report. It is taken only when it actually covers the location asked about, since a
   * normalization as small as a trailing slash would otherwise leave a grant that appears to cover
   * nothing.
   */
  private static String prefix(
      CredentialRequest request, UnityCatalogApi.TemporaryCredentials minted) {
    String url = minted.url();
    return !isBlank(url) && request.storageLocation().startsWith(url)
        ? url
        : request.storageLocation();
  }

  /** Only the values the catalog actually stated, so that a missing one is reported as missing. */
  private static Map<String, String> values(String... keysAndValues) {
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      if (!isBlank(keysAndValues[i + 1])) {
        values.put(keysAndValues[i], keysAndValues[i + 1]);
      }
    }
    return values;
  }

  /**
   * Runs a request and says what its failure means for the asset it was about.
   *
   * <p>Only the connector can: the status alone does not distinguish a table that is not there from a
   * schema that is not, and both a refusal and a rejection need the names of the asset and the caller
   * to read as anything but a number.
   */
  private <T> T ask(AssetLookup lookup, CatalogCaller caller, Supplier<T> request) {
    try {
      return request.get();
    } catch (UnityApiException e) {
      throw switch (e.status()) {
        // Not this server's own credentials being rejected — it has none, and asks as the principal
        // the request is about. So this is that principal's token expired or revoked, which for the
        // asset in hand means exactly what a refusal means: they cannot read it any more. Reported
        // as one, so the object is withdrawn rather than left listed and failing on every read.
        case 401 -> {
          log.warn(
              "The Unity Catalog rejected the credential held for '{}', which needs a new bearer "
                  + "token through the admin API before anything they share can be served",
              caller.name());
          yield new AssetAccessDeniedException(lookup, caller);
        }
        case 403 -> {
          log.debug("Unity Catalog refused {} to '{}'", lookup, caller.name(), e);
          yield new AssetAccessDeniedException(lookup, caller);
        }
        case 404 -> {
          log.debug("Unity Catalog has no {}", lookup, e);
          yield new AssetNotFoundException(lookup);
        }
        default -> e;
      };
    }
  }

  /**
   * The identifier as Unity Catalog names things, which is by however many parts the kind of asset
   * has. A name of the wrong shape is the provider's mistake and is said so plainly, rather than being
   * sent on for the catalog to answer about a name that could not have existed.
   */
  private static String qualifiedName(AssetLookup lookup, int parts, String shape) {
    String identifier = lookup.identifier().trim();
    String[] split = identifier.split("\\.", -1);
    if (split.length != parts || Arrays.stream(split).anyMatch(UnityCatalogConnector::isBlank)) {
      throw new IllegalArgumentException(
          "'"
              + lookup.identifier()
              + "' is not a Unity Catalog "
              + lookup.type().name().toLowerCase(Locale.ROOT)
              + " name; expected "
              + shape);
    }
    return identifier;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
