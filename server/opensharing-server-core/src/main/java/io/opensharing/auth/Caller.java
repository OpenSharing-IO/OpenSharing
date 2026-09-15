package io.opensharing.auth;

/** The catalog identity on a provider request. Not stored in this server. */
public record Caller(String id, String name, String bearerToken) {

  public static final String REQUEST_ATTRIBUTE = "io.opensharing.caller";
}
