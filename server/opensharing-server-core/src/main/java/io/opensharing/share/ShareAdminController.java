package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.http.ListResponse;
import io.opensharing.auth.UserContext;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider HTTP API for share CRUD. Callers are authenticated by {@code
 * ProviderAuthenticationFilter}; any caller may read shares, only the owner may change them.
 */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareStore shares;

  public ShareAdminController(ShareStore shares) {
    this.shares = shares;
  }

  /** {@code POST /shares}: creates a share owned by the caller. Needs CREATE_SHARE. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ShareResponse create(UserContext user, @Valid @RequestBody CreateShareRequest request) {
    return ShareResponse.from(
        shares.create(
            user,
            ObjectNames.validateShareName(request.name()),
            request.displayName(),
            request.comment(),
            request.properties()));
  }

  /** {@code GET /shares}: lists every share by name, unpaged. */
  @GetMapping
  public ListResponse<ShareResponse> list(UserContext user) {
    // TODO: page with maxResults and pageToken.
    return ListResponse.of(
        shares.list(Pageable.unpaged()).stream().map(ShareResponse::from).toList());
  }

  /** {@code GET /shares/{share}}: gets a share by name in any case. */
  @GetMapping("/{share}")
  public ShareResponse get(UserContext user, @PathVariable String share) {
    return ShareResponse.from(shares.require(share));
  }

  /** {@code PATCH /shares/{share}}: updates the fields set in the body. Owner only. */
  @PatchMapping("/{share}")
  public ShareResponse update(
      UserContext user,
      @PathVariable String share,
      @Valid @RequestBody UpdateShareRequest request) {
    return ShareResponse.from(
        shares.update(
            user, share, request.displayName(), request.comment(), request.properties()));
  }

  /** {@code DELETE /shares/{share}}: deletes the share. Owner only. */
  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String share) {
    shares.delete(share, user);
    return ResponseEntity.noContent().build();
  }
}
