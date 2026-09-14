package io.opensharing.recipient;

/** How a recipient proves who it is. */
public enum AuthType {
  /** Bearer tokens this server issues and the recipient collects from an activation URL. */
  TOKEN,
  /** Tokens from an external identity provider. Not implemented yet. */
  OIDC
}
