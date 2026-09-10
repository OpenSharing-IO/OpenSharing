package io.opensharing.serving;

import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.http.ListResponse;
import io.opensharing.http.Listings;
import io.opensharing.http.ProtocolMediaType;
import io.opensharing.protocol.GetShareResponse;
import io.opensharing.protocol.Schema;
import io.opensharing.protocol.Share;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareAccessService;
import io.opensharing.share.ShareEntity;
import io.opensharing.share.ShareMapper;
import io.opensharing.share.ShareStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a recipient can reach: {@code GET /shares}, {@code GET /shares/{share}} and {@code GET
 * /shares/{share}/schemas}.
 *
 * <p>A schema appears here whether a provider shared the schema itself or only tables under it, since
 * either way it is a level a recipient can address.
 */
@RestController
@RequestMapping(value = RecipientApi.SHARES, produces = ProtocolMediaType.JSON_UTF8)
public class SharesController {

  private final ShareStore shares;
  private final SharedDataObjectStore objects;
  private final ShareAccessService access;
  private final ShareMapper mapper;
  private final Listings listings;

  public SharesController(
      ShareStore shares,
      SharedDataObjectStore objects,
      ShareAccessService access,
      ShareMapper mapper,
      Listings listings) {
    this.shares = shares;
    this.objects = objects;
    this.access = access;
    this.mapper = mapper;
    this.listings = listings;
  }

  @GetMapping
  public ListResponse<Share> listShares(
      RecipientPrincipal principal,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    return listings.page(
        maxResults,
        pageToken,
        pageable -> shares.listGrantedTo(access.recipient(principal), pageable),
        mapper::share);
  }

  @GetMapping("/{share}")
  public GetShareResponse getShare(RecipientPrincipal principal, @PathVariable String share) {
    return new GetShareResponse(mapper.share(access.requireShare(principal, share)));
  }

  @GetMapping("/{share}/schemas")
  public ListResponse<Schema> listSchemas(
      RecipientPrincipal principal,
      @PathVariable String share,
      @RequestParam(required = false) Integer maxResults,
      @RequestParam(required = false) String pageToken) {
    ShareEntity shareEntity = access.requireShare(principal, share);
    return listings.page(
        maxResults,
        pageToken,
        pageable -> objects.listSchemaNames(shareEntity, pageable),
        schemaName -> mapper.schema(shareEntity, schemaName));
  }
}
