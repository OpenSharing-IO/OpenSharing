package io.opensharing.exception;

import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;

/** The catalog has the asset but this user may not share it. */
public class AssetAccessDeniedException extends CatalogException {

  public AssetAccessDeniedException(AssetLookup lookup, UserContext user) {
    super(
        "'"
            + user.name()
            + "' may not share "
            + lookup.type()
            + " '"
            + lookup.identifier()
            + "'");
  }
}
