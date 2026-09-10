package io.opensharing.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One line of a Delta read response. The protocol sends newline-delimited JSON where each line is
 * this wrapper carrying exactly one action, so the factories are the only way to build one.
 *
 * <p>Three of the slots hold either of two shapes, because the same line means the same thing in
 * both response formats but says it differently: parquet format states what a recipient needs to
 * know about a file, while delta format wraps the log's own action so a recipient can rebuild a
 * local Delta log from the response. The sealed interfaces are what keeps a slot to those two.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableAction(
    Protocol protocol,
    Metadata metaData,
    File file,
    ChangeFileAction add,
    ChangeFileAction cdf,
    ChangeFileAction remove,
    EndStreamAction endStreamAction) {

  /** What a reader must support: {@link ProtocolAction} or {@link DeltaProtocolAction}. */
  public sealed interface Protocol permits ProtocolAction, DeltaProtocolAction {}

  /** What the table is: {@link MetadataAction} or {@link DeltaMetadataAction}. */
  public sealed interface Metadata permits MetadataAction, DeltaMetadataAction {}

  /** One file a recipient may read: {@link FileAction} or {@link DeltaFileAction}. */
  public sealed interface File permits FileAction, DeltaFileAction {}

  public static TableAction of(Protocol protocol) {
    return new TableAction(protocol, null, null, null, null, null, null);
  }

  public static TableAction of(Metadata metadata) {
    return new TableAction(null, metadata, null, null, null, null, null);
  }

  public static TableAction of(File file) {
    return new TableAction(null, null, file, null, null, null, null);
  }

  public static TableAction added(ChangeFileAction change) {
    return new TableAction(null, null, null, change, null, null, null);
  }

  public static TableAction changed(ChangeFileAction change) {
    return new TableAction(null, null, null, null, change, null, null);
  }

  public static TableAction removed(ChangeFileAction change) {
    return new TableAction(null, null, null, null, null, change, null);
  }

  public static TableAction of(EndStreamAction endStream) {
    return new TableAction(null, null, null, null, null, null, endStream);
  }
}
