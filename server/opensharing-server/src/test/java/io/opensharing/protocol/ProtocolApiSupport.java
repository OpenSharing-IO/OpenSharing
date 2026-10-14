package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Provider setup shared by recipient protocol API tests. Each test class gets its own server.
 * Catalog lookup and provider auth go through {@code LocalCatalogConnector} and {@code
 * local-catalog.yml}.
 */
public abstract class ProtocolApiSupport {

  protected static final String PROVIDER = "/api/1.0/opensharing/provider";
  protected static final String PROTOCOL = "/api/1.0/opensharing";
  protected static final String ALICE = "alice-token";

  protected static TestServer server;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  protected static void createShare(String name) {
    String body = "{\"name\":\"%s\"}".formatted(name);
    assertEquals(201, server.post(PROVIDER + "/shares", ALICE, body).status());
  }

  /** Creates a share owned by Alice through the provider API. */
  protected static void createShare(String name, String displayName) {
    String body = "{\"name\":\"%s\",\"displayName\":\"%s\"}".formatted(name, displayName);
    assertEquals(201, server.post(PROVIDER + "/shares", ALICE, body).status());
  }

  /** Adds the catalog asset {@code name} of {@code type} to the share as {@code sharedAs}. */
  protected static void addObject(String share, String type, String name, String sharedAs) {
    String body =
        "{\"updates\":[{\"action\":\"ADD\",\"dataObject\":"
            + "{\"name\":\"%s\",\"type\":\"%s\",\"sharedAs\":\"%s\"}}]}";
    Response added =
        server.patch(PROVIDER + "/shares/" + share, ALICE, body.formatted(name, type, sharedAs));
    assertEquals(200, added.status());
  }

  /** Creates a TOKEN recipient, redeems its activation URL, and returns the bearer token. */
  protected static String createAndActivateRecipient(String name) {
    Response created =
        server.post(
            PROVIDER + "/recipients",
            ALICE,
            "{\"name\":\"%s\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}"
                .formatted(name));
    assertEquals(201, created.status());
    String activationPath =
        URI.create(created.body().path("activationUrl").asText()).getPath();
    Response profile = server.get(activationPath, null);
    assertEquals(200, profile.status());
    return profile.body().path("bearerToken").asText();
  }

  /** Grants {@code recipient} SELECT on Alice's {@code share} through the provider API. */
  protected static void grant(String share, String recipient) {
    String body = "{\"changes\":[{\"recipientName\":\"%s\",\"add\":[\"SELECT\"]}]}";
    Response granted =
        server.patch(
            PROVIDER + "/shares/" + share + "/permissions", ALICE, body.formatted(recipient));
    assertEquals(200, granted.status());
  }

  /** {@code path} with a {@code maxResults} and, unless null, a {@code pageToken} parameter. */
  protected static String page(String path, int maxResults, String pageToken) {
    String query = "?maxResults=" + maxResults;
    if (pageToken != null) {
      query += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
    }
    return path + query;
  }
}
