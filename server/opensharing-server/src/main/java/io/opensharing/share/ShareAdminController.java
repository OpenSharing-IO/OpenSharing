package io.opensharing.share;

import io.opensharing.auth.UserContext;
import io.opensharing.http.ListResponse;
import io.opensharing.runtime.OpenSharing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider HTTP API for share CRUD and recipient grants. Callers are authenticated by {@code
 * ProviderAuthenticationFilter}; any caller may read shares, only the owner may change them.
 */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareService shares;

  public ShareAdminController(OpenSharing openSharing) {
    this.shares = openSharing.shares();
  }

  /** {@code POST /shares}: creates a share owned by the caller. Needs CREATE_SHARE. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ShareResponse create(UserContext user, @RequestBody CreateShareRequest request) {
    return shares.create(user, request);
  }

  /** {@code GET /shares}: lists every share by name, unpaged. */
  @GetMapping
  public ListResponse<ShareResponse> list(UserContext user) {
    return shares.list();
  }

  /**
   * {@code GET /shares/{share}}: gets a share by name in any case. With {@code
   * include_shared_data=true}, also lists the objects in the share.
   */
  @GetMapping("/{share}")
  public ShareResponse get(
      UserContext user,
      @PathVariable String share,
      @RequestParam(name = "include_shared_data", defaultValue = "false")
          boolean includeSharedData) {
    return shares.get(share, includeSharedData);
  }

  /**
   * {@code PATCH /shares/{share}}: applies the object adds and removes in order, then updates the
   * fields set in the body. Owner only.
   */
  @PatchMapping("/{share}")
  public ShareResponse update(
      UserContext user, @PathVariable String share, @RequestBody UpdateShareRequest request) {
    return shares.update(user, share, request);
  }

  /** {@code DELETE /shares/{share}}: deletes the share. Owner only. */
  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String share) {
    shares.delete(user, share);
    return ResponseEntity.noContent().build();
  }

  /** {@code GET /shares/{share}/permissions}: lists who holds which privilege on the share. */
  @GetMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> listPermissions(
      UserContext user, @PathVariable String share) {
    return shares.listPermissions(share);
  }

  /**
   * {@code PATCH /shares/{share}/permissions}: grants and revokes privileges in order, then returns
   * the share's permissions. Owner only.
   */
  @PatchMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> updatePermissions(
      UserContext user,
      @PathVariable String share,
      @RequestBody UpdateSharePermissionsRequest request) {
    return shares.updatePermissions(user, share, request);
  }
}
