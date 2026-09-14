package io.opensharing.asset.delta;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.protocol.DeltaFileAction;
import io.opensharing.protocol.DeltaMetadataAction;
import io.opensharing.protocol.DeltaProtocolAction;
import io.opensharing.protocol.MetadataAction;
import io.opensharing.protocol.TableAction;
import java.util.ArrayList;
import java.util.List;

/**
 * The delta response format: each line wraps the log's own action, with the path replaced by a
 * signed url.
 *
 * <p>The point of answering this way is that the recipient can write the lines into a local Delta
 * log and read the table with a Delta library, so features this server has no opinion about —
 * deletion vectors, column mapping — arrive intact instead of being flattened into a shape that
 * cannot hold them. What the server still decides is what a recipient may see: the log's own name
 * and description for the table stay behind, as they do in parquet format, because a recipient knows
 * the table by the alias it was shared as.
 */
final class DeltaFormatLines implements DeltaLines {

  /** How the Delta spec marks a vector stored at a path of its own, which a signed url is. */
  private static final String PATH_VECTOR = "p";

  private final SignedFiles files;

  DeltaFormatLines(SignedFiles files) {
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
        TableAction.of(protocolAction(capabilities.reportedProtocol(snapshot.protocol()), null)),
        TableAction.of(
            metadataAction(
                object, snapshot.metadata(), statedVersion ? snapshot.version() : null, null, null)));
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
      fileLines.add(
          fileLine(
              table,
              file,
              statedVersion ? snapshot.version() : null,
              statedVersion ? snapshot.timestamp() : null,
              expiries));
      totalSize += file.size();
    }

    actions.add(
        TableAction.of(protocolAction(capabilities.reportedProtocol(snapshot.protocol()), null)));
    actions.add(
        TableAction.of(
            metadataAction(
                object,
                snapshot.metadata(),
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
    actions.add(
        TableAction.of(protocolAction(capabilities.reportedProtocol(head.protocol()), null)));
    actions.add(
        TableAction.of(metadataAction(object, head.metadata(), head.version(), null, null)));

    Expiries expiries = new Expiries();
    for (DeltaChanges.Entry entry : feed.changes()) {
      switch (entry) {
        case DeltaChanges.FileChange file -> actions.add(changeLine(table, file, expiries));
        case DeltaChanges.MetadataChange changed -> {
          if (history.metadata()) {
            actions.add(
                TableAction.of(
                    metadataAction(object, changed.metadata(), changed.version(), null, null)));
          }
        }
        case DeltaChanges.ProtocolChange changed -> {
          // A protocol inside the window is history, so it is reported as the log wrote it rather
          // than as the head is described to this client.
          if (history.protocol()) {
            actions.add(TableAction.of(protocolAction(changed.protocol(), changed.version())));
          }
        }
      }
    }
    expiries.close(actions, capabilities);
    return actions;
  }

  private TableAction fileLine(
      DeltaTable table, DeltaSnapshot.File file, Long version, Long timestamp, Expiries expiries) {
    SignedFiles.Signed signed = files.sign(table, file.path());
    SignedFiles.Signed vector = files.signVector(table, file.deletionVector());
    expiries.saw(signed.expiration());
    DeltaFileAction.Add add =
        new DeltaFileAction.Add(
            signed.url(),
            file.partitionValues(),
            file.size(),
            file.modificationTime(),
            file.dataChange(),
            file.stats(),
            deletionVector(file.deletionVector(), vector, expiries),
            file.baseRowId(),
            file.defaultRowCommitVersion());
    return TableAction.of(
        new DeltaFileAction(
            signed.id(),
            vector == null ? null : vector.id(),
            version,
            timestamp,
            signed.expiration(),
            DeltaFileAction.SingleAction.of(add)));
  }

  private TableAction changeLine(
      DeltaTable table, DeltaChanges.FileChange change, Expiries expiries) {
    SignedFiles.Signed signed = files.sign(table, change.path());
    SignedFiles.Signed vector = files.signVector(table, change.deletionVector());
    expiries.saw(signed.expiration());
    DeltaFileAction.DeletionVector deletionVector =
        deletionVector(change.deletionVector(), vector, expiries);
    DeltaFileAction.SingleAction action =
        switch (change.kind()) {
          case ADD ->
              DeltaFileAction.SingleAction.of(
                  new DeltaFileAction.Add(
                      signed.url(),
                      change.partitionValues(),
                      change.size(),
                      change.modificationTime(),
                      change.dataChange(),
                      change.stats(),
                      deletionVector,
                      null,
                      null));
          case REMOVE ->
              DeltaFileAction.SingleAction.of(
                  new DeltaFileAction.Remove(
                      signed.url(),
                      change.partitionValues(),
                      change.size(),
                      change.deletionTimestamp(),
                      change.dataChange(),
                      deletionVector));
          case CDF ->
              DeltaFileAction.SingleAction.of(
                  new DeltaFileAction.Cdc(
                      signed.url(),
                      change.partitionValues(),
                      change.size(),
                      change.dataChange()));
        };
    return TableAction.of(
        new DeltaFileAction(
            signed.id(),
            vector == null ? null : vector.id(),
            change.version(),
            change.timestamp(),
            signed.expiration(),
            action));
  }

  /**
   * A vector kept in its own file is handed over the way its data file is, as a signed url, so it is
   * marked as living at a path rather than under the name the log gave it. One inlined in the action
   * needs nothing: it travels as the log wrote it.
   */
  private static DeltaFileAction.DeletionVector deletionVector(
      DeltaSnapshot.DeletionVector vector, SignedFiles.Signed signed, Expiries expiries) {
    if (vector == null) {
      return null;
    }
    if (signed == null) {
      return new DeltaFileAction.DeletionVector(
          vector.storageType(),
          vector.pathOrInlineDv(),
          vector.offset(),
          vector.sizeInBytes(),
          vector.cardinality());
    }
    expiries.saw(signed.expiration());
    return new DeltaFileAction.DeletionVector(
        PATH_VECTOR, signed.url(), vector.offset(), vector.sizeInBytes(), vector.cardinality());
  }

  private static DeltaProtocolAction protocolAction(DeltaSnapshot.Protocol protocol, Long version) {
    return new DeltaProtocolAction(
        new DeltaProtocolAction.Protocol(
            protocol.minReaderVersion(),
            protocol.minWriterVersion(),
            protocol.readerFeatures().isEmpty() ? null : protocol.readerFeatures(),
            protocol.writerFeatures().isEmpty() ? null : protocol.writerFeatures()),
        version);
  }

  private static DeltaMetadataAction metadataAction(
      SharedDataObjectEntity object,
      DeltaSnapshot.Metadata metadata,
      Long version,
      Long size,
      Long numFiles) {
    return new DeltaMetadataAction(
        new DeltaMetadataAction.Metadata(
            metadata.id(),
            null,
            null,
            new MetadataAction.Format(metadata.formatProvider(), metadata.formatOptions()),
            metadata.schemaString(),
            metadata.partitionColumns(),
            metadata.configuration(),
            metadata.createdTime()),
        version,
        size,
        numFiles,
        DeltaLines.location(object),
        DeltaLines.auxiliaryLocations(object),
        DeltaLines.accessModes(object));
  }
}
