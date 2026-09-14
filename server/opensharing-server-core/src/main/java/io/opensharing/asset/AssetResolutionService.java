package io.opensharing.asset;

import io.opensharing.asset.storage.StoragePaths;
import io.opensharing.asset.storage.UrlSigners;
import io.opensharing.catalog.AccessMode;
import io.opensharing.catalog.AssetAccessDeniedException;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.AssetNotFoundException;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CatalogException;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.TableFormat;
import io.opensharing.catalog.UnsupportedAssetTypeException;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Bridges stored objects and the catalog. The store keeps a snapshot of what the catalog last
 * reported so listings are cheap, but anything that hands out access re-resolves first, so a
 * relocated or dropped source is never served from stale state.
 */
@Service
public class AssetResolutionService {

  private static final Logger log = LoggerFactory.getLogger(AssetResolutionService.class);

  private final CatalogConnector catalog;
  private final SharedDataObjectStore objects;
  private final OpenSharingProperties properties;
  private final UrlSigners signers;

  public AssetResolutionService(
      CatalogConnector catalog,
      SharedDataObjectStore objects,
      OpenSharingProperties properties,
      UrlSigners signers) {
    this.catalog = catalog;
    this.objects = objects;
    this.properties = properties;
    this.signers = signers;
  }

  /**
   * Resolves an object a provider admin is about to share, as the admin themselves, so the catalog
   * decides whether they may share it. A missing object is the admin's mistake, so it is reported as a
   * bad request rather than as a missing sharing-server resource.
   */
  public ResolvedAsset resolveForRegistration(
      AssetType type, String catalogName, CatalogCaller caller) {
    try {
      return catalog.resolveAsset(AssetLookup.of(type, catalogName), caller);
    } catch (AssetNotFoundException e) {
      throw ApiException.invalidParameter(
          type + " '" + catalogName + "' does not exist in the " + catalog.name() + " catalog");
    }
  }

  /**
   * Re-resolves a shared object and refreshes its stored snapshot when the catalog has moved it. A
   * source that has gone away, or that may no longer be read, is recorded on the object so it stops
   * being listed instead of failing every request from now on.
   */
  public ResolvedAsset resolveForServing(SharedDataObjectEntity object) {
    AssetLookup lookup = AssetLookup.of(object.getType(), object.getName());
    ResolvedAsset resolved;
    try {
      resolved = catalog.resolveAsset(lookup, shareOwner(object));
    } catch (AssetNotFoundException | AssetAccessDeniedException | UnsupportedAssetTypeException e) {
      throw noLongerServable(object, e);
    }
    if (snapshotChanged(object, resolved)) {
      applySnapshot(object, resolved);
      persistIfStored(object);
    }
    return resolved;
  }

  /**
   * Who a recipient's read is resolved as: the owner of the share it came through.
   *
   * <p>A recipient is nobody the catalog has heard of, so they cannot be the caller. The provider who
   * owns the share can be, and is the right one — a recipient reads by virtue of that provider's
   * access, so a provider who loses it should take their recipients' access with them, which is what
   * asking as them each time gets. There is no stored credential to ask with, and none needed: the
   * owner's id, recorded on the share when it was created, is enough for a connector that can ask the
   * catalog on their behalf — see {@code CatalogCaller.Credential.OnBehalfOf}. A grant revoked in the
   * catalog therefore takes effect on the very next read, since there is no separately stored
   * credential that could still work after it.
   */
  private CatalogCaller shareOwner(SharedDataObjectEntity object) {
    String ownerId = object.getShare().getOwnerId();
    return CatalogCaller.onBehalfOf(ownerId, ownerId);
  }

  /**
   * A table reached through a shared schema has no row to refresh, and wants none: it was assembled
   * from this very resolution, so there is nothing to bring up to date.
   */
  private void persistIfStored(SharedDataObjectEntity object) {
    if (!object.isInSharedSchema()) {
      objects.save(object);
    }
  }

  /**
   * A schema may only be shared if the catalog can say what it contains, since that is the only place
   * a recipient's table list can come from. Asked while the provider is still on the phone, so they
   * hear about it now rather than a recipient hearing about it later.
   */
  public void requireEnumerable(ResolvedAsset schema, CatalogCaller caller) {
    try {
      catalog.listChildren(AssetLookup.of(schema.type(), schema.identifier()), caller);
    } catch (UnsupportedAssetTypeException e) {
      throw ApiException.invalidParameter(
          "the "
              + catalog.name()
              + " catalog cannot list what is in '"
              + schema.identifier()
              + "', so its schemas cannot be shared as a whole; share their tables individually");
    }
  }

