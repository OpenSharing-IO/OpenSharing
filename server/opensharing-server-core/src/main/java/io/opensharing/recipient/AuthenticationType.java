package io.opensharing.recipient;

/** How a recipient authenticates to this server. Only {@link #TOKEN} is implemented. */
public enum AuthenticationType {
  TOKEN,
  OIDC
}
