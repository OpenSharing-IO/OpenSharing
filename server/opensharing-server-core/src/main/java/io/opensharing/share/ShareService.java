package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.auth.AuthContext;
import io.opensharing.auth.Privilege;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import java.util.Map;

/**
 * Provider share CRUD for a caller the host has authenticated. Creating a share needs CREATE_SHARE
 * from the catalog; any caller may read shares, only the owner may change them.
 */
public class ShareService {

  private final CatalogConnector catalog;
  private final ShareStore shares;

  public ShareService(CatalogConnector catalog, ShareStore shares) {
    this.catalog = catalog;
    this.shares = shares;
  }

  /** Creates a share owned by the caller, who needs CREATE_SHARE from the catalog. */
  public ShareResponse create(UserContext user, CreateShareRequest request) {
    // The host has already authenticated the caller. A host that also checks CREATE_SHARE at its
    // endpoint can make this a no-op.
    catalog.authorize(AuthContext.of(user), Privilege.CREATE_SHARE);
    return ShareResponse.from(
        shares.create(
            user,
            ObjectNames.validateShareName(request.name()),
            request.displayName(),
            request.comment(),
            validateProperties(request.properties())));
  }

  /** Lists every share by name, unpaged. */
  public ListResponse<ShareResponse> list() {
    // TODO: page with maxResults and pageToken.
    // TODO: check USE_SHARE through catalog.authorize.
    return ListResponse.of(shares.list().stream().map(ShareResponse::from).toList());
  }

  /** Gets a share by name in any case. */
  public ShareResponse get(String share) {
    return ShareResponse.from(shares.getOrThrow(share));
  }

  /** Updates the fields set in {@code request}. Owner only. */
  public ShareResponse update(UserContext user, String share, UpdateShareRequest request) {
    return ShareResponse.from(
        shares.update(
            user,
            share,
            request.displayName(),
            request.comment(),
            validateProperties(request.properties())));
  }

  /** Deletes the share. Owner only. */
  public void delete(UserContext user, String share) {
    shares.delete(share, user);
  }

  private static Map<String, String> validateProperties(Map<String, String> properties) {
    if (properties != null) {
      for (Map.Entry<String, String> property : properties.entrySet()) {
        if (property.getValue() == null) {
          throw ApiException.invalidParameter(
              "property '" + property.getKey() + "' must not be null");
        }
      }
    }
    return properties;
  }
}
