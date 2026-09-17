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
    requireSupportedType(type);
    Alias alias = parseAlias(sharedAs, type, name);

    if (objects.existsByShareAndSharedAsSchemaAndSharedAsTable(
        share, alias.schema(), alias.table())) {
      throw ApiException.alreadyExists(
          "alias '" + alias.formatted() + "' already exists in share '" + share.getName() + "'");
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
    if (objects.existsByShareAndName(share, resolved.identifier())) {
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
    object.setSharedAsSchema(alias.schema());
    object.setSharedAsTable(alias.table());
    return objects.save(object);
  }

  public void remove(ShareEntity share, String name, AssetType type, String sharedAs) {
    if (type == null) {
      throw ApiException.invalidParameter("dataObject.type is required");
    }
    requireSupportedType(type);
    SharedDataObjectEntity object;
    if (sharedAs != null && !sharedAs.isBlank()) {
      Alias alias = parseAlias(sharedAs, type, null);
      object =
          objects
              .findByShareAndSharedAsSchemaAndSharedAsTable(
                  share, alias.schema(), alias.table())
              .orElseThrow(() -> notIncluded(share, alias.formatted()));
    } else {
      requireText(name, "dataObject.name or dataObject.sharedAs");
      object =
          objects.findByShareAndName(share, name).orElseThrow(() -> notIncluded(share, name));
    }
    if (object.getType() != type) {
      throw notIncluded(share, object.getSharedAs());
    }
    objects.delete(object);
  }

  private static Alias parseAlias(String sharedAs, AssetType type, String name) {
    String raw = sharedAs == null || sharedAs.isBlank() ? name : sharedAs;
    if (raw == null || raw.isBlank()) {
      throw ApiException.invalidParameter("dataObject.sharedAs is required");
    }
    raw = ObjectNames.normalize(raw);
    String[] parts = raw.split("\\.", -1);
    try {
      if (type == AssetType.SCHEMA) {
        String schema = ObjectNames.validateSchemaName(parts[parts.length - 1]);
        return new Alias(schema, "");
      }
      if (parts.length < 2) {
        throw ApiException.invalidParameter(
            "dataObject.sharedAs must have at least 2 dot-separated names");
      }
      String schema = ObjectNames.validateSchemaName(parts[parts.length - 2]);
      String table = ObjectNames.validateAssetName(parts[parts.length - 1]);
      return new Alias(schema, table);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidParameter(e.getMessage());
    }
  }

  private static void requireSupportedType(AssetType type) {
    if (type != AssetType.SCHEMA && type != AssetType.TABLE) {
      throw ApiException.invalidParameter("dataObject.type " + type + " is not supported yet");
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

  private record Alias(String schema, String table) {
    String formatted() {
      return table.isEmpty() ? schema : schema + "." + table;
    }
  }
}
