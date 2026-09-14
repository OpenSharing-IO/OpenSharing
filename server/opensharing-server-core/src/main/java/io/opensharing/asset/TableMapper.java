package io.opensharing.asset;

import io.opensharing.catalog.AccessMode;
import io.opensharing.protocol.Table;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps stored objects onto protocol wire types. Recipients see the alias an object is shared as, never
 * its catalog name. Optional fields are emitted as null so they are omitted from responses rather than
 * sent as empty values.
 */
@Component
public class TableMapper {

  /** List responses describe where a table lives and how it can be read. */
  public Table listing(SharedDataObjectEntity object) {
    return new Table(
        object.getSharedAsName(),
        object.getSharedAsSchema(),
        object.getShare().getName(),
        object.getShare().getId(),
        object.getId(),
        object.getStorageLocation(),
        emptyToNull(object.getAuxiliaryLocations()),
        accessModes(object));
  }

  private static List<String> accessModes(SharedDataObjectEntity object) {
    if (object.getAccessModes().isEmpty()) {
      return null;
    }
    return object.getAccessModes().stream().map(AccessMode::wireName).sorted().toList();
  }

  private static List<String> emptyToNull(List<String> values) {
    return values == null || values.isEmpty() ? null : List.copyOf(values);
  }
}
