package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.auth.TokenHashes;
import io.opensharing.http.ErrorCodes;
import io.opensharing.recipient.RecipientTokenEntity;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ActivationServiceTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  private static TestServer server;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void unknownActivationCodeIsNotFound() {
    // Missing activation codes are 404 without a bearer token.
    Response missing = server.get("/api/1.0/opensharing/activations/missing", null);
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());
  }

  @Test
  void redeemsAnActivationUrlOnce() {
    // Owner creates a TOKEN recipient and gets an activation URL.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Acme\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}");
    assertEquals(201, created.status());
    String activationPath =
        URI.create(created.body().path("activationUrl").asText()).getPath();

    // Redeeming the URL issues a protocol profile with a bearer token.
    Response profile = server.get(activationPath, null);
    assertEquals(200, profile.status());
    assertEquals("no-store", profile.headers().get("Cache-Control"));
    assertEquals(
        "attachment; filename=\"config.share\"", profile.headers().get("Content-Disposition"));
    assertEquals(1, profile.body().path("shareCredentialsVersion").asInt());
    String bearer = profile.body().path("bearerToken").asText();
    assertTrue(bearer.matches("[A-Za-z0-9_-]{64}"));
    assertEquals(
        "http://127.0.0.1:" + server.server().port() + "/api/1.0/opensharing",
        profile.body().path("endpoint").asText());
    assertTrue(
        profile
            .body()
            .path("expirationTime")
            .asText()
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    assertTrue(profile.body().path("icebergEndpoint").isMissingNode());

    // Only a hash of the bearer token is stored, and the code is used up.
    RecipientTokenEntity stored = newestToken("acme");
    assertNull(stored.getActivationCode());
    assertTrue(stored.isActivated());
    assertEquals(TokenHashes.sha256(bearer), stored.getTokenHash());
    assertNotEquals(bearer, stored.getTokenHash());

    // The same code cannot be redeemed again.
    Response again = server.get(activationPath, null);
    assertEquals(404, again.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, again.errorCode());

    // After activation the recipient no longer has an activation URL.
    Response recipient = server.get(RECIPIENTS + "/acme", "alice-token");
    assertEquals(200, recipient.status());
    assertTrue(recipient.body().path("activationUrl").isMissingNode());
  }

  private static RecipientTokenEntity newestToken(String recipient) {
    return server
        .server()
        .transactions()
        .inTransaction(
            true,
            em ->
                em.createQuery(
                        "select t from RecipientTokenEntity t where t.recipient.name = :name"
                            + " order by t.createdAt desc",
                        RecipientTokenEntity.class)
                    .setParameter("name", recipient)
                    .setMaxResults(1)
                    .getSingleResult());
  }
}