  /**
   * A table may only be shared if a recipient would have some way of reading it. Neither way is this
   * server's to promise on its own: directory access needs the catalog to vend for the location, and
   * url access needs a Delta log and this build's leave to replay it. A table with neither would be
   * accepted, listed, and then unreadable by any route a recipient could take. Asked while the
   * provider is still on the phone, for the same reason a schema's enumerability is.
   */
  public void requireServable(ResolvedAsset table) {
    if (!accessModesFor(table).isEmpty()) {
      return;
    }
    throw ApiException.invalidParameter(
        "no access mode could serve '"
            + table.identifier()
            + "' to a recipient: the "
            + catalog.name()
            + " catalog offers no credentials for "
            + table.storageLocation()
            + ", so it cannot be read as a directory, and "
            + whyNoUrl(table));
  }

  private String whyNoUrl(ResolvedAsset table) {
    if (!properties.getDelta().isUrlAccessEnabled()) {
      return "url access mode is turned off on this server";
    }
    if (table.format() != TableFormat.DELTA) {
      return "url access mode serves Delta tables, not "
          + (table.format() == null ? "tables of unstated format" : table.format().wireName())
          + " ones";
    }
    return "this build signs no url for the storage it is on, so url access mode cannot serve it "
        + "either";
  }

  /**
   * The tables a shared schema holds right now. Asked on every listing rather than remembered, which
   * is the point of sharing a schema: a table added to it today is shared today, and one dropped
   * stops being offered without the provider doing anything.
   *
   * <p>A table no access mode could serve is left out, as the connector already leaves out the ones
   * it cannot resolve at all: sharing a schema offers whatever is in it, and a table a recipient
   * could not read by any route is better absent than listed and dead to the touch.
   */
  public List<ResolvedAsset> listSharedSchemaTables(SharedDataObjectEntity schemaGrant) {
    AssetLookup lookup = AssetLookup.of(schemaGrant.getType(), schemaGrant.getName());
    try {
      return catalog.listChildren(lookup, shareOwner(schemaGrant)).stream()
          .filter(child -> child.type() == AssetType.TABLE)
          .filter(child -> !accessModesFor(child).isEmpty())
          .toList();
    } catch (AssetNotFoundException | AssetAccessDeniedException e) {
      throw noLongerServable(schemaGrant, e);
    }
  }

  /**
   * Asks the catalog, as the owner of the share, to mint credentials for an object's location. A
   * catalog may answer with one entry per prefix the asset spans, so the one covering the location
   * being read is selected here, longest prefix first, since a catalog can return both a bucket-wide
   * and a table-specific grant.
   *
   * <p>A catalog that authorizes this call separately from resolving — Unity Catalog wants {@code USE
   * CATALOG}, {@code USE SCHEMA} and {@code SELECT} before it will mint — can refuse it having just
   * answered where the table lives. That withdraws the object exactly as a refused resolution does,
   * and is reported the same way, which is what keeps the owner the call was made as out of an answer
   * a recipient reads.
   *
   * @return null when the table is on storage reached without a credential, which a catalog states
   *     by vending nothing for a local path; the read then goes ahead on the deployment's own
   *     filesystem access, which is all such a table was ever readable by
   */
  public StorageCredentials vendCredentials(
      SharedDataObjectEntity object, CredentialRequest request) {
    List<StorageCredentials> minted;
    try {
      minted = catalog.getStorageCredentials(request, shareOwner(object));
    } catch (AssetNotFoundException | AssetAccessDeniedException e) {
      throw noLongerServable(object, e);
    }
    if (minted == null || minted.isEmpty()) {
      if (StoragePaths.isLocal(request.storageLocation())) {
        return null;
      }
      throw new CatalogException(
          "the " + catalog.name() + " catalog vended no credentials for '" + request.identifier() + "'");
    }
    return minted.stream()
        .filter(credentials -> covers(credentials, request.storageLocation()))
        .max(Comparator.comparingInt(credentials -> credentials.prefix().length()))
        .orElseThrow(
            () ->
                new CatalogException(
                    "the "
                        + catalog.name()
                        + " catalog vended credentials for "
                        + minted.stream().map(StorageCredentials::prefix).toList()
                        + ", none covering '"
                        + request.storageLocation()
                        + "'"));
  }

  private static boolean covers(StorageCredentials credentials, String location) {
    String prefix = credentials.prefix();
    return prefix == null || prefix.isBlank() || location.startsWith(prefix);
  }

