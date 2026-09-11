package io.opensharing.catalog.exception;

import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.CatalogCaller;

/** The catalog has the asset but this caller may not share it. */
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
