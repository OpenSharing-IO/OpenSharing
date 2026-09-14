package io.opensharing.asset.delta;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.http.ApiException;
import io.opensharing.protocol.ChangeFileAction;
import io.opensharing.protocol.FileAction;
import io.opensharing.protocol.MetadataAction;
import io.opensharing.protocol.ProtocolAction;
import io.opensharing.protocol.TableAction;
import java.util.ArrayList;
import java.util.List;

/**
 * The parquet response format: each line says what a recipient needs in order to read one file,
 * without it having to understand a Delta log at all.
 *
 * <p>Two things from the log are deliberately not passed through. The table's own name and
 * description stay behind, because a recipient sees the alias the object was shared as and the log's
 * name would leak the provider's naming. And a file with a deletion vector is refused rather than
 * handed over as if all of its rows still counted — the choice of format was made from what the
 * table's properties say it uses, and a vector left behind by a property since switched off is the
 * one way a file can still arrive here needing more than this format can say.
 */
final class ParquetLines implements DeltaLines {

  private final SignedFiles files;

  ParquetLines(SignedFiles files) {
    this.files = files;
  }

  @Override
  public List<TableAction> metadata(
      SharedDataObjectEntity object,
      DeltaTable table,
      boolean statedVersion,
      DeltaSharingCapabilities capabilities) {
    DeltaSnapshot snapshot = table.snapshot();
    return List.of(
        TableAction.of(protocolAction(snapshot, capabilities)),
        TableAction.of(
            metadataAction(object, snapshot, statedVersion ? snapshot.version() : null, null, null)));
  }

  @Override
  public List<TableAction> query(
      SharedDataObjectEntity object,
      DeltaTable table,
      boolean statedVersion,
      DeltaSharingCapabilities capabilities) {
    DeltaSnapshot snapshot = table.snapshot();
    List<TableAction> actions = new ArrayList<>(snapshot.files().size() + 3);
    Expiries expiries = new Expiries();
    List<TableAction> fileLines = new ArrayList<>(snapshot.files().size());
    long totalSize = 0;
    for (DeltaSnapshot.File file : snapshot.files()) {
      requireThisFormatCanCarry(file.deletionVector());
      SignedFiles.Signed signed = files.sign(table, file.path());
      expiries.saw(signed.expiration());
      fileLines.add(
          TableAction.of(
              new FileAction(
                  signed.url(),
                  signed.id(),
                  file.partitionValues(),
                  file.size(),
                  file.stats(),
                  statedVersion ? snapshot.version() : null,
                  statedVersion ? snapshot.timestamp() : null,
                  signed.expiration())));
      totalSize += file.size();
    }

    actions.add(TableAction.of(protocolAction(snapshot, capabilities)));
    actions.add(
        TableAction.of(
            metadataAction(
                object,
                snapshot,
                statedVersion ? snapshot.version() : null,
                totalSize,
                (long) snapshot.files().size())));
    actions.addAll(fileLines);
    expiries.close(actions, capabilities);
    return actions;
  }

  @Override
  public List<TableAction> changes(
      SharedDataObjectEntity object,
      DeltaTableService.ChangeFeed feed,
      History history,
      DeltaSharingCapabilities capabilities) {
    DeltaTable table = feed.table();
    DeltaSnapshot head = table.snapshot();
    List<TableAction> actions = new ArrayList<>(feed.changes().size() + 3);
    actions.add(TableAction.of(protocolAction(head, capabilities)));
    actions.add(TableAction.of(metadataAction(object, head, head.version(), null, null)));

    Expiries expiries = new Expiries();
    for (DeltaChanges.Entry entry : feed.changes()) {
      switch (entry) {
        case DeltaChanges.FileChange file -> actions.add(fileLine(table, file, expiries));
        case DeltaChanges.MetadataChange changed -> {
          if (history.metadata()) {
            actions.add(
                TableAction.of(
                    metadataAction(object, changed.metadata(), changed.version(), null, null)));
          }
        }
        case DeltaChanges.ProtocolChange ignored -> {
          // This format has no line for a protocol, and a version raised mid-window says nothing a
          // reader of these lines can act on: what matters is whether the files stay describable.
        }
      }
    }
    expiries.close(actions, capabilities);
    return actions;
  }

  private TableAction fileLine(
      DeltaTable table, DeltaChanges.FileChange change, Expiries expiries) {
    requireThisFormatCanCarry(change.deletionVector());
    SignedFiles.Signed signed = files.sign(table, change.path());
    expiries.saw(signed.expiration());
    ChangeFileAction action =
        new ChangeFileAction(
            signed.url(),
            signed.id(),
            change.partitionValues(),
            change.size(),
            change.timestamp(),
            change.version(),
            change.stats(),
            signed.expiration());
    return switch (change.kind()) {
      case ADD -> TableAction.added(action);
      case CDF -> TableAction.changed(action);
      case REMOVE -> TableAction.removed(action);
    };
  }

  private static MetadataAction metadataAction(
      SharedDataObjectEntity object,
      DeltaSnapshot snapshot,
      Long version,
      Long size,
      Long numFiles) {
    return metadataAction(object, snapshot.metadata(), version, size, numFiles);
  }

  private static MetadataAction metadataAction(
      SharedDataObjectEntity object,
      DeltaSnapshot.Metadata metadata,
      Long version,
      Long size,
      Long numFiles) {
    return new MetadataAction(
        metadata.id(),
        null,
        null,
        DeltaLines.location(object),
        DeltaLines.auxiliaryLocations(object),
        DeltaLines.accessModes(object),
        new MetadataAction.Format(metadata.formatProvider(), metadata.formatOptions()),
        metadata.schemaString(),
        metadata.partitionColumns(),
        metadata.configuration(),
        version,
        size,
        numFiles);
  }

  private static ProtocolAction protocolAction(
      DeltaSnapshot snapshot, DeltaSharingCapabilities capabilities) {
    return new ProtocolAction(capabilities.reportedProtocol(snapshot.protocol()).minReaderVersion());
  }

  /**
   * A file whose rows are not all still there. The table said it had no deletion vectors, or its
   * property has since been switched off, but this file kept one, and a line that cannot mention it
   * would have the recipient count deleted rows as live ones. So the read is refused instead.
   */
  private static void requireThisFormatCanCarry(DeltaSnapshot.DeletionVector vector) {
    if (vector != null) {
      throw ApiException.notImplemented(
          "a file of this table carries a deletion vector, which the parquet response format cannot "
              + "express even though the table does not declare the feature; ask for "
              + "responseformat=delta in the delta-sharing-capabilities header with "
              + "readerfeatures=deletionVectors, or read the table through dir access mode");
    }
  }
}
