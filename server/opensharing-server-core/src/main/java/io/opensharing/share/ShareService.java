package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.recipient.RecipientStore;

/**
 * Provider share CRUD and recipient grants. The host authenticates the caller and checks
 * CREATE_SHARE before {@link #create}; any caller may read shares, only the owner may change them.
 */
public class ShareService {

  private final ShareStore shares;
  private final SharedDataObjectStore objects;
  private final SharePermissionStore permissions;
  private final RecipientStore recipients;
  private final CatalogConnector catalog;
  private final Pagination pagination;

  public ShareService(
      ShareStore shares,
      SharedDataObjectStore objects,
      SharePermissionStore permissions,
      RecipientStore recipients,
      CatalogConnector catalog,
      Pagination pagination) {
    this.shares = shares;
    this.objects = objects;
    this.permissions = permissions;
    this.recipients = recipients;
    this.catalog = catalog;
    this.pagination = pagination;
  }

  /** Creates a share owned by {@code user}. */
  public ShareResponse create(UserContext user, CreateShareRequest request) {
    return ShareResponse.from(
        shares.create(
            user,
            ObjectNames.validateShareName(request.name()),
            request.displayName(),
            request.comment(),
            request.properties()));
  }

  /** Lists one page of shares by name. */
  public ListResponse<ShareResponse> list(Integer maxResults, String pageToken) {
    return pagination.page(maxResults, pageToken, shares::list, ShareResponse::from);
  }

  /** Gets a share by name in any case, with the objects in it when {@code includeSharedData}. */
  public ShareResponse get(String share, boolean includeSharedData) {
    ShareEntity entity = shares.require(share);
    return includeSharedData
        ? ShareResponse.from(entity, objects.list(entity))
        : ShareResponse.from(entity);
  }

  /**
   * Applies the object adds and removes in order, then updates the fields set in {@code request}.
   * Owner only.
   */
  public ShareResponse update(UserContext user, String share, UpdateShareRequest request) {
    ShareEntity entity = shares.requireOwned(share, user);
    for (UpdateShareRequest.Update update : request.updates()) {
      if (update == null || update.action() == null) {
        throw ApiException.invalidParameter("updates.action is required");
      }
      if (update.dataObject() == null) {
        throw ApiException.invalidParameter("updates.dataObject is required");
      }
      switch (update.action()) {
        case ADD -> addObject(entity, user, update.dataObject());
        case REMOVE -> removeObject(entity, update.dataObject());
      }
    }
    return ShareResponse.from(
        shares.update(
            user, share, request.displayName(), request.comment(), request.properties()));
  }

  /** Deletes the share. Owner only. */
  public void delete(UserContext user, String share) {
    shares.delete(share, user);
  }

  /** Lists one page of who holds which privilege on the share, by recipient name. */
  public ListResponse<SharePermissionResponse> listPermissions(
      String share, Integer maxResults, String pageToken) {
    ShareEntity entity = shares.require(share);
    return pagination.page(
        maxResults,
        pageToken,
        (offset, limit) -> permissions.list(entity, offset, limit),
        SharePermissionResponse::from);
  }

  /**
   * Grants and revokes privileges in order, then returns the first page of the share's
   * permissions. Owner only.
   */
  public ListResponse<SharePermissionResponse> updatePermissions(
      UserContext user, String share, UpdateSharePermissionsRequest request) {
    ShareEntity entity = shares.requireOwned(share, user);
    for (UpdateSharePermissionsRequest.Change change : request.changes()) {
      RecipientEntity recipient =
          recipients.require(requireText(change.recipientName(), "changes.recipientName"));
      change.remove().forEach(privilege -> permissions.revoke(entity, recipient, privilege));
      change.add().forEach(privilege -> permissions.grant(entity, recipient, privilege));
    }
    return listPermissions(share, null, null);
  }

  // Resolves the object in the catalog on behalf of the caller and checks the declared type.
  private void addObject(
      ShareEntity share, UserContext user, UpdateShareRequest.DataObject dataObject) {
    String name = requireText(dataObject.name(), "dataObject.name");
    if (name.length() > 512) {
      throw ApiException.invalidParameter("dataObject.name must not exceed 512 characters");
    }
    AssetType type = requireType(dataObject.type());
    Alias alias = parseAlias(dataObject.sharedAs(), type, name);
    ResolvedAsset resolved = catalog.resolveAsset(new Asset(type, name), AuthContext.of(user));
    if (resolved.type() != type) {
      throw new CatalogException(
          "catalog resolved '" + name + "' as " + resolved.type() + " instead of " + type);
    }
    objects.add(share, name, type, resolved, alias.schema(), alias.table());
  }

  // Removes by alias when sharedAs is given, otherwise by catalog name.
  private void removeObject(ShareEntity share, UpdateShareRequest.DataObject dataObject) {
    AssetType type = requireType(dataObject.type());
    if (dataObject.sharedAs() != null && !dataObject.sharedAs().isBlank()) {
      Alias alias = parseAlias(dataObject.sharedAs(), type, null);
      objects.removeByAlias(share, type, alias.schema(), alias.table());
    } else {
      objects.removeByName(
          share, type, requireText(dataObject.name(), "dataObject.name or dataObject.sharedAs"));
    }
  }

  /**
   * Lowercased {@code sharedAs}, or {@code name} if omitted; catalog prefix is dropped.
   *
   * <p>{@code Main.Sales.Orders} (TABLE) → {@code sales} / {@code orders}. {@code Main.Sales}
   * (SCHEMA) → {@code sales}. {@code Sales.Orders} (TABLE) → {@code sales} / {@code orders}.
   */
  private static Alias parseAlias(String sharedAs, AssetType type, String name) {
    String raw = sharedAs == null || sharedAs.isBlank() ? name : sharedAs;
    if (raw == null || raw.isBlank()) {
      throw ApiException.invalidParameter("dataObject.sharedAs is required");
    }
    raw = ObjectNames.normalize(raw);
    String[] parts = raw.split("\\.", -1);
    if (type == AssetType.SCHEMA) {
      return new Alias(ObjectNames.validateSchemaName(parts[parts.length - 1]), "");
    }
    if (parts.length < 2) {
      throw ApiException.invalidParameter(
          "dataObject.sharedAs must have at least 2 dot-separated names");
    }
    return new Alias(
        ObjectNames.validateSchemaName(parts[parts.length - 2]),
        ObjectNames.validateAssetName(parts[parts.length - 1]));
  }

  private static AssetType requireType(AssetType type) {
    if (type == null) {
      throw ApiException.invalidParameter("dataObject.type is required");
    }
    return type;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.invalidParameter(field + " is required");
    }
    return value;
  }

  private record Alias(String schema, String table) {}
}
