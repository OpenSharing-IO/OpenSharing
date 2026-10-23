package io.opensharing.asset;

import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.exception.AssetNotFoundException;
import java.util.Optional;

/** Resolves the objects a share includes, as long as the catalog still has what was shared. */
public final class SharedAssetResolver {

  private SharedAssetResolver() {}

  /**
   * The catalog asset behind {@code sharedObject}, resolved as {@code auth}, or empty once the
   * catalog has dropped it or replaced it with another asset of the same name. An object shared
   * without a catalog id is matched by name alone.
   */
  public static Optional<ResolvedAsset> resolve(
      CatalogConnector catalog, SharedDataObjectEntity sharedObject, AuthContext auth) {
    Asset catalogAsset = new Asset(sharedObject.getType(), sharedObject.getFullName());
    ResolvedAsset resolvedAsset;
    try {
      resolvedAsset = catalog.resolveAsset(catalogAsset, auth);
    } catch (AssetNotFoundException dropped) {
      return Optional.empty();
    }
    String sharedAssetId = sharedObject.getSourceAssetId();
    return sharedAssetId == null || sharedAssetId.equals(resolvedAsset.catalogAssetId())
        ? Optional.of(resolvedAsset)
        : Optional.empty();
  }
}
