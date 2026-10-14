package io.opensharing.asset.table;

/**
 * Optional body for Generate Temporary Table Credential.
 *
 * @param location the table root or one of its auxiliary locations; the table root if null
 */
public record TemporaryTableCredentialsRequest(String location) {}
