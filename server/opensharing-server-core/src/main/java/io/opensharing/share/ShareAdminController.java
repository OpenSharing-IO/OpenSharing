package io.opensharing.share;

import io.opensharing.ObjectNames;
import io.opensharing.asset.SharedDataObjectStore;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Provider HTTP API for share CRUD. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareStore shares;
  private final SharedDataObjectStore objects;

  public ShareAdminController(ShareStore shares, SharedDataObjectStore objects) {
    this.shares = shares;
    this.objects = objects;
  }

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

  @GetMapping
  public ListResponse<ShareResponse> list(UserContext user) {
    return ListResponse.of(shares.list(Pageable.unpaged()).stream().map(ShareResponse::from).toList());
  }

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

  @PatchMapping("/{share}")
  public ShareResponse update(
      UserContext user, @PathVariable String share, @Valid @RequestBody UpdateShareRequest request) {
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

  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(UserContext user, @PathVariable String share) {
    shares.delete(share, user);
    return ResponseEntity.noContent().build();
  }
}
