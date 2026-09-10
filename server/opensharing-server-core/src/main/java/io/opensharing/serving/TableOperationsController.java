package io.opensharing.serving;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opensharing.asset.AssetResolutionService;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedTableService;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ProtocolMediaType;
import io.opensharing.protocol.QueryTableRequest;
import io.opensharing.protocol.TableAction;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareAccessService;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read operations the protocol defines for a table: what version it is at, what shape it has, what
 * files make it up, and what changed over a window.
 *
 * <p>Nothing here knows a table format. The endpoint authorizes the share, resolves the table —
 * whether shared in its own right or through the schema holding it — and hands the request to whichever
 * {@link TableOperations} serves that format, then writes out the actions and headers it gets back.
 * Which version a timestamp names, what a capabilities header may ask for, and how files are signed are
 * all decided on the other side of that seam.
 *
 * <p>Responses are newline-delimited JSON, one action per line.
 */
@RestController
@RequestMapping(RecipientApi.TABLE)
public class TableOperationsController {

  private final ShareAccessService access;
  private final SharedTableService tables;
  private final AssetResolutionService resolution;
  private final TableFormatRegistry formats;
  private final ObjectMapper json;

  public TableOperationsController(
      ShareAccessService access,
      SharedTableService tables,
      AssetResolutionService resolution,
      TableFormatRegistry formats,
      ObjectMapper json) {
    this.access = access;
    this.tables = tables;
    this.resolution = resolution;
    this.formats = formats;
    this.json = json;
  }

  /**
   * The cheap question: what version is this table at? Answered in a header with no body, which is
   * what lets a client check a cache without paying for metadata.
   */
  @GetMapping("/version")
  public ResponseEntity<Void> version(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestParam(required = false) String startingTimestamp) {
    Served served = serve(principal, share, schema, table);
    long version =
        served.operations().version(
            served.table(), served.resolved(), new TableRequests.Version(startingTimestamp));
    return ResponseEntity.ok()
        .header(ProtocolHeaders.TABLE_VERSION, Long.toString(version))
        .build();
  }

  @GetMapping(value = "/metadata", produces = ProtocolMediaType.NDJSON_UTF8)
  public ResponseEntity<String> metadata(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestParam(required = false) Long version,
      @RequestParam(required = false) String timestamp,
      @RequestHeader(name = ProtocolHeaders.CAPABILITIES, required = false) String capabilities) {
    Served served = serve(principal, share, schema, table);
    return ndjson(
        served.operations().metadata(
            served.table(),
            served.resolved(),
            new TableRequests.Metadata(version, timestamp, capabilities)));
  }

  @PostMapping(value = "/query", produces = ProtocolMediaType.NDJSON_UTF8)
  public ResponseEntity<String> query(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestBody(required = false) QueryTableRequest request,
      @RequestHeader(name = ProtocolHeaders.CAPABILITIES, required = false) String capabilities,
      @RequestHeader(name = ProtocolHeaders.FILE_ID_HASH, required = false) String fileIdHash) {
    Served served = serve(principal, share, schema, table);
    QueryTableRequest body = request == null ? QueryTableRequest.snapshot() : request;
    return ndjson(
        served.operations().query(
            served.table(),
            served.resolved(),
            new TableRequests.Query(body, capabilities, fileIdHash)));
  }

  /**
   * The change data feed: what happened to the table over a window of versions, rather than what it
   * holds now.
   */
  @GetMapping(value = "/changes", produces = ProtocolMediaType.NDJSON_UTF8)
  public ResponseEntity<String> changes(
      RecipientPrincipal principal,
      @PathVariable String share,
      @PathVariable String schema,
      @PathVariable String table,
      @RequestParam(required = false) Long startingVersion,
      @RequestParam(required = false) String startingTimestamp,
      @RequestParam(required = false) Long endingVersion,
      @RequestParam(required = false) String endingTimestamp,
      @RequestParam(required = false, defaultValue = "false") boolean includeHistoricalMetadata,
      @RequestParam(required = false, defaultValue = "false") boolean includeHistoricalProtocol,
      @RequestHeader(name = ProtocolHeaders.CAPABILITIES, required = false) String capabilities,
      @RequestHeader(name = ProtocolHeaders.FILE_ID_HASH, required = false) String fileIdHash) {
    Served served = serve(principal, share, schema, table);
    return ndjson(
        served.operations().changes(
                served.table(),
                served.resolved(),
                new TableRequests.Changes(
                    startingVersion,
                    startingTimestamp,
                    endingVersion,
                    endingTimestamp,
                    includeHistoricalMetadata,
                    includeHistoricalProtocol,
                    capabilities,
                    fileIdHash)));
  }

  private ResponseEntity<String> ndjson(ActionStream stream) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    stream.headers().forEach(response::header);
    return response.body(
        stream.actions().stream().map(this::line).collect(Collectors.joining("\n", "", "\n")));
  }

  private String line(TableAction action) {
    try {
      return json.writeValueAsString(action);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to serialize a table action", e);
    }
  }

  /**
   * Everything the four endpoints do before they differ: authorize the share, find the table in it,
   * ask the catalog about it, and pick the implementation for the format it answered with.
   *
   * <p>The catalog is asked here, once, rather than by whichever implementation is chosen. That is
   * what lets the choice be made on the table as it is now — a table converted to another format goes
   * to the implementation for the new one, and one converted into a format this server serves starts
   * being served instead of staying refused by a record that has fallen behind — and it saves the
   * implementation asking the same question again to check the answer it was dispatched on.
   */
  private Served serve(RecipientPrincipal principal, String share, String schema, String table) {
    SharedDataObjectEntity object =
        tables.require(access.requireShare(principal, share), schema, table);
    ResolvedAsset resolved = resolution.resolveForServing(object);
    return new Served(object, resolved, formats.forTable(object, resolved));
  }

  private record Served(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableOperations operations) {}
}
