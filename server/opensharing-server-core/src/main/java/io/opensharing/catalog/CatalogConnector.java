package io.opensharing.catalog;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.util.List;

/**
 * Looks up assets and vends storage credentials as an {@link AuthContext}. Implementations must be
 * thread-safe.
 */
public interface CatalogConnector {

  /** Identifier used to select this connector in configuration. */
  String name();

  /**
   * Resolves an asset as {@code auth}. Also the existence check: missing assets throw {@link
   * AssetNotFoundException}.
   */
  ResolvedAsset resolveAsset(AssetLookup lookup, AuthContext auth);

  /**
   * Lists children of a container such as a schema. Optional: catalogs that cannot enumerate throw
   * {@link UnsupportedAssetTypeException}.
   */
  default List<ResolvedAsset> listChildren(AssetLookup parent, AuthContext auth) {
    throw new UnsupportedAssetTypeException(
        "the " + name() + " catalog cannot list the contents of a " + parent.type());
  }

  /** Mints credentials scoped to the asset location, as {@code auth}. */
  List<StorageCredentials> getStorageCredentials(CredentialRequest request, AuthContext auth);

  /**
   * Maps {@code auth} to a {@link UserContext}, optionally checking {@code privilege}. Optional:
   * catalogs with no provider identity throw {@link UnsupportedOperationException}.
   */
  default UserContext authorize(AuthContext auth, String privilege) {
    throw new UnsupportedOperationException(
        "the " + name() + " catalog has no notion of provider identity to authorize");
  }
}
