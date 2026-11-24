package io.opensharing.protocol.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AttributeKey;
import io.opensharing.asset.SharedDataObjectEntity;
import io.opensharing.asset.SharedDataObjectStore;
import io.opensharing.asset.share.ProtocolShares;
import io.opensharing.asset.table.iceberg.ProtocolIcebergTables;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.DataSourceFormat;
import io.opensharing.protocol.RecipientTokenDecorator;
import io.opensharing.recipient.RecipientPrincipal;
import io.opensharing.share.ShareEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IcebergListTablesReadTest {

  @Test
  void noPageTokenReadsTheSchemaOnceEvenAboveThePageLimit() {
    // Supply a schema larger than the internal page limit, in the store's normal order.
    List<SharedDataObjectEntity> entries = new ArrayList<>();
    // Populate a distinct alias for each table.
    for (int i = 0; i < 501; i++) {
      // Build the stored object with its recorded type and format.
      SharedDataObjectEntity entry = new SharedDataObjectEntity();
      // Only tables are included in the response.
      entry.setType(AssetType.TABLE);
      // Each object belongs to the Iceberg catalog.
      entry.setSourceFormat(DataSourceFormat.ICEBERG);
      // Match the store's sorted-alias order.
      entry.setSharedAsTable(String.format("table%03d", i));
      // Include it in the simulated storage result.
      entries.add(entry);
    }
    // Replace persistence and authorization to count the calls made by the real service.
    AtomicInteger reads = new AtomicInteger();
    // Count the grant checks independently.
    AtomicInteger grants = new AtomicInteger();
    // The stub avoids a database while preserving the real listing implementation.
    SharedDataObjectStore objects =
        new SharedDataObjectStore(null) {
          @Override
          public List<SharedDataObjectEntity> listInSchema(ShareEntity share, String schema) {
            // Count reads of the shared objects.
            reads.incrementAndGet();
            // Supply the sorted schema contents.
            return entries;
          }
        };
    // The authorization stub counts checks without requiring a database.
    ProtocolShares shares =
        new ProtocolShares(null, null) {
          @Override
          public ShareEntity requireGranted(RecipientPrincipal recipient, String shareName) {
            // Count grant checks for the request.
            grants.incrementAndGet();
            // Supply the granted share.
            return new ShareEntity();
          }
        };
    // Exercise the real protocol and service over those dependencies.
    ProtocolIcebergTables catalog = new ProtocolIcebergTables(shares, objects, null);
    // Namespace listing is not used by this test.
    IcebergRestCatalogService service = new IcebergRestCatalogService(shares, null, catalog);
    // Construct a context for the request.
    ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    // The service normally receives this principal from the authentication decorator.
    ctx.setAttr(
        AttributeKey.valueOf(RecipientTokenDecorator.class, "recipient"),
        new RecipientPrincipal(UUID.randomUUID()));

    // A missing page token requests all tables regardless of page size.
    TablesResponse response = service.listTables(ctx, "share", "lake", 1, null);

    // All aliases are present.
    assertEquals(501, response.identifiers().size());
    // The first alias retains the store's ordering.
    assertEquals("table000", response.identifiers().get(0).name());
    // The final alias is included beyond the internal page limit.
    assertEquals("table500", response.identifiers().get(500).name());
    // No continuation is returned.
    assertNull(response.nextPageToken());
    // A concurrent share edit cannot affect a second read because there is none.
    assertEquals(1, reads.get());
    // Authorization is also checked only once.
    assertEquals(1, grants.get());
  }
}
