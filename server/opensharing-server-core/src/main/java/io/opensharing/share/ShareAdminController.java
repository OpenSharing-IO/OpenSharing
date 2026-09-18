package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
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

/** Provider HTTP API for share CRUD. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

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

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ShareResponse create(UserContext user, @Valid @RequestBody CreateShareRequest request) {
    return ShareResponse.from(
        shares.create(
            user,
            ObjectNames.validateShareName(request.name()),
            request.displayName(),
            request.comment(),
            request.properties()));
  }

  @GetMapping
  public ListResponse<ShareResponse> list(UserContext user) {
    return ListResponse.of(shares.list(Pageable.unpaged()).stream().map(ShareResponse::from).toList());
  }

  @GetMapping("/{share}")
  public ShareResponse get(
      UserContext user,
      @PathVariable String share,
      @RequestParam(name = "include_shared_data", defaultValue = "false")
          boolean includeSharedData) {
    ShareEntity entity = shares.require(share);
    return includeSharedData
        ? ShareResponse.from(entity, objects.list(entity))
        : ShareResponse.from(entity);
  }

  @PatchMapping("/{share}")
  public ShareResponse update(
      UserContext user, @PathVariable String share, @Valid @RequestBody UpdateShareRequest request) {
    ShareEntity entity = shares.requireOwned(share, user);
    for (UpdateShareRequest.Update update : request.updates()) {
      UpdateShareRequest.DataObject dataObject = update.dataObject();
      switch (update.action()) {
        case ADD -> addObject(entity, user, dataObject);
        case REMOVE -> removeObject(entity, dataObject);
      }
    }
    return ShareResponse.from(
        shares.update(
            user, share, request.displayName(), request.comment(), request.properties()));
  }

  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String share) {
    shares.delete(share, user);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> listPermissions(
      UserContext user, @PathVariable String share) {
    return ListResponse.of(
        permissions.list(shares.require(share)).stream()
            .map(SharePermissionResponse::from)
            .toList());
  }

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

  private void addObject(
      ShareEntity share, UserContext user, UpdateShareRequest.DataObject dataObject) {
    String name = requireText(dataObject.name(), "dataObject.name");
    if (name.length() > 512) {
      throw ApiException.invalidParameter("dataObject.name must not exceed 512 characters");
    }
    AssetType type = requireSupportedType(dataObject.type());
    Alias alias = parseAlias(dataObject.sharedAs(), type, name);
    ResolvedAsset resolved = catalog.resolveAsset(AssetLookup.of(type, name), AuthContext.of(user));
    if (resolved.type() != type) {
      throw new CatalogException(
          "catalog resolved '" + name + "' as " + resolved.type() + " instead of " + type);
    }
    objects.add(share, name, type, resolved, alias.schema(), alias.table());
  }

  private void removeObject(ShareEntity share, UpdateShareRequest.DataObject dataObject) {
    AssetType type = requireSupportedType(dataObject.type());
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

  private static AssetType requireSupportedType(AssetType type) {
    if (type == null) {
      throw ApiException.invalidParameter("dataObject.type is required");
    }
    if (type != AssetType.SCHEMA && type != AssetType.TABLE) {
      throw ApiException.invalidParameter("dataObject.type " + type + " is not supported yet");
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
