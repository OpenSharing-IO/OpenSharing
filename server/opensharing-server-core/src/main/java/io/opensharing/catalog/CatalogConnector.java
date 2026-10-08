package io.opensharing.catalog;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.util.List;

/**
 * OpenSharing's only interface to a data catalog. Every catalog interaction goes through this
 * narrow waist: no other code talks to a catalog directly.
 *
 * <p>OpenSharing doesn't own assets, permissions or storage access; the catalog stays the source of
 * truth for all three. A connector resolves catalog names to assets and their storage locations,
 * mints storage credentials scoped to those locations, and maps callers to catalog users. Every
 * call carries an {@link AuthContext}, so the catalog authenticates the caller and enforces the
 * permission model OpenSharing needs, while the OpenSharing server implements the core sharing
 * protocol.
 */
public interface CatalogConnector {

  /** Identifier used to select this connector in configuration. */
  String name();

  /**
   * Resolves an asset as {@code auth}. Also the existence check: missing assets throw {@link
   * AssetNotFoundException}.
   */
  ResolvedAsset resolveAsset(Asset asset, AuthContext auth);

  /**
   * Lists one page of the assets under a container such as a schema, as {@code auth}. The listing
   * is flat: catalogs with nested containers, such as folders inside a schema, must walk them and
   * return every descendant asset rather than the intermediate containers. Optional: catalogs that
   * cannot enumerate throw {@link UnsupportedAssetTypeException}.
   *
   * <p>Callers pass a null {@code pageToken} for the first page, then the previous page's {@code
   * nextPageToken} until it is null, and may stop early. A catalog without native paging may
   * return the whole listing in one page.
   *
   * @param parent the container to list, such as a schema
   * @param maxResults the most assets the caller wants on this page; must be positive, but a
   *     catalog without native paging may return more
   * @param pageToken null for the first page, otherwise a token from the same listing
   * @param auth the caller's credentials; the catalog returns only the assets they may see
   */
  default AssetPage listChildren(
      Asset parent, int maxResults, String pageToken, AuthContext auth) {
    throw new UnsupportedAssetTypeException(
        "the " + name() + " catalog cannot list the contents of a " + parent.type());
  }

  /** Mints credentials scoped to the asset location, as {@code auth}. */
  List<StorageCredentials> getStorageCredentials(CredentialRequest request, AuthContext auth);

  /**
   * Authenticates the caller and, when {@code privilege} is set, checks that they hold it. The
   * catalog owns authorization: OpenSharing only names the sharing privilege an action needs, and
   * it is up to each connector to support the sharing privileges, mapping them to its own
   * catalog's privileges as needed.
   *
   * @param auth who is calling. When {@code user().userAuthToken()} is set, as on a user's own
   *     request, the catalog authenticates that token; otherwise {@code user().userId()} names the
   *     user, and {@code serverId}, when set, is the OpenSharing server acting on their behalf
   * @param privilege the privilege the action needs, or null to authenticate only
   * @return the caller as the catalog knows them. {@code userId} must be set: OpenSharing keeps it
   *     as the caller's durable identity, such as the owner of the shares they create, and uses it
   *     on later calls made for them. {@code userName} is optional and only used for display
   */
  UserContext authorize(AuthContext auth, Privilege privilege);
}
