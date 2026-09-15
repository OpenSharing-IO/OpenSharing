package io.opensharing.http;

/**
 * Content types the spec requires on successful protocol responses. Both are spelled the way a
 * parsed media type renders, so the published API lists each of them once.
 */
public final class ProtocolMediaType {

  public static final String JSON_UTF8 = "application/json;charset=utf-8";

  private ProtocolMediaType() {}
}
