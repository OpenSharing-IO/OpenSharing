package io.opensharing.exception;

import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;

/** The catalog has the asset but this user may not share it. */
public class AssetAccessDeniedException extends CatalogException {

  public AssetAccessDeniedException(AssetLookup lookup, UserContext user) {
    super(
        "'"
            + display(user)
            + "' may not share "
            + lookup.type()
            + " '"
            + lookup.identifier()
            + "'");
  }

  private static String display(UserContext user) {
    if (user == null) {
      return "unknown user";
    }
    if (user.userName() != null) {
      return user.userName();
    }
    if (user.userId() != null) {
      return user.userId();
    }
    return "unknown user";
  }
}
