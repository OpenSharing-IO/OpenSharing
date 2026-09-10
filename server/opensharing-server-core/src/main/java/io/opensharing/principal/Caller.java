package io.opensharing.principal;

/**
 * The principal behind a provider-admin request, resolved from the bearer token it presented — by
 * the catalog itself ({@code unity}: a fresh call to Unity Catalog's own authorize API, every
 * request) or from static configuration ({@code local}). Never persisted: this server keeps no
 * principal table of its own, so {@code principalId} is whatever identity its source of truth
 * assigned, held only for the life of this one request.
 *
 * <p>The token travels with the caller because the server queries the catalog as them for some
 * requests (adding an object to a share): a live token, presented once, for the one call it
 * concerns — never stored for later.
 */
public record Caller(String principalId, String name, String bearerToken) {

  public static final String REQUEST_ATTRIBUTE = "io.opensharing.caller";

  public static Caller of(String principalId, String name, String bearerToken) {
    return new Caller(principalId, name, bearerToken);
  }
}
