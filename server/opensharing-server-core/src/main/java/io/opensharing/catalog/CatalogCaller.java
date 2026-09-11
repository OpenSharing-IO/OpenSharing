package io.opensharing.catalog;

/**
 * Which provider-side principal a catalog request is made for, and what to authenticate to the
 * catalog as them with. Always both: every question this server asks a catalog is about an asset
 * some provider shares or is about to, so there is always somebody to name, and a catalog that
 * decides authorization has to be told who is asking in a form it can verify.
 *
 * <p>Two shapes, for the two moments this is ever built:
 *
 * <ul>
 *   <li>Adding an object to a share names the admin making the request, with the bearer token the
 *       request itself just arrived carrying — {@link Credential.BearerToken}.
 *   <li>Serving a recipient names the owner of the share being read through — a recipient is
 *       nobody the catalog knows, and the owner is whose access they read by — but the owner is
 *       not the one asking and never will be for this request, so there is no token of theirs to
 *       present. {@link Credential.OnBehalfOf} carries their catalog user id instead, for a
 *       connector that can ask the catalog as them without their token — see {@code
 *       UnityCatalogConnector}, which authenticates itself to the catalog and names the id.
 * </ul>
 *
 * <p>{@link Credential.None} is for a catalog that reads no credential at all ({@code
 * LocalCatalogConnector} only ever consults {@link #name()}), so a caller can still be built for
 * one without inventing a credential that means nothing to it.
 */
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

  /** The live requester's own bearer token — an admin call, made as whoever is making it. */
  public static CatalogCaller withBearerToken(String name, String bearerToken) {
    return new CatalogCaller(name, new Credential.BearerToken(bearerToken));
  }

  /**
   * Ask the catalog as {@code catalogUserId} without their token, authenticating as the connector
   * itself instead — a recipient's read, made long after the owner it reads by is gone.
   */
  public static CatalogCaller onBehalfOf(String name, String catalogUserId) {
    return new CatalogCaller(name, new Credential.OnBehalfOf(catalogUserId));
  }

  /** No credential at all, for a catalog connector that reads none. */
  public static CatalogCaller unauthenticated(String name) {
    return new CatalogCaller(name, new Credential.None());
  }

  /**
   * Never printed whole. A record prints every component by default, and this one now travels
   * through three connector methods, so a log line or a test failure that formats the whole caller
   * — neither of which is written with a secret in mind — would print a live credential.
   */
  @Override
  public String toString() {
    return "CatalogCaller[name=" + name + ", credential=" + credential.getClass().getSimpleName() + "]";
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
