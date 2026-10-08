package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ListResponse;

/**
 * Provider share CRUD. The host authenticates the caller and checks CREATE_SHARE before {@link
 * #create}; any caller may read shares, only the owner may change them.
 */
public class ShareService {

  private final ShareStore shares;

  public ShareService(ShareStore shares) {
    this.shares = shares;
  }

  /** Creates a share owned by {@code user}. */
  public ShareResponse create(UserContext user, CreateShareRequest request) {
    return ShareResponse.from(
        shares.create(
            user,
            ObjectNames.validateShareName(request.name()),
            request.displayName(),
            request.comment(),
            request.properties()));
  }

  /** Lists every share by name, unpaged. */
  public ListResponse<ShareResponse> list() {
    // TODO: page with maxResults and pageToken.
    return ListResponse.of(shares.list().stream().map(ShareResponse::from).toList());
  }

  /** Gets a share by name in any case. */
  public ShareResponse get(String share) {
    return ShareResponse.from(shares.require(share));
  }

  /** Updates the fields set in {@code request}. Owner only. */
  public ShareResponse update(UserContext user, String share, UpdateShareRequest request) {
    return ShareResponse.from(
        shares.update(
            user, share, request.displayName(), request.comment(), request.properties()));
  }

  /** Deletes the share. Owner only. */
  public void delete(UserContext user, String share) {
    shares.delete(share, user);
  }
}
