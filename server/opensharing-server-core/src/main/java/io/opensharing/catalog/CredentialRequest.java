package io.opensharing.catalog;

import java.time.Duration;

/** Request for storage credentials scoped to a single asset location. */
public record CredentialRequest(
    AssetType assetType,
    String identifier,
    String catalogAssetId,
    String storageLocation,
    StorageOperation operation,
    Duration ttl) {}
