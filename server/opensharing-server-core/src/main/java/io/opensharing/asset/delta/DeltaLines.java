package io.opensharing.asset.delta;

import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.catalog.AccessMode;
import io.opensharing.protocol.TableAction;
import java.util.List;

/**
 * The lines of a Delta read response, in one response format.
 *
 * <p>Both formats answer the same three questions from the same log and the same signed urls; what
 * differs is what a line says. So the reading, the signing and the version arithmetic all happen
 * before this seam, and an implementation only writes down what it was given.
 */
interface DeltaLines {

  /** What a reader must support, and what the table is. */
  List<TableAction> metadata(
      SharedDataObjectEntity object,
      DeltaTable table,
      boolean statedVersion,
      DeltaSharingCapabilities capabilities);

  /** The same, then one line per file the recipient may read. */
  List<TableAction> query(
      SharedDataObjectEntity object,
      DeltaTable table,
      boolean statedVersion,
      DeltaSharingCapabilities capabilities);

  /** The same, then the window's own entries in the order the log recorded them. */
  List<TableAction> changes(
      SharedDataObjectEntity object,
      DeltaTableService.ChangeFeed feed,
      History history,
      DeltaSharingCapabilities capabilities);

  /**
   * Which changes inside a window a reader asked to be told about, beyond the files themselves.
   *
   * @param protocol only ever honoured in delta format, which is the only one with a line for it
   */
  record History(boolean metadata, boolean protocol) {}

  /** Where the table can be reached, which the log knows nothing about. */
  static List<String> accessModes(SharedDataObjectEntity object) {
    if (object.getAccessModes().isEmpty()) {
      return null;
    }
    return object.getAccessModes().stream().map(AccessMode::wireName).sorted().toList();
  }

  /** The table's root, for a recipient who may read it as a directory. */
  static String location(SharedDataObjectEntity object) {
    return object.getStorageLocation();
  }

  /** The locations a recipient may read directly, or null when there are none to name. */
  static List<String> auxiliaryLocations(SharedDataObjectEntity object) {
    return object.getAuxiliaryLocations().isEmpty()
        ? null
        : List.copyOf(object.getAuxiliaryLocations());
  }
}
