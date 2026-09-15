package io.opensharing.principal;

/**
 * The principal behind a provider-admin request, resolved from the bearer token it presented.
 * Never persisted: this server keeps no principal table of its own.
 */
public record Caller(String principalId, String name, String bearerToken) {

  public static final String REQUEST_ATTRIBUTE = "io.opensharing.caller";
}
