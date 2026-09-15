package io.opensharing.share;

import io.opensharing.http.ListResponse;
import io.opensharing.principal.Caller;
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

/** Provider-admin API for shares. Shared objects and recipient grants come later. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareStore shares;

  public ShareAdminController(ShareStore shares) {
    this.shares = shares;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ShareResponse create(Caller caller, @Valid @RequestBody CreateShareRequest request) {
    return ShareResponse.from(
        shares.create(
            caller,
            request.name(),
            request.displayName(),
            request.comment(),
            request.properties()));
  }

  @GetMapping
  public ListResponse<ShareResponse> list(Caller caller) {
    return ListResponse.of(shares.list(Pageable.unpaged()).stream().map(ShareResponse::from).toList());
  }

  @GetMapping("/{share}")
  public ShareResponse get(Caller caller, @PathVariable String share) {
    return ShareResponse.from(shares.require(share));
  }

  @PatchMapping("/{share}")
  public ShareResponse update(
      Caller caller, @PathVariable String share, @Valid @RequestBody UpdateShareRequest request) {
    ShareEntity entity = shares.requireOwned(share, caller);
    return ShareResponse.from(
        shares.update(caller, entity, request.displayName(), request.comment(), request.properties()));
  }

  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(Caller caller, @PathVariable String share) {
    shares.delete(share, caller);
    return ResponseEntity.noContent().build();
  }
}
