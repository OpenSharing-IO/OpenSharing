package io.opensharing.catalog;

import java.util.List;

/**
 * Looks up assets and vends storage credentials as a named {@link CatalogCaller}. Implementations
 * must be thread-safe.
 */
public interface CatalogConnector {

  /** Identifier used to select this connector in configuration. */
  String name();

  /**
   * Resolves an asset as {@code caller}. Also the existence check: missing assets throw {@link
   * AssetNotFoundException}.
   */
  ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller);

  /**
   * Lists children of a container such as a schema. Optional: catalogs that cannot enumerate throw
   * {@link UnsupportedAssetTypeException}.
   */
  default List<ResolvedAsset> listChildren(AssetLookup parent, CatalogCaller caller) {
    throw new UnsupportedAssetTypeException(
        "the " + name() + " catalog cannot list the contents of a " + parent.type());
  }

  /** Mints credentials scoped to the asset location, as {@code caller}. */
  List<StorageCredentials> getStorageCredentials(CredentialRequest request, CatalogCaller caller);

  /**
   * Maps a bearer token to a catalog principal, optionally checking {@code privilege}. Optional:
   * catalogs with no provider-admin identity throw {@link UnsupportedOperationException}.
   */
  default CatalogPrincipal authorize(String bearerToken, String privilege) {
    throw new UnsupportedOperationException(
        "the " + name() + " catalog has no notion of provider-admin identity to authorize");
  }
}
