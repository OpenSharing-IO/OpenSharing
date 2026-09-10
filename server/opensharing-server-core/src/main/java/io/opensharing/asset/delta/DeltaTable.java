package io.opensharing.asset.delta;

import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;

/**
 * A shared Delta table as the server has just seen it: where the catalog says it lives, the access
 * the catalog minted for it, and what its log says. The credentials travel with the snapshot because
 * signing a file url needs the same grant that listing the file did.
 */
public record DeltaTable(
    ResolvedAsset resolved, StorageCredentials credentials, DeltaSnapshot snapshot) {}
