package io.opensharing.asset.delta;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import io.opensharing.protocol.QueryTableRequest;
import io.opensharing.protocol.TableAction;
import io.opensharing.serving.ActionStream;
import io.opensharing.serving.ProtocolHeaders;
import io.opensharing.serving.TableOperations;
import io.opensharing.serving.TableRequests;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The table read operations for a Delta table, served in url access mode.
 *
 * <p>The server reads the table's log with credentials the catalog minted for it, and hands back
 * per-file urls signed from those same credentials, so a recipient reads the bytes directly without
 * ever holding a credential. A recipient whose engine can replay a Delta log itself is better served by
 * dir access mode, which skips all of this — and is all that is left when {@code
 * opensharing.delta.url-access-enabled} is off.
 *
 * <p>This is where the protocol's Delta specifics live: what a version selector means, which
 * response format a request settles on, and which file id schemes exist.
 */
@Component
public class DeltaTableOperations implements TableOperations {

  private static final Set<String> FILE_ID_SCHEMES = Set.of("parquet", "delta");

  private final DeltaTableService tables;
  private final DeltaResponses responses;
  private final boolean urlAccessEnabled;

  public DeltaTableOperations(
      DeltaTableService tables, DeltaResponses responses, OpenSharingProperties properties) {
    this.tables = tables;
    this.responses = responses;
    this.urlAccessEnabled = properties.getDelta().isUrlAccessEnabled();
  }

  @Override
  public TableFormat format() {
    return TableFormat.DELTA;
  }

  @Override
  public long version(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Version request) {
    requireUrlAccess();
    return tables.version(table, resolved, DeltaVersion.parse(request.startingTimestamp()));
  }

  @Override
  public ActionStream metadata(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Metadata request) {
    requireUrlAccess();
    DeltaSharingCapabilities capabilities = DeltaSharingCapabilities.parse(request.capabilities());
    DeltaVersion at = DeltaVersion.from(request.version(), request.timestamp());
    DeltaTable delta = tables.read(table, resolved, at, false);
    DeltaResponseFormat format = capabilities.chooseFormat(delta.snapshot());
    return stream(
        responses.in(format).metadata(table, delta, !at.isLatest(), capabilities),
        delta.snapshot().version(),
        capabilities,
        format,
        null);
  }

  /**
   * Either a snapshot of the table or the changes since a version, which are different questions
   * behind one endpoint: {@code startingVersion} asks what has happened since, as a stream following
   * the table does, while everything else asks what the table holds.
   */
  @Override
  public ActionStream query(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Query request) {
    requireUrlAccess();
    QueryTableRequest query = request.body();
    DeltaSharingCapabilities capabilities = DeltaSharingCapabilities.parse(request.capabilities());
    String fileIdScheme = fileIdScheme(request.fileIdHash());
    if (query.startingVersion() != null) {
      return changesFrom(table, resolved, query, capabilities, fileIdScheme);
    }

    DeltaVersion at = DeltaVersion.from(query.version(), query.timestamp());
    DeltaTable delta = tables.read(table, resolved, at, true);
    DeltaResponseFormat format = capabilities.chooseFormat(delta.snapshot());
    return stream(
        responses.in(format).query(table, delta, !at.isLatest(), capabilities),
        delta.snapshot().version(),
        capabilities,
        format,
        fileIdScheme);
  }

  /**
   * The data change files from a version onwards. A schema change inside the range is always
   * reported, since a stream that is told nothing would keep reading files under a schema the table
   * has left behind.
   */
  private ActionStream changesFrom(
      SharedDataObjectEntity table,
      ResolvedAsset resolved,
      QueryTableRequest query,
      DeltaSharingCapabilities capabilities,
      String fileIdScheme) {
    if (query.version() != null || query.timestamp() != null) {
      throw ApiException.invalidParameter(
          "startingVersion asks what has changed since a version, while version and timestamp ask "
              + "what the table held at one, so they cannot be combined");
    }
    DeltaTableService.ChangeFeed feed =
        tables.changesFrom(table, resolved, query.startingVersion(), query.endingVersion(), true);
    DeltaResponseFormat format = capabilities.chooseFormat(feed.table().snapshot());
    DeltaLines.History history =
        new DeltaLines.History(true, historicalProtocol(query.includeHistoricalProtocol(), format));
    return stream(
        responses.in(format).changes(table, feed, history, capabilities),
        feed.startVersion(),
        capabilities,
        format,
        fileIdScheme);
  }

  /**
   * Only a table with the change data feed turned on records enough to answer fully, which is the
   * log's own business — an untracked window simply yields the added and removed files it can see.
   */
  @Override
  public ActionStream changes(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Changes request) {
    requireUrlAccess();
    DeltaSharingCapabilities capabilities = DeltaSharingCapabilities.parse(request.capabilities());
    String fileIdScheme = fileIdScheme(request.fileIdHash());

    DeltaTableService.ChangeFeed feed =
        tables.changes(
            table,
            resolved,
            request.startingVersion(),
            DeltaVersion.parse(request.startingTimestamp()),
            request.endingVersion(),
            DeltaVersion.parse(request.endingTimestamp()),
            request.includeHistoricalMetadata() || request.includeHistoricalProtocol());
    DeltaResponseFormat format = capabilities.chooseFormat(feed.table().snapshot());
    DeltaLines.History history =
        new DeltaLines.History(
            request.includeHistoricalMetadata(),
            historicalProtocol(request.includeHistoricalProtocol(), format));
    return stream(
        responses.in(format).changes(table, feed, history, capabilities),
        feed.startVersion(),
        capabilities,
        format,
        fileIdScheme);
  }

  /** The parquet format has no line for a protocol, so asking for one there is quietly nothing. */
  private static boolean historicalProtocol(Boolean requested, DeltaResponseFormat format) {
    return Boolean.TRUE.equals(requested) && format == DeltaResponseFormat.DELTA;
  }

  private ActionStream stream(
      List<TableAction> actions,
      long version,
      DeltaSharingCapabilities capabilities,
      DeltaResponseFormat format,
      String fileIdScheme) {
    return ActionStream.of(actions)
        .header(ProtocolHeaders.TABLE_VERSION, Long.toString(version))
        .header(ProtocolHeaders.CAPABILITIES, capabilities.responseHeaderValue(format))
        .header(ProtocolHeaders.FILE_ID_HASH, fileIdScheme)
        .build();
  }

  /**
   * The scheme a client wants file ids derived by. Both the protocol's schemes are accepted, and this
   * server derives an id the same way for either — from the file's path within the table, which is
   * stable whichever format the response takes — since the protocol leaves the encoding to the
   * server and only asks that it stay the same. Anything else is a 400, and what was accepted is
   * echoed back.
   */
  private static String fileIdScheme(String requested) {
    if (requested == null || requested.isBlank()) {
      return null;
    }
    String scheme = requested.trim().toLowerCase(Locale.ROOT);
    if (!FILE_ID_SCHEMES.contains(scheme)) {
      throw ApiException.invalidParameter(
          "unsupported fileidhash '" + requested + "'; this server derives ids the parquet or the "
              + "delta way, which for it are the same way");
    }
    return scheme;
  }

  private void requireUrlAccess() {
    if (!urlAccessEnabled) {
      throw ApiException.notImplemented(
          "url access mode is turned off on this server; call temporary-table-credentials and read "
              + "the table's storage location directly");
    }
  }
}
