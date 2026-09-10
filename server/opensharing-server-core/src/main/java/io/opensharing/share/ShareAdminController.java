package io.opensharing.share;

import io.opensharing.catalog.CatalogCaller;
import io.opensharing.http.ApiException;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.principal.Caller;
import io.opensharing.recipient.RecipientStore;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectResponse;
import io.opensharing.asset.SharedDataObjectService;
import io.opensharing.asset.SharedDataObjectStore;
import jakarta.validation.Valid;
import java.util.List;
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

/** Provider-admin API for shares, their contents, and the permissions recipients hold on them. */
@RestController
@RequestMapping("${opensharing.provider.base-path}/shares")
public class ShareAdminController {

  private final ShareStore shares;
  private final RecipientStore recipients;
  private final SharedDataObjectStore objects;
  private final SharedDataObjectService objectService;
  private final Listings listings;

  public ShareAdminController(
      ShareStore shares,
      RecipientStore recipients,
      SharedDataObjectStore objects,
      SharedDataObjectService objectService,
      Listings listings) {
    this.shares = shares;
    this.recipients = recipients;
    this.objects = objects;
    this.objectService = objectService;
    this.listings = listings;
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
  public ListResponse<ShareResponse> list(
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    return listings.page(maxResults, pageToken, shares::list, ShareResponse::from);
  }

  @GetMapping("/{share}")
  public ShareResponse get(@PathVariable String share) {
    return withObjects(shares.require(share));
  }

  /**
   * Applies metadata changes and content changes in a single request, as the spec's PATCH does. One
   * transaction spans all of them, so a rejected object leaves the share as it was rather than half
   * updated.
   */
  @Transactional
  @PatchMapping("/{share}")
  public ShareResponse update(
      Caller caller, @PathVariable String share, @Valid @RequestBody UpdateShareRequest request) {
    ShareEntity entity = shares.requireOwned(share, caller);
    for (UpdateShareRequest.Update update : request.updates()) {
      apply(caller, entity, update);
    }
    return withObjects(
        shares.update(
            caller, entity, request.displayName(), request.comment(), request.properties()));
  }

  @DeleteMapping("/{share}")
  public ResponseEntity<Void> delete(Caller caller, @PathVariable String share) {
    shares.delete(share, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> listPermissions(@PathVariable String share) {
    List<SharePermissionResponse> items =
        shares.listPermissions(shares.require(share)).stream()
            .map(SharePermissionResponse::from)
            .toList();
    return ListResponse.of(items, null);
  }

  @Transactional
  @PatchMapping("/{share}/permissions")
  public ListResponse<SharePermissionResponse> updatePermissions(
      Caller caller,
      @PathVariable String share,
      @Valid @RequestBody UpdateSharePermissionsRequest request) {
    ShareEntity entity = shares.requireOwned(share, caller);
    for (UpdateSharePermissionsRequest.Change change : request.changes()) {
      var recipient = recipients.require(change.recipientName());
      change.remove().forEach(privilege -> shares.revoke(entity, recipient, privilege));
      change.add().forEach(privilege -> shares.grant(caller, entity, recipient, privilege));
    }
    return listPermissions(share);
  }

  private void apply(Caller caller, ShareEntity share, UpdateShareRequest.Update update) {
    UpdateShareRequest.DataObject dataObject = update.dataObject();
    switch (update.action()) {
      case ADD ->
          objectService.add(
              share,
              caller,
              CatalogCaller.withBearerToken(caller.name(), caller.bearerToken()),
              dataObject.name(),
              dataObject.type(),
              dataObject.sharedAs());
      case REMOVE -> objectService.remove(requireObject(share, dataObject));
    }
  }

  /**
   * A REMOVE names either the alias the object is shared as or its canonical catalog name. A
   * one-level alias is a shared schema, whose alias occupies a schema level of its own.
   */
  private SharedDataObjectEntity requireObject(
      ShareEntity share, UpdateShareRequest.DataObject dataObject) {
    if (dataObject.sharedAs() != null && !dataObject.sharedAs().isBlank()) {
      String[] alias = dataObject.sharedAs().split("\\.", -1);
      if (alias.length == 2) {
        return objects
            .find(share, alias[0], alias[1])
            .orElseThrow(() -> notShared(share, dataObject.sharedAs()));
      }
      if (alias.length == 1) {
        return objects
            .findSchemaGrant(share, alias[0])
            .orElseThrow(() -> notShared(share, dataObject.sharedAs()));
      }
    }
    return objects
        .findSource(share, dataObject.name())
        .orElseThrow(() -> notShared(share, dataObject.name()));
  }

  private static ApiException notShared(ShareEntity share, String object) {
    return ApiException.notFound(
        "'" + object + "' is not shared in '" + share.getName() + "'");
  }

  private ShareResponse withObjects(ShareEntity share) {
    return ShareResponse.withObjects(
        share,
        objects.listAll(share).stream().map(SharedDataObjectResponse::from).toList());
  }
}
