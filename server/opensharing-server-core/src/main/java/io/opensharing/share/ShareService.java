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
import io.opensharing.recipient.RecipientStore;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider HTTP API for share CRUD and recipient grants. Callers are authenticated by {@code
 * ProviderAuthenticationFilter}; any caller may read shares, only the owner may change them.
 */
public class ShareService {

  private final ShareStore shares;
  private final SharedDataObjectStore objects;
  private final SharePermissionStore permissions;
  private final RecipientStore recipients;
  private final CatalogConnector catalog;

  public ShareAdminController(
      ShareStore shares,
      SharedDataObjectStore objects,
      SharePermissionStore permissions,
      RecipientStore recipients,
      CatalogConnector catalog) {
    this.shares = shares;
    this.objects = objects;
    this.permissions = permissions;
    this.recipients = recipients;
    this.catalog = catalog;
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

  /** Lists every share by name, unpaged. */
  public ListResponse<ShareResponse> list() {
    // TODO: page with maxResults and pageToken.
    return ListResponse.of(shares.list().stream().map(ShareResponse::from).toList());
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

  /** {@code GET /shares/{share}/permissions}: lists who holds which privilege on the share. */
  @GetMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> listPermissions(
      UserContext user, @PathVariable String share) {
    // TODO: page with maxResults and pageToken.
    return ListResponse.of(
        permissions.list(shares.require(share)).stream()
            .map(SharePermissionResponse::from)
            .toList());
  }

  /**
   * {@code PATCH /shares/{share}/permissions}: grants and revokes privileges in order, then returns
   * the share's permissions. Owner only.
   */
  @PatchMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> updatePermissions(
      UserContext user,
      @PathVariable String share,
      @Valid @RequestBody UpdateSharePermissionsRequest request) {
    ShareEntity entity = shares.requireOwned(share, user);
    for (UpdateSharePermissionsRequest.Change change : request.changes()) {
      var recipient = recipients.require(change.recipientName());
      change.remove().forEach(privilege -> permissions.revoke(entity, recipient, privilege));
      change.add().forEach(privilege -> permissions.grant(entity, recipient, privilege));
    }
    return listPermissions(user, share);
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
