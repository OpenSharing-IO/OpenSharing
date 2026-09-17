package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.http.ListResponse;
import io.opensharing.auth.UserContext;
import io.opensharing.http.ListResponse;
import io.opensharing.runtime.OpenSharing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
 * Provider HTTP API for share CRUD. Callers are authenticated by {@code
 * ProviderAuthenticationFilter}; any caller may read shares, only the owner may change them.
 */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareStore shares;
  private final SharedDataObjectStore objects;

  public ShareAdminController(ShareStore shares, SharedDataObjectStore objects) {
    this.shares = shares;
    this.objects = objects;
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

  /** {@code GET /shares/{share}}: gets a share by name in any case. */
  @GetMapping("/{share}")
  public ShareResponse get(
      UserContext user,
      @PathVariable String share,
      @RequestParam(name = "include_shared_data", defaultValue = "false")
          boolean includeSharedData) {
    ShareEntity entity = shares.require(share);
    return includeSharedData
        ? ShareResponse.from(entity, objects.list(entity))
        : ShareResponse.from(entity);
  }

  /** {@code PATCH /shares/{share}}: updates the fields set in the body. Owner only. */
  @PatchMapping("/{share}")
  @Transactional
  public ShareResponse update(
      UserContext user,
      @PathVariable String share,
      @Valid @RequestBody UpdateShareRequest request) {
    ShareEntity entity = shares.requireOwned(share, user);
    for (UpdateShareRequest.Update update : request.updates()) {
      switch (update.action()) {
        case ADD ->
            objects.add(
                entity,
                user,
                update.dataObject().name(),
                update.dataObject().type(),
                update.dataObject().sharedAs());
        case REMOVE ->
            objects.remove(
                entity,
                update.dataObject().name(),
                update.dataObject().type(),
                update.dataObject().sharedAs());
      }
    }
    return ShareResponse.from(
        shares.update(
            user, share, request.displayName(), request.comment(), request.properties()));
  }

  /** {@code DELETE /shares/{share}}: deletes the share. Owner only. */
  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String share) {
    shares.delete(user, share);
    return ResponseEntity.noContent().build();
  }
}
