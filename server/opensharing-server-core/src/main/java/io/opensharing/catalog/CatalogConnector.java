package io.opensharing.catalog;

import java.util.List;

/**
 * The seam between the sharing server and the system of record for assets. The server never talks
 * to cloud storage itself: it asks the catalog where an asset lives and asks it to mint scoped,
 * TTL-bounded credentials the recipient uses to read bytes directly.
 *
 * <p>Two operations carry the whole integration, and each has a counterpart in every catalog worth
 * plugging in: Unity Catalog answers them with {@code GET /tables/{full_name}} and {@code POST
 * /temporary-table-credentials}, an Iceberg REST catalog such as Polaris with {@code loadTable} and
 * {@code loadCredentials}. Nothing else is required of an implementation, so that the cost of adding
 * one stays close to those two calls. {@link #listChildren} is offered on top for a catalog that can
 * enumerate, and is what a provider needs to share a whole schema rather than a list of tables.
 *
 * <p>Implementations authenticate to the catalog as the {@link CatalogCaller} each request names,
 * and are expected to be thread-safe.
 */
public interface CatalogConnector {

  /** Identifier used to select this connector in configuration. */
  String name();

  /**
   * Resolves an asset to its physical location, as a given caller, so that a catalog owning
   * authorization can refuse. Called when an asset is added to a share and again when credentials are
   * vended, so a relocation is picked up rather than served from a stale snapshot.
   *
   * <p>What comes back is described by {@link ResolvedAsset}, including which of its fields are kept
   * against the shared object and which are only as current as this call. An implementation states
   * what the catalog says and nothing more: a field the catalog does not report is left null or empty
   * rather than guessed at, because a guess here is indistinguishable from an answer downstream.
   *
   * <p>Either way the caller is a provider-side principal, and what to ask of them is whether they may
   * share the asset — not whether they own it. Databricks, whose model this follows, lets anyone with
   * {@code SELECT} on a table or view add it to a share, given {@code USE CATALOG} and {@code USE
   * SCHEMA} on the parents that hold it; a connector demanding ownership would refuse a provider
   * Databricks allows. The other half of that rule, that the caller must also own the share, is this
   * server's own to enforce and is never asked of a catalog.
   *
   * <p>Adding names the admin making the request. Serving names the owner of the share the recipient
   * reads through, which is deliberate rather than incidental: a recipient reads by virtue of that
   * provider's access, so asking as them each time is what makes a provider who loses access take
   * their recipients' access with them.
   *
   * <p>A caller always carries both a name and a credential to act as them with, so an implementation
   * never has to decide what to do without one. A connector authenticates to the catalog as the caller
   * and lets it answer; there is no service identity to fall back to, because a read that succeeded on
   * the server's own access rather than the owner's would outlive the owner's.
   *
   * <p>This doubles as the existence check. There is no separate one, because every caller that
   * wants to know whether an asset exists also wants the metadata that proves it.
   *
   * @param caller the principal the request is made for
   * @throws AssetNotFoundException if the catalog has no such asset
   * @throws AssetAccessDeniedException if the caller may not do this with the asset
   * @throws CatalogAuthenticationException if the catalog rejects the connector's own credentials
   * @throws CatalogException if the catalog cannot be reached or refuses the request
   */
  ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller);

  /**
   * Lists the assets a container holds, which is what makes sharing a whole schema possible: the
   * grant names the schema, and its contents are whatever the catalog says they are at the moment
   * someone asks.
   *
   * <p>Optional, and the only operation that is. A catalog that cannot enumerate is still a perfectly
   * good catalog for sharing individual assets, so this refuses rather than forcing every
   * implementation to answer; adding a schema to a share is then rejected up front instead of failing
   * later, when a recipient tries to list it.
   *
   * <p>Full {@link ResolvedAsset}s are returned rather than bare identifiers because the catalogs
   * worth plugging in already carry the metadata in their list response — Unity Catalog's
   * {@code GET /tables?schema_name=} answers with each table's columns and location — and asking for
   * identifiers alone would throw that away and buy it back one request per child.
   *
   * @param parent the container to enumerate, such as a {@link AssetType#SCHEMA}
   * @throws UnsupportedAssetTypeException if this catalog cannot enumerate that kind of container
   * @throws AssetNotFoundException if the container itself does not exist
   */
  default List<ResolvedAsset> listChildren(AssetLookup parent, CatalogCaller caller) {
    throw new UnsupportedAssetTypeException(
        "the " + name() + " catalog cannot list the contents of a " + parent.type());
  }

  /**
   * Mints credentials scoped to the asset's storage location, as a given caller.
   *
   * <p>Returns one entry per storage prefix the asset spans, mirroring the Iceberg REST
   * {@code LoadCredentialsResponse}; a catalog that scopes to a single prefix returns a single
   * element. The caller picks the entry covering the location it is about to read.
   *
   * <p>The caller is the owner of the share being read through, as it is for {@link #resolveAsset}
   * and for the same reason: this is the moment access to the bytes is handed out, so it is the
   * moment a catalog most wants to decide, and a grant minted on any other identity would be one the
   * owner losing access does not take away.
   *
   * <p>An empty answer means the asset's storage needs no credential, and is only taken that way for
   * a local path — the one storage the server reaches on its own account. From a cloud location an
   * empty answer is a catalog with nothing configured for the bucket, and is reported as a failure
   * rather than quietly turned into a read that cannot work.
   *
   * @throws CatalogException if the catalog refuses or cannot mint credentials
   */
  List<StorageCredentials> getStorageCredentials(CredentialRequest request, CatalogCaller caller);

  /**
   * Resolves a provider-admin bearer token to the principal behind it, and — when {@code privilege}
   * is given — whether that principal may do it. This is how a provider-admin request is
   * authenticated at all in a deployment with no {@code opensharing.admin.principals} of its own: the
   * catalog is asked whose token this is instead, so this server never has to store, or even see,
   * anything it could replay as them later.
   *
   * <p>Unlike {@link #resolveAsset} and {@link #getStorageCredentials}, which act as an already-known
   * {@link CatalogCaller}, this is what produces one in the first place — from a bare token, with no
   * asset or existing identity in view yet. That is also why it takes an unadorned bearer token
   * rather than a {@link CatalogCaller}: there is nothing to wrap it in until this answers.
   *
   * <p>Optional, like {@link #listChildren}, and for a similar reason: a catalog with no notion of
   * provider-admin principals distinct from asset access — the local file catalog, most of all — is
   * still a perfectly good connector for serving assets, and a deployment that never delegates
   * identity to it configures {@code opensharing.admin.principals} instead and never calls this.
   *
   * @param privilege the one platform-level permission being asked about, or {@code null} to resolve
   *     identity only, with no permission check — this server enforces everything else itself, per
   *     share or recipient owned rather than as a privilege a catalog has an opinion on
   * @throws CatalogAuthorizationException if the catalog does not recognize the token at all, or
   *     recognizes it but says no to {@code privilege} — either way, exactly what an unauthenticated
   *     request looks like to whoever resolves this into a rejection
   * @throws UnsupportedOperationException if this catalog has no notion of provider-admin identity
   *     distinct from the assets it serves
   */
  default CatalogPrincipal authorize(String bearerToken, String privilege) {
    throw new UnsupportedOperationException(
        "the " + name() + " catalog has no notion of provider-admin identity to authorize");
  }
}
