package io.opensharing.catalog;

import java.time.Duration;

/**
 * Request for credentials scoped to one asset location.
 *
 * @param assetType the type of the asset the credentials are for
 * @param assetFullName the asset's full name in the catalog, such as {@code main.sales.orders}
 * @param catalogAssetId the catalog's own id for the asset, as returned in {@link ResolvedAsset};
 *     optional. When set, the catalog refuses the request with {@link
 *     io.opensharing.exception.AssetNotFoundException} unless the asset still has this id, so
 *     credentials are not vended for another asset that took a dropped one's name
 * @param storageLocation the location to scope the credentials to: the asset's storage location
 *     or one of its auxiliary locations. Null means the asset's storage location
 * @param operation the access the credentials must allow
 * @param ttl how long the credentials should last; null means the catalog's default
 */
public record CredentialRequest(
    AssetType assetType,
    String assetFullName,
    String catalogAssetId,
    String storageLocation,
    StorageOperation operation,
    Duration ttl) {}
