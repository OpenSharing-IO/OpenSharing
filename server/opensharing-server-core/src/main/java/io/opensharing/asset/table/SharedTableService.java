package io.opensharing.asset.table;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tables a recipient can list in a schema: stored table grants, plus current catalog children of a
 * shared schema. A table shared in its own right wins when both exist.
 */
@Service
@Transactional(readOnly = true)
public class SharedTableService {

  private final SharedDataObjectStore objects;
  private final CatalogConnector catalog;

  public SharedTableService(SharedDataObjectStore objects, CatalogConnector catalog) {
    this.objects = objects;
    this.catalog = catalog;
  }

  public Page<TableResponse> listInSchema(ShareEntity share, String schema, Pageable pageable) {
    String schemaName = ObjectNames.normalize(schema);
    if (!objects.existsInSchema(share, schemaName)) {
      throw ApiException.notFound(
          "schema '" + schema + "' does not exist in share '" + share.getName() + "'");
    }
    return objects
        .findSchemaGrant(share, schemaName)
        .map(grant -> merge(share, schemaName, grant, pageable))
        .orElseGet(() -> objects.listTablesInSchema(share, schemaName, pageable).map(this::fromStored));
  }

  private Page<TableResponse> merge(
      ShareEntity share, String schemaName, SharedDataObjectEntity grant, Pageable pageable) {
    Map<String, TableResponse> byName = new LinkedHashMap<>();
    for (SharedDataObjectEntity table : objects.listTablesInSchema(share, schemaName)) {
      TableResponse listed = fromStored(table);
      byName.put(ObjectNames.normalize(listed.name()), listed);
    }
    for (ResolvedAsset child :
        catalog.listChildren(AssetLookup.of(AssetType.SCHEMA, grant.getName()), owner(share))) {
      if (child.type() != AssetType.TABLE) {
        continue;
      }
      TableResponse listed = fromChild(share, schemaName, child);
      byName.putIfAbsent(ObjectNames.normalize(listed.name()), listed);
    }
    List<TableResponse> tables = new ArrayList<>(byName.values());
    tables.sort(Comparator.comparing(table -> ObjectNames.normalize(table.name())));
    int from = Math.min(Math.toIntExact(pageable.getOffset()), tables.size());
    int to = Math.min(from + pageable.getPageSize(), tables.size());
    return new org.springframework.data.domain.PageImpl<>(
        tables.subList(from, to), pageable, tables.size());
  }

  private TableResponse fromStored(SharedDataObjectEntity object) {
    ResolvedAsset resolved =
        catalog.resolveAsset(
            AssetLookup.of(object.getType(), object.getName()), owner(object.getShare()));
    return new TableResponse(
        object.getSharedAsTable(),
        object.getSharedAsSchema(),
        object.getShare().getName(),
        object.getShare().getId(),
        object.getId(),
        resolved.storageLocation(),
        emptyToNull(resolved.auxiliaryLocations()),
        null);
  }

  private static TableResponse fromChild(
      ShareEntity share, String schemaName, ResolvedAsset child) {
    return new TableResponse(
        lastSegment(child.identifier()),
        schemaName,
        share.getName(),
        share.getId(),
        null,
        child.storageLocation(),
        emptyToNull(child.auxiliaryLocations()),
        null);
  }

  private static AuthContext owner(ShareEntity share) {
    return AuthContext.of(new UserContext(share.getOwnerId(), null));
  }

  private static String lastSegment(String identifier) {
    int dot = identifier.lastIndexOf('.');
    return dot < 0 ? identifier : identifier.substring(dot + 1);
  }

  private static List<String> emptyToNull(List<String> values) {
    return values == null || values.isEmpty() ? null : values;
  }
}
