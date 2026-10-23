package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.Transactions;
import io.opensharing.asset.SharedAssetResolver;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Ownership;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientEntity;
import io.opensharing.recipient.RecipientStore;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Provider share CRUD and recipient grants for a caller the host has authenticated. Creating a
 * share needs CREATE_SHARE from the catalog, and only the owner may change it. The owner and
 * callers with USE_SHARE from the catalog may read a share; listing without USE_SHARE returns only
 * the caller's own shares.
 */
public class ShareService {

  private final CatalogConnector catalog;
  private final Transactions tx;
  private final ShareStore shares;
  private final SharedDataObjectStore objects;
  private final SharePermissionStore permissions;
  private final RecipientStore recipients;

  public ShareService(
      CatalogConnector catalog,
      Transactions tx,
      ShareStore shares,
      SharedDataObjectStore objects,
      SharePermissionStore permissions,
      RecipientStore recipients) {
    this.catalog = catalog;
    this.tx = tx;
    this.shares = shares;
    this.objects = objects;
    this.permissions = permissions;
    this.recipients = recipients;
  }

  /** Creates a share owned by the caller, who needs CREATE_SHARE from the catalog. */
  public ShareResponse create(UserContext user, CreateShareRequest request) {
    // The host has already authenticated the caller. A host that also checks CREATE_SHARE at its
    // endpoint can make this a no-op.
    catalog.authorize(AuthContext.of(user), Privilege.CREATE_SHARE);
    return ShareResponse.from(
        shares.create(
            user,
            ObjectNames.validateShareName(request.name()),
            request.displayName(),
            request.comment(),
            validateProperties(request.properties())));
  }

  /** Lists one page of shares by name: every share with USE_SHARE, otherwise the caller's own. */
  public ListResponse<ShareResponse> list(UserContext user, Integer maxResults, String pageToken) {
    Pagination.Slice<ShareEntity> slice =
        holds(user, Privilege.USE_SHARE)
            ? shares::list
            : (offset, limit) -> shares.listOwnedBy(user.userId(), offset, limit);
    return Pagination.page(maxResults, pageToken, slice, ShareResponse::from);
  }

  /**
   * Gets a share by name in any case, with the objects in it when {@code includeSharedData}. Owner
   * or USE_SHARE.
   */
  public ShareResponse get(UserContext user, String share, boolean includeSharedData) {
    ShareEntity entity = requireReadable(user, share);
    if (!includeSharedData) {
      return ShareResponse.from(entity);
    }
    List<SharedDataObjectEntity> includedObjects = objects.list(entity);
    includedObjects.forEach(sharedObject -> requireStillShared(entity, sharedObject));
    return ShareResponse.from(entity, includedObjects);
  }

  /**
   * Removes objects, then adds objects, then updates the fields set in {@code request}, all in one
   * transaction: either every change commits or none does. Owner only.
   */
  public ShareResponse update(UserContext user, String share, UpdateShareRequest request) {
    shares.getOwnedOrThrow(share, user);
    Map<String, String> properties = validateProperties(request.properties());
    List<Removal> removals = new ArrayList<>();
    List<UpdateShareRequest.DataObject> adds = new ArrayList<>();
    for (UpdateShareRequest.Update update : request.updates()) {
      if (update == null) {
        throw ApiException.invalidParameter("updates must not contain null");
      }
      if (update.action() == null) {
        throw ApiException.invalidParameter("updates.action is required");
      }
      if (update.dataObject() == null) {
        throw ApiException.invalidParameter("updates.dataObject is required");
      }
      switch (update.action()) {
        case ADD -> adds.add(update.dataObject());
        case REMOVE -> removals.add(removal(update.dataObject()));
      }
    }
    // Catalog calls finish before the transaction opens, so it holds no connection while waiting.
    List<Addition> additions = adds.stream().map(dataObject -> addition(user, dataObject)).toList();
    ShareEntity updated =
        tx.inTransaction(
            false,
            em -> {
              ShareEntity entity = shares.getOwnedOrThrow(em, share, user);
              for (Removal removal : removals) {
                removal.apply(em, objects, entity);
              }
              for (Addition addition : additions) {
                objects.add(
                    em,
                    entity,
                    addition.name(),
                    addition.type(),
                    addition.resolved(),
                    addition.alias().schema(),
                    addition.alias().table());
              }
              shares.update(entity, request.displayName(), request.comment(), properties);
              return entity;
            });
    return ShareResponse.from(updated);
  }

  /** Deletes the share. Owner only. */
  public void delete(UserContext user, String share) {
    shares.delete(share, user);
  }

  /**
   * Lists one page of who holds which privilege on the share, by recipient name. Owner or
   * USE_SHARE.
   */
  public ListResponse<SharePermissionResponse> listPermissions(
      UserContext user, String share, Integer maxResults, String pageToken) {
    return permissionPage(requireReadable(user, share), maxResults, pageToken);
  }

  private ListResponse<SharePermissionResponse> permissionPage(
      ShareEntity entity, Integer maxResults, String pageToken) {
    return Pagination.page(
        maxResults,
        pageToken,
        (offset, limit) -> permissions.list(entity, offset, limit),
        SharePermissionResponse::from);
  }

