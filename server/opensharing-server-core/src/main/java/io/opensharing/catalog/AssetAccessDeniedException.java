package io.opensharing.catalog;

/**
 * The catalog knows the asset but will not let this caller share it. Distinct from {@link
 * CatalogAuthenticationException}, which is the sharing server's own credentials being rejected.
 */
public class AssetAccessDeniedException extends CatalogException {

  public AssetAccessDeniedException(AssetLookup lookup, CatalogCaller caller) {
    super(
        "'"
            + caller.name()
            + "' may not share "
            + lookup.type()
            + " '"
            + lookup.identifier()
            + "'");
  }
}
