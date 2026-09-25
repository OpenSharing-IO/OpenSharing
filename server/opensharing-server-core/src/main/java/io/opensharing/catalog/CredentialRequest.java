package io.opensharing.catalog;

import java.time.Duration;

/** Request for credentials scoped to one asset location. */
public record CredentialRequest(
    AssetType assetType,
    String identifier,
    String catalogAssetId,
    String storageLocation,
    StorageOperation operation,
    Duration ttl) {}
