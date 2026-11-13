package io.opensharing.asset.table.delta;

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.InvalidTableException;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.exceptions.UnsupportedProtocolVersionException;
import io.delta.kernel.exceptions.UnsupportedTableFeatureException;
import io.delta.kernel.internal.TableImpl;
import io.opensharing.asset.table.StorageLocations;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.catalog.TableProperties;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opens a Kernel session against catalog-vended credentials for a Delta table root. */
public class DeltaKernel {

  private static final Logger log = LoggerFactory.getLogger(DeltaKernel.class);
  static final Duration CREDENTIAL_TTL = Duration.ofMinutes(5);
  private static final String CONTACT_PROVIDER = "Please contact your data provider.";
  private static final String SERVER_CANNOT_READ =
      "This server cannot read them. " + CONTACT_PROVIDER;
  private static final int MAX_CAUSE_DEPTH = 10;
  /** A location with a scheme, such as one of the table's files under another spelling. */
  private static final Pattern URI_LIKE =
      Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:/{1,3}[^\\s,;'\")\\]]*[^\\s,;'\")\\]:.]");
  /** What a stripped table path is replaced with in a message the recipient gets. */
  private static final String PATH_PLACEHOLDER = "the table";

  private final CatalogConnector catalog;

  public DeltaKernel(CatalogConnector catalog) {
    this.catalog = catalog;
  }

  /**
   * The table's latest version, or with {@code startingTimestamp} the earliest version committed
   * at or after it. Reads the table as {@code auth}.
   */
  public long getVersion(ResolvedAsset table, Instant startingTimestamp, AuthContext auth) {
    try (Session session = open(table, auth)) {
      return versionAtOrAfter((TableImpl) session.table(), session.engine(), startingTimestamp);
    } catch (KernelException | KernelEngineException failed) {
      throw classifyRead(failed, table);
    }
  }

  /**
   * The table's latest version, or the earliest committed at or after {@code startingTimestamp}.
   * A timestamp the log cannot answer is Kernel's report.
   */
  static long versionAtOrAfter(TableImpl table, Engine engine, Instant startingTimestamp) {
    if (startingTimestamp == null) {
      return table.getLatestSnapshot(engine).getVersion();
    }
    try {
      return table.getVersionAtOrAfterTimestamp(engine, startingTimestamp.toEpochMilli());
    } catch (IllegalArgumentException failed) {
      throw classify(failed, table.getPath(engine), null);
    }
  }

  /**
   * Classifies a Kernel failure anywhere in reading {@code table}, of which the catalog's storage
   * location is all the path known.
   */
  static RuntimeException classifyRead(RuntimeException failed, ResolvedAsset table) {
    return classify(
        failed, HadoopStorageConfiguration.kernelPath(table.location().storageLocation()), null);
  }

  /**
   * The recipient's answer to a failure reading the table, classified once. A log or file that is
   * missing or cannot be accessed, or a feature this server cannot read, is the shared source
   * table's own state, which its data provider controls. A log whose content Kernel rejects is
   * the table's state too, and Kernel says what is wrong. Any other Kernel report, such as a
   * version or timestamp the table does not have, is the request's mistake. A failure of the
   * storage layer itself is not the recipient's to hear about, and stays a server error. Kernel's
   * messages name the table's storage path, which stays in the log.
   *
   * @param version the table version the failed read was for, if the request named one
   */
  static RuntimeException classify(RuntimeException failed, String tablePath, Long version) {
    if (failed instanceof TableNotFoundException || storageUnavailable(failed)) {
      log.warn("Delta table at '{}' is unavailable: {}", tablePath, failed.toString());
      return SourceTableException.unreadable(failed.toString());
    }
    if (failed instanceof KernelEngineException) {
      // The storage layer failed for a reason of its own, which is a server error.
      log.error("Delta table at '{}' failed in the storage layer", tablePath, failed);
      return failed;
    }
    if (failed instanceof UnsupportedTableFeatureException unsupported) {
      log.warn("Delta table at '{}': {}", tablePath, unsupported.getMessage());
      return ApiException.unsupportedFeatures(
          unsupported.getUnsupportedFeatures().stream()
              .map(feature -> feature.toLowerCase(Locale.ROOT))
              .toList(),
          version,
          SERVER_CANNOT_READ);
    }
    if (failed instanceof UnsupportedProtocolVersionException unsupported) {
      log.warn("Delta table at '{}': {}", tablePath, unsupported.getMessage());
      return ApiException.unsupportedFeatures(
          List.of("reader version " + unsupported.getMinReaderVersion()),
          version,
          SERVER_CANNOT_READ);
    }
    if (failed instanceof InvalidTableException) {
      return kernelError(failed, tablePath, " " + CONTACT_PROVIDER);
    }
    return kernelError(failed, tablePath, "");
  }

