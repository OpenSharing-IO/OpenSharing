package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.opensharing.TestServer.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcebergConfigTest extends LocalIcebergTableSupport {

  static final String ICEBERG = PROTOCOL + "/iceberg";

  @Test
  void configuresAGrantedShareAsTheCatalogPrefix() {
    createShare("iceberg-config");
    String bearer = createAndActivateRecipient("iceberg-config-partner");
    grant("iceberg-config", "iceberg-config-partner");

    // The warehouse is looked up case-insensitively, and the prefix names the share as stored.
    Response config = server.get(ICEBERG + "/v1/config?warehouse=ICEBERG-CONFIG", bearer);
    assertEquals(200, config.status());
    JsonNode body = config.body();
    assertEquals("shares/iceberg-config", body.path("overrides").path("prefix").asText());
    // No defaults alter the client configuration.
    assertTrue(body.path("defaults").isObject());
    assertEquals(0, body.path("defaults").size());
    assertTrue(body.path("endpoints").isArray());
  }

  @Test
  void reportsFailuresInIcebergsErrorBody() {
    createShare("iceberg-config-hidden");
    String bearer = createAndActivateRecipient("iceberg-config-stranger");

    Response missing = server.get(ICEBERG + "/v1/config", bearer);
    assertIcebergError(missing, 400, "BadRequestException");

    // The spec has no 404 for getConfig, so a share the recipient holds no SELECT on is
    // forbidden.
    Response hidden = server.get(ICEBERG + "/v1/config?warehouse=iceberg-config-hidden", bearer);
    assertIcebergError(hidden, 403, "ForbiddenException");
    // A share that doesn't exist gets the same answer, so a recipient can't probe for shares.
    Response unknown = server.get(ICEBERG + "/v1/config?warehouse=iceberg-config-none", bearer);
    assertIcebergError(unknown, 403, "ForbiddenException");
    assertEquals(
        hidden.body().path("error").path("message").asText(),
        unknown.body().path("error").path("message").asText());

    // A failed token check gets the same body, and names the scheme to authenticate with.
    Response unauthenticated = server.get(ICEBERG + "/v1/config?warehouse=x", "not-a-token");
    assertIcebergError(unauthenticated, 401, "NotAuthorizedException");
    assertEquals("Bearer", unauthenticated.headers().get("www-authenticate"));
  }

  @Test
  void reportsUnservedRoutesInIcebergsErrorBody() {
    // Grant a recipient SELECT on a share.
    createShare("iceberg-unserved");
    String bearer = createAndActivateRecipient("iceberg-unserved-partner");
    grant("iceberg-unserved", "iceberg-unserved-partner");
    String namespaces = ICEBERG + "/v1/shares/iceberg-unserved/namespaces";

    // The catalog serves no views.
    assertIcebergError(server.get(namespaces + "/sales/views", bearer), 404, "NotFoundException");
    // It serves no namespace creation, whatever it serves under that path with another method.
    assertIcebergError(server.post(namespaces, bearer, "{}"), 404, "NotFoundException");
    // A request to an unserved route still has to pass the token check.
    Response unauthenticated = server.get(namespaces + "/sales/views", "not-a-token");
    assertIcebergError(unauthenticated, 401, "NotAuthorizedException");
  }

  /** Iceberg clients read {@code error.type} and {@code error.code} to tell failures apart. */
  static void assertIcebergError(Response response, int status, String type) {
    assertEquals(status, response.status());
    JsonNode error = response.body().path("error");
    assertEquals(type, error.path("type").asText());
    assertEquals(status, error.path("code").asInt());
    assertTrue(!error.path("message").asText().isEmpty());
  }

  /** Asserts that getConfig's {@code endpoints} list {@code endpoint}, wherever in the list. */
  static void assertAdvertised(JsonNode endpoints, String endpoint) {
    List<String> advertised = new ArrayList<>();
    for (JsonNode listed : endpoints) {
      advertised.add(listed.asText());
    }
    assertTrue(advertised.contains(endpoint), endpoint + " is not among " + advertised);
  }
}
