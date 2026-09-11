package io.opensharing.catalog;

/** Who a catalog request is for, and how to authenticate as them. */
public record CatalogCaller(String name, Auth auth) {

  public CatalogCaller {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a catalog request is always made for a named principal");
    }
    if (auth == null) {
      throw new IllegalArgumentException("a catalog request for '" + name + "' needs auth");
    }
  }

  /** Admin request using the caller's own bearer token. */
  public static CatalogCaller withBearerToken(String name, String bearerToken) {
    return new CatalogCaller(name, new Auth.BearerToken(bearerToken));
  }

  /** Recipient read: act as {@code catalogUserId} without their token. */
  public static CatalogCaller onBehalfOf(String name, String catalogUserId) {
    return new CatalogCaller(name, new Auth.OnBehalfOf(catalogUserId));
  }

  /** Omits the secret so logs and test failures cannot print a live token. */
  @Override
  public String toString() {
    return "CatalogCaller[name=" + name + ", auth=" + auth.getClass().getSimpleName() + "]";
  }

  public sealed interface Auth {

    record BearerToken(String token) implements Auth {
      public BearerToken {
        if (token == null || token.isBlank()) {
          throw new IllegalArgumentException("a bearer token is required");
        }
      }
    }

    record OnBehalfOf(String catalogUserId) implements Auth {
      public OnBehalfOf {
        if (catalogUserId == null || catalogUserId.isBlank()) {
          throw new IllegalArgumentException("an on-behalf-of request needs a catalog user id");
        }
      }
    }
  }
}
