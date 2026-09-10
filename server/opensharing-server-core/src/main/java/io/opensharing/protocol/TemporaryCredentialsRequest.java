package io.opensharing.protocol;

/**
 * Optional body of a {@code temporary-table-credentials} request.
 *
 * @param location for tables with auxiliary locations, which one to scope credentials to; the
 *     table's root location is used when omitted
 */
public record TemporaryCredentialsRequest(String location) {}