  /**
   * Whether the table's files could not be found or read: Kernel reports that only as the storage
   * layer's own failure, which may be the filesystem library's class of the same name.
   */
  private static boolean storageUnavailable(Throwable failed) {
    Throwable cause = failed;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof FileNotFoundException
          || cause instanceof NoSuchFileException
          || cause.getClass().getSimpleName().equals("AccessDeniedException")) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  /**
   * Kernel's report, in Kernel's own words and without the table's storage path, which stays in the
   * log: every spelling of the path, and then any other location with a scheme, is removed from
   * what the recipient gets. {@code suffix} is appended to the message.
   */
  static ApiException kernelError(RuntimeException failed, String tablePath, String suffix) {
    String message =
        failed.getMessage() == null ? "Kernel rejected the request." : failed.getMessage();
    log.info("Kernel rejected a request for '{}': {}", tablePath, message);
    for (String form : pathForms(tablePath)) {
      message = message.replaceAll(Pattern.quote(form) + "(/(?=[:\\s]|$))?", PATH_PLACEHOLDER);
    }
    message = URI_LIKE.matcher(message).replaceAll(PATH_PLACEHOLDER);
    String lead = PATH_PLACEHOLDER + ": ";
    if (message.startsWith(lead)) {
      message = message.substring(lead.length());
    }
    return ApiException.badRequest(message + suffix);
  }

  /**
   * The spellings of a table path a Kernel message can carry: as given, a local path with and
   * without its {@code file:} scheme, which Kernel normalizes to one slash, and with spaces escaped
   * or unescaped, since a path and its URI form differ in all of those. Longer spellings come
   * first, so replacing one never leaves a stub of another behind.
   */
  private static List<String> pathForms(String tablePath) {
    Set<String> paths = new HashSet<>(List.of(tablePath));
    if (tablePath.startsWith("file:")) {
      String local = tablePath.replaceFirst("^file:/*", "/");
      paths.addAll(List.of(local, "file:" + local, "file://" + local));
    }
    Set<String> forms = new HashSet<>();
    for (String path : paths) {
      String base = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      forms.addAll(List.of(base, base.replace(" ", "%20"), base.replace("%20", " ")));
    }
    forms.remove("");
    return forms.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
  }

  /** Opens the table's root with read credentials the catalog vends to {@code auth}. */
  public Session open(ResolvedAsset table, AuthContext auth) {
    return open(table, auth, CREDENTIAL_TTL);
  }

  /** Like {@link #open(ResolvedAsset, AuthContext)}, with credentials that last {@code ttl}. */
  public Session open(ResolvedAsset table, AuthContext auth, Duration ttl) {
    if (!(table.additionalProperties() instanceof TableProperties properties)
        || properties.dataSourceFormat() != DataSourceFormat.DELTA) {
      throw ApiException.invalidParameter("the table is not a Delta table");
    }
    String location = table.location() == null ? null : table.location().storageLocation();
    if (location == null || location.isBlank()) {
      log.error("Catalog table '{}' has no storage location", table.fullName());
      throw new CatalogException("the catalog reported no storage location for the table");
    }
    HadoopStorageConfiguration.requireSupported(location);
    List<StorageCredentials> credentials =
        catalog.getStorageCredentials(
            new CredentialRequest(
                AssetType.TABLE,
                table.fullName(),
                table.catalogAssetId(),
                location,
                StorageOperation.READ,
                ttl),
            auth);
    StorageCredentials rootCredentials =
        credentials.stream()
            .filter(candidate -> StorageLocations.covers(candidate.prefix(), location))
            .findFirst()
            .orElseThrow(
                () -> {
                  log.error("Catalog returned no credentials for table root '{}'", location);
                  return new CatalogException("the catalog returned no credentials for the table");
                });
    String path = HadoopStorageConfiguration.kernelPath(location);
    SessionFileIO files =
        new SessionFileIO(HadoopStorageConfiguration.from(rootCredentials, location), path);
    Engine engine = DefaultEngine.create(files);
    return new Session(engine, Table.forPath(engine, path), location, rootCredentials, files);
  }

  /** A Kernel engine and table reading through files the session owns until it is closed. */
  public record Session(
      Engine engine,
      Table table,
      String location,
      StorageCredentials credentials,
      SessionFileIO files)
      implements AutoCloseable {

    @Override
    public void close() {
      files.close();
    }
  }
}