  /** Copies catalog state onto the stored object, which also revives one that had stopped resolving. */
  public void applySnapshot(SharedDataObjectEntity object, ResolvedAsset resolved) {
    object.setSourceAssetId(resolved.catalogAssetId());
    object.setStorageLocation(resolved.storageLocation());
    object.setSourceFormat(resolved.format());
    object.setSourceSubtype(resolved.subtype());
    object.setAccessModes(accessModesFor(resolved));
    object.setAuxiliaryLocations(resolved.auxiliaryLocations());
    object.setStatus(SharedObjectStatus.ACTIVE);
  }

  /**
   * The modes a recipient may pick from, which the protocol lets them pick from freely — so a mode
   * advertised here and then answered with {@code NOT_IMPLEMENTED} would send a client down a road
   * with no end.
   *
   * <p>Directory access is the catalog's to offer, because it comes down to whether the catalog will
   * scope credentials to the location, and only the catalog knows: Unity Catalog mints for a bucket
   * and has nothing to mint for a path on this machine. A location alone was once taken as enough,
   * which offered the mode to every table with somewhere to live, including the ones no grant exists
   * for. Url access is this server's to offer, and takes three things: a Delta log to replay, leave
   * to replay it, and a signer for the storage it sits on — a build that cannot sign for a scheme
   * would replay the log and then refuse every file it found.
   */
  private Set<AccessMode> accessModesFor(ResolvedAsset resolved) {
    Set<AccessMode> modes = new LinkedHashSet<>(resolved.accessModes());
    if (resolved.storageLocation() == null || resolved.storageLocation().isBlank()) {
      // A directory read is given nothing but the location, so the mode cannot stand without one.
      modes.remove(AccessMode.DIR);
    }
    if (properties.getDelta().isUrlAccessEnabled()
        && resolved.format() == TableFormat.DELTA
        && signers.canSign(resolved.storageLocation())) {
      modes.add(AccessMode.URL);
    } else {
      modes.remove(AccessMode.URL);
    }
    return modes;
  }

  /**
   * The two ways the catalog can withdraw an object the server is trying to serve. Both are asked
   * the same way of anything shared, whether the question was about the object itself or about the
   * tables of a schema it grants, so the answer is phrased in one place.
   */
  private ApiException noLongerServable(SharedDataObjectEntity object, CatalogException withdrawn) {
    if (withdrawn instanceof AssetNotFoundException) {
      return unservable(
          object,
          SharedObjectStatus.SOURCE_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          ErrorCodes.RESOURCE_DOES_NOT_EXIST,
          "'" + object.getName() + "' no longer exists in the " + catalog.name() + " catalog");
    }
    // Shareable when it was added, so something in the catalog changed: a table replaced by a view,
    // or recreated in a format this server does not serve. Withdrawn rather than left to answer that
    // the request was invalid on every read from now on, which is what it would say — the request is
    // the same one that worked yesterday. Which asset, and what it became, is in the log with the
    // catalog's own words, and the recipient is told what they can act on: it is gone from the share.
    if (withdrawn instanceof UnsupportedAssetTypeException) {
      log.warn(
          "'{}' can no longer be shared and has been withdrawn from '{}': {}",
          object.getName(),
          object.getShare().getName(),
          withdrawn.getMessage());
      return unservable(
          object,
          SharedObjectStatus.SOURCE_NOT_SHAREABLE,
          HttpStatus.NOT_FOUND,
          ErrorCodes.RESOURCE_DOES_NOT_EXIST,
          "'" + object.getSharedAs() + "' is no longer available in this share");
    }
    return unservable(
        object,
        SharedObjectStatus.PERMISSION_DENIED,
        HttpStatus.FORBIDDEN,
        ErrorCodes.PERMISSION_DENIED,
        "the sharing server may no longer read '" + object.getName() + "'");
  }

  private ApiException unservable(
      SharedDataObjectEntity object,
      SharedObjectStatus status,
      HttpStatus httpStatus,
      String errorCode,
      String message) {
    if (object.getStatus() != status) {
      object.setStatus(status);
      persistIfStored(object);
    }
    return new ApiException(httpStatus, errorCode, message);
  }

  private boolean snapshotChanged(SharedDataObjectEntity object, ResolvedAsset resolved) {
    return object.getStatus() != SharedObjectStatus.ACTIVE
        || !Objects.equals(object.getStorageLocation(), resolved.storageLocation())
        || !Objects.equals(object.getSourceAssetId(), resolved.catalogAssetId())
        || !Objects.equals(object.getSourceSubtype(), resolved.subtype())
        || object.getSourceFormat() != resolved.format()
        || !object.getAccessModes().equals(accessModesFor(resolved))
        || !object.getAuxiliaryLocations().equals(resolved.auxiliaryLocations());
  }
}
