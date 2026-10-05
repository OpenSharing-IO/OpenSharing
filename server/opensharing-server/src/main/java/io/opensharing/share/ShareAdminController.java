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
 * Provider HTTP API for share CRUD. Callers are authenticated by {@code
 * ProviderAuthenticationFilter}; any caller may read shares, only the owner may change them.
 */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareStore shares;
  private final SharedDataObjectStore objects;
  private final CatalogConnector catalog;

  public ShareAdminController(
      ShareStore shares, SharedDataObjectStore objects, CatalogConnector catalog) {
    this.shares = shares;
    this.objects = objects;
    this.catalog = catalog;
  }

  /** {@code POST /shares}: creates a share owned by the caller. Needs CREATE_SHARE. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ShareResponse create(UserContext user, @RequestBody CreateShareRequest request) {
    return shares.create(user, request);
  }

  /** {@code GET /shares}: lists every share by name, unpaged. */
  @GetMapping
  public ListResponse<ShareResponse> list(UserContext user) {
    return shares.list();
  }

  /**
   * {@code GET /shares/{share}}: gets a share by name in any case. With {@code
   * include_shared_data=true}, also lists the objects in the share.
   */
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

  /**
   * {@code PATCH /shares/{share}}: applies the object adds and removes in order, then updates the
   * fields set in the body. Owner only.
   */
  @PatchMapping("/{share}")
  public ShareResponse update(
      UserContext user,
      @PathVariable String share,
      @Valid @RequestBody UpdateShareRequest request) {
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

  /** {@code DELETE /shares/{share}}: deletes the share. Owner only. */
  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String share) {
    shares.delete(user, share);
    return ResponseEntity.noContent().build();
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
