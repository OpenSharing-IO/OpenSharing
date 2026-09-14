package io.opensharing.asset;

import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import io.opensharing.principal.Caller;
import io.opensharing.share.ShareEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds and removes objects in a share, refusing anything the catalog will not hand over. */
@Service
@Transactional
public class SharedDataObjectService {

  private final SharedDataObjectStore objects;
  private final AssetResolutionService resolution;

  public SharedDataObjectService(
      SharedDataObjectStore objects, AssetResolutionService resolution) {
    this.objects = objects;
    this.resolution = resolution;
  }

  /**
   * @param catalogName the canonical catalog name, e.g. {@code main.sales.orders}
   * @param sharedAs the two-level alias recipients see, defaulting to the last two levels of the
   *     catalog name
   * @param caller the principal to resolve as, so the catalog decides whether they may share it
   */
  public SharedDataObjectEntity add(
      ShareEntity share,
      Caller author,
      CatalogCaller caller,
      String catalogName,
      AssetType type,
      String sharedAs) {
    requireSupported(type);
    requireNameFits(catalogName);
    SharedDataObjectEntity object = new SharedDataObjectEntity();
    object.setShare(share);
    object.setName(catalogName);
    object.setType(type);
    if (type == AssetType.SCHEMA) {
      object.setSharedAsSchema(
          sharedAs == null || sharedAs.isBlank()
              ? SharedAliases.defaultSchemaFor(catalogName)
              : sharedAs);
    } else {
      object.setSharedAs(
          sharedAs == null || sharedAs.isBlank() ? SharedAliases.defaultFor(catalogName) : sharedAs);
    }
    object.setAddedBy(author.principalId());
    object.setUpdatedBy(author.principalId());

    if (objects.existsSource(share, catalogName)) {
      throw ApiException.alreadyExists(
          "'" + catalogName + "' is already shared in '" + share.getName() + "'");
    }
    requireAliasIsFree(share, object);

    ResolvedAsset resolved = resolution.resolveForRegistration(type, catalogName, caller);
    if (type == AssetType.SCHEMA) {
      resolution.requireEnumerable(resolved, caller);
    } else {
      requireStorageLocation(resolved);
      resolution.requireServable(resolved);
    }
    resolution.applySnapshot(object, resolved);
    return objects.save(object);
  }

  public void remove(SharedDataObjectEntity object) {
    objects.delete(object);
  }

  /** Said here so an over-long name is a bad request, not a constraint violation at flush time. */
  private static void requireNameFits(String catalogName) {
    if (catalogName.length() > SharedDataObjectEntity.MAX_SOURCE_NAME_LENGTH) {
      throw ApiException.invalidParameter(
          "the catalog name must not exceed "
              + SharedDataObjectEntity.MAX_SOURCE_NAME_LENGTH
              + " characters");
    }
  }

  private static void requireSupported(AssetType type) {
    if (type != AssetType.TABLE && type != AssetType.SCHEMA) {
      throw ApiException.invalidParameter(
          "only TABLE and SCHEMA objects can be shared so far, not " + type);
    }
  }

  /**
   * Two objects may not claim the same alias, and a schema grant claims a whole schema level: sharing
   * two catalog schemas as one name would leave a recipient's table ambiguous between them. A table
   * shared in its own right may still sit alongside a shared schema, which is how a provider adds one
   * table from elsewhere into the same schema a recipient sees.
   */
  private void requireAliasIsFree(ShareEntity share, SharedDataObjectEntity object) {
    boolean taken =
        object.getType() == AssetType.SCHEMA
            ? objects.findSchemaGrant(share, object.getSharedAsSchema()).isPresent()
            : objects.existsSharedAs(share, object.getSharedAsSchema(), object.getSharedAsName());
    if (taken) {
      throw ApiException.alreadyExists(
          "'" + share.getName() + "." + object.getSharedAs() + "' already exists");
    }
  }

  /** A shared object needs a location for the recipient to read from. */
  private static void requireStorageLocation(ResolvedAsset resolved) {
    if (resolved.storageLocation() == null || resolved.storageLocation().isBlank()) {
      throw ApiException.invalidParameter(
          "the catalog did not report a storage location for '" + resolved.identifier() + "'");
    }
  }
}
