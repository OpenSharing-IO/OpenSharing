package io.opensharing.asset;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.share.ShareEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves catalog objects and persists their membership in a share. */
@Service
@Transactional
public class SharedDataObjectStore {

  private final SharedDataObjectRepository objects;
  private final CatalogConnector catalog;

  public SharedDataObjectStore(
      SharedDataObjectRepository objects, CatalogConnector catalog) {
    this.objects = objects;
    this.catalog = catalog;
  }

  public SharedDataObjectEntity add(
      ShareEntity share,
      UserContext user,
      String name,
      AssetType type,
      String sharedAs) {
    requireText(name, "dataObject.name");
    if (name.length() > 512) {
      throw ApiException.invalidParameter("dataObject.name must not exceed 512 characters");
    }
    if (type == null) {
      throw ApiException.invalidParameter("dataObject.type is required");
    }
    validateAlias(sharedAs, type);

    if (objects.existsByShareAndSharedAsLower(share, ObjectNames.normalize(sharedAs))) {
      throw ApiException.alreadyExists(
          "alias '" + sharedAs + "' already exists in share '" + share.getName() + "'");
    }

    ResolvedAsset resolved = catalog.resolveAsset(AssetLookup.of(type, name), user);
    if (resolved.type() != type) {
      throw new CatalogException(
          "catalog resolved '" + name + "' as " + resolved.type() + " instead of " + type);
    }
    requireText(resolved.identifier(), "catalog asset identifier");
    if (resolved.identifier().length() > 512) {
      throw new CatalogException("catalog asset identifier must not exceed 512 characters");
    }
    if (objects.existsByShareAndNameLower(
        share, ObjectNames.normalize(resolved.identifier()))) {
      throw ApiException.alreadyExists(
          "'" + resolved.identifier() + "' is already included in share '" + share.getName() + "'");
    }

    SharedDataObjectEntity object = new SharedDataObjectEntity();
    object.setShare(share);
    object.setSourceAssetId(resolved.catalogAssetId());
    object.setName(resolved.identifier());
    object.setType(type);
    object.setSourceSubtype(resolved.subtype());
    object.setSourceFormat(resolved.format());
    object.setSharedAs(sharedAs);
    object.setAddedBy(user.id());
    return objects.save(object);
  }

  public void remove(ShareEntity share, String name, String sharedAs) {
    SharedDataObjectEntity object;
    if (sharedAs != null && !sharedAs.isBlank()) {
      object =
          objects
              .findByShareAndSharedAsLower(share, ObjectNames.normalize(sharedAs))
              .orElseThrow(() -> notIncluded(share, sharedAs));
    } else {
      requireText(name, "dataObject.name or dataObject.sharedAs");
      object =
          objects
              .findByShareAndNameLower(share, ObjectNames.normalize(name))
              .orElseThrow(() -> notIncluded(share, name));
    }
    objects.delete(object);
  }

  private static void validateAlias(String sharedAs, AssetType type) {
    requireText(sharedAs, "dataObject.sharedAs");
    String[] parts = sharedAs.split("\\.", -1);
    int expectedParts = type == AssetType.SCHEMA ? 1 : 2;
    if (parts.length != expectedParts) {
      throw ApiException.invalidParameter(
          "dataObject.sharedAs must have "
              + expectedParts
              + (expectedParts == 1 ? " name" : " dot-separated names"));
    }
    ObjectNames.validateSchemaName(parts[0]);
    if (expectedParts == 2) {
      ObjectNames.validateAssetName(parts[1]);
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.invalidParameter(field + " is required");
    }
  }

  private static ApiException notIncluded(ShareEntity share, String object) {
    return ApiException.notFound(
        "'" + object + "' is not included in share '" + share.getName() + "'");
  }
}
