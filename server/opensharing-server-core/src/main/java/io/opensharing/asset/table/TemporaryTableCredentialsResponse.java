package io.opensharing.asset.table;

/**
 * Generate Temporary Table Credential response:
 *
 * <pre>{@code
 * {"credentials": {"location": "<storage location>", ..., "expirationTime": <epoch milliseconds>}}
 * }</pre>
 */
public record TemporaryTableCredentialsResponse(TemporaryCredentials credentials) {}