  /**
   * Applies the changes in order, each revoking then granting, all in one transaction: either every
   * change commits or none does. Returns the first page of the share's permissions. Owner only.
   */
  public ListResponse<SharePermissionResponse> updatePermissions(
      UserContext user, String share, UpdateSharePermissionsRequest request) {
    shares.getOwnedOrThrow(share, user);
    List<RecipientChange> changes = new ArrayList<>();
    for (UpdateSharePermissionsRequest.Change change : request.changes()) {
      if (change == null) {
        throw ApiException.invalidParameter("changes must not contain null");
      }
      if (Stream.concat(change.add().stream(), change.remove().stream())
          .anyMatch(Objects::isNull)) {
        throw ApiException.invalidParameter("changes.add and changes.remove must not contain null");
      }
      RecipientEntity recipient =
          recipients.getOrThrow(requireText(change.recipientName(), "changes.recipientName"));
      changes.add(new RecipientChange(recipient, change));
    }
    tx.inTransaction(
        false,
        em -> {
          ShareEntity entity = shares.getOwnedOrThrow(em, share, user);
          for (RecipientChange change : changes) {
            for (SharePrivilege privilege : change.change().remove()) {
              permissions.revoke(em, entity, change.recipient(), privilege);
            }
            for (SharePrivilege privilege : change.change().add()) {
              permissions.grant(em, entity, change.recipient(), privilege);
            }
          }
          return null;
        });
    return permissionPage(shares.getOrThrow(share), null, null);
  }

  private ShareEntity requireReadable(UserContext user, String share) {
    ShareEntity entity = shares.getOrThrow(share);
    if (!Ownership.owns(user, entity.getOwnerId())) {
      catalog.authorize(AuthContext.of(user), Privilege.USE_SHARE);
    }
    return entity;
  }

  // Resolves an included object as the share's owner, whom recipients read it as.
  private void requireStillShared(ShareEntity share, SharedDataObjectEntity sharedObject) {
    AuthContext owner = AuthContext.of(UserContext.fromUserIdAndName(share.getOwnerId(), null));
    if (SharedAssetResolver.resolve(catalog, sharedObject, owner).isEmpty()) {
      throw ApiException.conflict(
          sharedObject.getType().name().toLowerCase(Locale.ROOT)
              + " '"
              + sharedObject.getSharedAs()
              + "' in share '"
              + share.getName()
              + "' was dropped or replaced in the catalog; remove it from the share");
    }
  }

  private boolean holds(UserContext user, Privilege privilege) {
    try {
      catalog.authorize(AuthContext.of(user), privilege);
      return true;
    } catch (CatalogAuthorizationException e) {
      return false;
    }
  }

  // Resolves the object in the catalog on behalf of the caller and checks the declared type.
  private Addition addition(UserContext user, UpdateShareRequest.DataObject dataObject) {
    String name = requireText(dataObject.name(), "dataObject.name");
    if (name.length() > 512) {
      throw ApiException.invalidParameter("dataObject.name must not exceed 512 characters");
    }
    AssetType type = requireType(dataObject.type());
    Alias alias = parseAlias(dataObject.sharedAs(), type, name);
    // The catalog is responsible for checking that the caller holds SELECT on the asset. A host
    // that already checks it at its endpoint can skip that check in resolveAsset.
    ResolvedAsset resolved = catalog.resolveAsset(new Asset(type, name), AuthContext.of(user));
    if (resolved.type() != type) {
      throw new CatalogException(
          "catalog resolved '" + name + "' as " + resolved.type() + " instead of " + type);
    }
    return new Addition(name, type, resolved, alias);
  }

  // Removes by alias when sharedAs is given, otherwise by catalog name.
  private static Removal removal(UpdateShareRequest.DataObject dataObject) {
    AssetType type = requireType(dataObject.type());
    if (dataObject.sharedAs() != null && !dataObject.sharedAs().isBlank()) {
      return new Removal(type, parseAlias(dataObject.sharedAs(), type, null), null);
    }
    return new Removal(
        type, null, requireText(dataObject.name(), "dataObject.name or dataObject.sharedAs"));
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

  private static Map<String, String> validateProperties(Map<String, String> properties) {
    if (properties != null) {
      for (Map.Entry<String, String> property : properties.entrySet()) {
        if (property.getValue() == null) {
          throw ApiException.invalidParameter(
              "property '" + property.getKey() + "' must not be null");
        }
      }
    }
    return properties;
  }

  private record Alias(String schema, String table) {}

  private record Addition(String name, AssetType type, ResolvedAsset resolved, Alias alias) {}

  private record RecipientChange(
      RecipientEntity recipient, UpdateSharePermissionsRequest.Change change) {}

  // Exactly one of alias and name is set.
  private record Removal(AssetType type, Alias alias, String name) {

    void apply(EntityManager em, SharedDataObjectStore objects, ShareEntity share) {
      if (alias != null) {
        objects.removeByAlias(em, share, type, alias.schema(), alias.table());
      } else {
        objects.removeByFullName(em, share, type, name);
      }
    }
  }
}
