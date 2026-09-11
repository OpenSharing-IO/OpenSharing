package io.opensharing.catalog;

/** Who a catalog request is for, and how to authenticate as them. */
public record CatalogCaller(String name, Credential credential) {

  public CatalogCaller {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a catalog request is always made for a named principal");
    }
    if (credential == null) {
      throw new IllegalArgumentException(
          "a catalog request for '" + name + "' needs a credential, even if it is None");
    }
  }

  /** Admin request using the caller's own bearer token. */
  public static CatalogCaller withBearerToken(String name, String bearerToken) {
    return new CatalogCaller(name, new Credential.BearerToken(bearerToken));
  }

  /** Recipient read: act as {@code catalogUserId} without their token. */
  public static CatalogCaller onBehalfOf(String name, String catalogUserId) {
    return new CatalogCaller(name, new Credential.OnBehalfOf(catalogUserId));
  }

  /** For connectors that do not read a credential. */
  public static CatalogCaller unauthenticated(String name) {
    return new CatalogCaller(name, new Credential.None());
  }

  /** Omits the secret so logs and test failures cannot print a live token. */
  @Override
  public String toString() {
    return "CatalogCaller[name="
        + name
        + ", credential="
        + credential.getClass().getSimpleName()
        + "]";
  }

  public sealed interface Credential {

    record BearerToken(String token) implements Credential {
      public BearerToken {
        if (token == null || token.isBlank()) {
          throw new IllegalArgumentException("a bearer-token credential needs a token");
        }
      }
    }

    record OnBehalfOf(String catalogUserId) implements Credential {
      public OnBehalfOf {
        if (catalogUserId == null || catalogUserId.isBlank()) {
          throw new IllegalArgumentException("an on-behalf-of credential needs a catalog user id");
        }
      }
    }

    record None() implements Credential {}
  }
}
