package io.opensharing.asset.iceberg;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import io.opensharing.serving.ActionStream;
import io.opensharing.serving.TableOperations;
import io.opensharing.serving.TableRequests;
import org.springframework.stereotype.Component;

/**
 * An Iceberg table's read operations, which are not served here and are not meant to be.
 *
 * <p>These four are the Delta Sharing read operations: a version, a metadata line, a list of signed
 * files, a change feed. An Iceberg table answers the same questions through its own catalog API
 * instead — {@code loadTable} hands over the table's metadata document and credentials for its
 * files, and an engine that reads Iceberg does the rest. So this refuses and says where to go, rather
 * than inventing a translation between two protocols that both already work.
 *
 * <p>It is registered rather than absent so the refusal can be specific: the endpoint exists for
 * this table and the recipient asked correctly, but asked in the wrong protocol.
 */
@Component
public class IcebergTableOperations implements TableOperations {

  @Override
  public TableFormat format() {
    return TableFormat.ICEBERG;
  }

  @Override
  public long version(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Version request) {
    throw unserved(table, "a version");
  }

  @Override
  public ActionStream metadata(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Metadata request) {
    throw unserved(table, "metadata");
  }

  @Override
  public ActionStream query(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Query request) {
    throw unserved(table, "files");
  }

  @Override
  public ActionStream changes(
      SharedDataObjectEntity table, ResolvedAsset resolved, TableRequests.Changes request) {
    throw unserved(table, "a change feed");
  }

  private static ApiException unserved(SharedDataObjectEntity table, String what) {
    return ApiException.notImplemented(
        "serving "
            + what
            + " for the Iceberg table '"
            + table.getSharedAsName()
            + "' is not how an Iceberg table is read; load it from the Iceberg REST catalog at the "
            + "profile file's icebergEndpoint, or call temporary-table-credentials and read its "
            + "storage location directly");
  }
}
