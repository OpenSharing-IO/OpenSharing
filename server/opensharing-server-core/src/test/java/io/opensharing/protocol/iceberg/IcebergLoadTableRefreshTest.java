package io.opensharing.protocol.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AttributeKey;
import io.opensharing.asset.table.iceberg.IcebergStorageCredential;
import io.opensharing.asset.table.iceberg.IcebergTable;
import io.opensharing.asset.table.iceberg.ProtocolIcebergTables;
import io.opensharing.protocol.RecipientTokenDecorator;
import io.opensharing.recipient.RecipientPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IcebergLoadTableRefreshTest {

  @Test
  void legacyConfigAdvertisesOnlyTheFirstCredentialsRefresher() {
    // A refreshable GCS credential follows the first one in storage-credentials.
    IcebergStorageCredential gcs =
        new IcebergStorageCredential(
            "gs://bucket/table/",
            Map.of("gcs.oauth2.token", "second", "gcs.oauth2.token-expires-at", "1"));
    // With refreshable S3 credentials first, config names only the S3 refresher.
    IcebergStorageCredential refreshableS3 =
        new IcebergStorageCredential(
            "s3://bucket/table/",
            Map.of("s3.session-token", "first", "s3.session-token-expires-at-ms", "1"));
    LoadTableResponse refreshable = load(List.of(refreshableS3, gcs));
    // The S3 refresher uses the shared table's credentials endpoint.
    assertEquals(PATH, refreshable.config().get("client.refresh-credentials-endpoint"));
    // The later GCS credential's refresher isn't in config, which holds only the S3 properties.
    assertFalse(refreshable.config().containsKey("gcs.oauth2.refresh-credentials-endpoint"));
    // Only the first credential supplies base properties.
    assertEquals("first", refreshable.config().get("s3.session-token"));
    assertFalse(refreshable.config().containsKey("gcs.oauth2.token"));
    // Both complete credentials are vended.
    assertEquals(List.of(refreshableS3, gcs), refreshable.storageCredentials());

    // With static S3 keys first, config names no refresher, so the client keeps those keys.
    IcebergStorageCredential staticS3 =
        new IcebergStorageCredential(
            "s3://bucket/table/",
            Map.of("s3.access-key-id", "AKIATEST", "s3.secret-access-key", "secret"));
    LoadTableResponse fixed = load(List.of(staticS3, gcs));
    // Neither refresher is advertised.
    assertFalse(fixed.config().containsKey("client.refresh-credentials-endpoint"));
    assertFalse(fixed.config().containsKey("gcs.oauth2.refresh-credentials-endpoint"));
  }

  private static final String PATH = "v1/shares/share/namespaces/lake/tables/trips/credentials";

  /** loadTable with vended credentials through the real service, for a stubbed catalog lookup. */
  private static LoadTableResponse load(List<IcebergStorageCredential> credentials) {
    // Stub the catalog lookup so the test exercises the service's response configuration.
    ProtocolIcebergTables catalog =
        new ProtocolIcebergTables(null, null, null) {
          @Override
          public IcebergTable loadTable(
              RecipientPrincipal recipient,
              String share,
              String schema,
              String table,
              boolean withCredentials) {
            return new IcebergTable(
                "s3://bucket/table/metadata.json",
                JsonNodeFactory.instance.objectNode(),
                credentials);
          }
        };
    IcebergRestCatalogService service = new IcebergRestCatalogService(null, null, catalog);
    // A request context with the principal authentication normally attaches.
    ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    ctx.setAttr(
        AttributeKey.valueOf(RecipientTokenDecorator.class, "recipient"),
        new RecipientPrincipal(UUID.randomUUID()));
    return service.loadTable(ctx, "share", "lake", "trips", "vended-credentials");
  }
}
