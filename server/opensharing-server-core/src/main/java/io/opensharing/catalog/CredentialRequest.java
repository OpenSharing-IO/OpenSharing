package io.opensharing.catalog;

import java.time.Duration;

/**
 * Request for credentials scoped to one asset location.
 *
 * @param assetType the type of the asset the credentials are for
 * @param assetFullName the asset's full name in the catalog, such as {@code main.sales.orders}
 * @param catalogAssetId the catalog's own id for the asset, as returned in {@link ResolvedAsset};
 *     optional
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
