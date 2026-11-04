package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.TestServer;
import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipientAdminServiceTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  private static TestServer server;
  private static String activations;

  @BeforeAll
  static void startServer() {
    server = TestServer.start();
    activations =
        "http://127.0.0.1:" + server.server().port() + "/api/1.0/opensharing/activations/";
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @Test
  void requiresACatalogAuthorizedPrincipal() {
    // Missing bearer token is unauthenticated.
    Response missing = server.get(RECIPIENTS, null);
    assertEquals(401, missing.status());
    assertEquals(ErrorCodes.UNAUTHENTICATED, missing.errorCode());
  }

  @Test
  void createsListsUpdatesAndDeletesARecipient() {
    // Owner creates a TOKEN recipient and gets an activation URL.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Acme\",\"comment\":\"partner\",\"authenticationType\":\"TOKEN\","
                + "\"tokenExpirationDays\":30}");
    assertEquals(201, created.status());
    assertEquals("acme", created.body().path("name").asText());
    assertEquals("TOKEN", created.body().path("authenticationType").asText());
    assertTrue(created.body().path("activationUrl").asText().startsWith(activations));
    assertFalse(created.body().path("id").isMissingNode());

    // Owner lists recipients including activation URLs.
    Response list = server.get(RECIPIENTS, "alice-token");
    assertEquals(200, list.status());
    assertEquals("acme", list.body().path("items").get(0).path("name").asText());
    assertTrue(
        list.body().path("items").get(0).path("activationUrl").asText().startsWith(activations));

    // Get by name is case-insensitive and allowed for any catalog principal.
    Response fetched = server.get(RECIPIENTS + "/ACME", "bob-token");
    assertEquals(200, fetched.status());
    assertEquals("acme", fetched.body().path("name").asText());
    assertEquals("partner", fetched.body().path("comment").asText());
    assertEquals("TOKEN", fetched.body().path("authenticationType").asText());
    assertTrue(fetched.body().path("activationUrl").asText().startsWith(activations));

    // Non-owner cannot update.
    Response stolen = server.patch(RECIPIENTS + "/acme", "bob-token", "{\"comment\":\"stolen\"}");
    assertEquals(403, stolen.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, stolen.errorCode());

    // Owner updates the comment.
    Response updated =
        server.patch(RECIPIENTS + "/acme", "alice-token", "{\"comment\":\"updated\"}");
    assertEquals(200, updated.status());
    assertEquals("updated", updated.body().path("comment").asText());

    // Owner deletes the recipient.
    assertEquals(204, server.delete(RECIPIENTS + "/acme", "alice-token").status());

    // Deleted recipient is 404.
    Response deleted = server.get(RECIPIENTS + "/acme", "alice-token");
    assertEquals(404, deleted.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, deleted.errorCode());
  }

  @Test
  void createsARecipientWithTheRequestedTokenLifetime() {
    // Create requires the first token's lifetime.
    Response missing =
        server.post(
            RECIPIENTS, "alice-token", "{\"name\":\"Timed\",\"authenticationType\":\"TOKEN\"}");
    assertEquals(400, missing.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, missing.errorCode());

    // Create sets the first token's lifetime in days.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Timed\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":1}");
    assertEquals(201, created.status());

    // Activating the issued URL puts that expiration on the profile.
    Response profile = server.get(activationPath(created), null);
    assertEquals(200, profile.status());
    Instant expiresAt = Instant.parse(profile.body().path("expirationTime").asText());
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    assertTrue(remaining.compareTo(Duration.ofHours(23)) >= 0);
    assertTrue(remaining.compareTo(Duration.ofHours(25)) <= 0);
  }

  @Test
  void rotatesARecipientToken() {
    // Create a recipient with its initial pending activation.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Rotate\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}");
    assertEquals(201, created.status());
    String oldActivation = activationPath(created);

    // Non-owner cannot rotate the token.
    Response denied =
        server.post(
            RECIPIENTS + "/rotate/rotate-token", "bob-token", "{\"tokenExpirationDays\":1}");
    assertEquals(403, denied.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, denied.errorCode());

    // Owner replaces it with a one-day token and no grace window.
    Response rotated =
        server.post(
            RECIPIENTS + "/rotate/rotate-token",
            "alice-token",
            "{\"tokenExpirationDays\":1,\"existingTokenExpireInSeconds\":0}");
    assertEquals(201, rotated.status());
    assertFalse(rotated.body().path("tokenId").isMissingNode());
    assertEquals("rotate", rotated.body().path("recipientName").asText());
    assertFalse(rotated.body().path("expiresAt").isMissingNode());

    // Rotation invalidates the old pending activation immediately.
    Response old = server.get(oldActivation, null);
    assertEquals(404, old.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, old.errorCode());

    // The replacement activation yields the requested profile.
    Response profile = server.get(activationPath(rotated), null);
    assertEquals(200, profile.status());
    assertEquals(1, profile.body().path("shareCredentialsVersion").asInt());
    assertTrue(profile.body().path("bearerToken").asText().matches("[A-Za-z0-9_-]{64}"));
    assertFalse(profile.body().path("expirationTime").isMissingNode());

    // Rotation requires the replacement token's lifetime.
    Response missing = server.post(RECIPIENTS + "/rotate/rotate-token", "alice-token", "{}");
    assertEquals(400, missing.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, missing.errorCode());

    // Without a grace window, rotating again expires the activated token immediately.
    Instant before = Instant.now();
    Response again =
        server.post(
            RECIPIENTS + "/rotate/rotate-token", "alice-token", "{\"tokenExpirationDays\":1}");
    assertEquals(201, again.status());
    assertTrue(again.body().path("activationUrl").asText().startsWith(activations));
    Instant expired = expiresAt(rotated.body().path("tokenId").asText());
    assertFalse(expired.isBefore(before));
    assertFalse(expired.isAfter(Instant.now()));
  }

  @Test
  void rejectsACommentLongerThanItsColumn() {
    // A comment at the column's length is stored.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Commented\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":1,"
                + "\"comment\":\""
                + "c".repeat(65536)
                + "\"}");
    assertEquals(201, created.status());

    // One character more is rejected on create and on update.
    String tooLong = "comment must not exceed 65536 characters";
    assertInvalid(
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Overlong\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":1,"
                + "\"comment\":\""
                + "c".repeat(65537)
                + "\"}"),
        tooLong);
    assertInvalid(
        server.patch(
            RECIPIENTS + "/commented",
            "alice-token",
            "{\"comment\":\"" + "c".repeat(65537) + "\"}"),
        tooLong);
  }

  @Test
  void rejectsTokenLifetimesEndingAfterTheYear9999() {
    String tooLong = "tokenExpirationDays must not expire the token after 9999-12-31T23:59:59.999Z";

    // Create rejects them.
    for (long days : new long[] {3_000_000, Long.MAX_VALUE}) {
      assertInvalid(
          server.post(
              RECIPIENTS,
              "alice-token",
              "{\"name\":\"Forever\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":"
                  + days
                  + "}"),
          tooLong);
    }

    // So does rotation.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Forever\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":1}");
    assertEquals(201, created.status());
    assertInvalid(
        server.post(
            RECIPIENTS + "/forever/rotate-token",
            "alice-token",
            "{\"tokenExpirationDays\":" + Long.MAX_VALUE + "}"),
        tooLong);
  }

  @Test
  void keepsTheExpirationOfATokenThatEndsWithinTheGraceWindow() {
    // Activate a recipient's first token.
    Response created =
        server.post(
            RECIPIENTS,
            "alice-token",
            "{\"name\":\"Graceful\",\"authenticationType\":\"TOKEN\",\"tokenExpirationDays\":30}");
    assertEquals(201, created.status());
    Response profile = server.get(activationPath(created), null);
    assertEquals(200, profile.status());
    String bearer = profile.body().path("bearerToken").asText();
    Instant expiresAt = activatedTokenExpiresAt("graceful");

    // The longest grace window leaves the token working until it expired before.
    Response rotated =
        server.post(
            RECIPIENTS + "/graceful/rotate-token",
            "alice-token",
            "{\"tokenExpirationDays\":1,\"existingTokenExpireInSeconds\":" + Long.MAX_VALUE + "}");
    assertEquals(201, rotated.status());
    assertEquals(expiresAt, activatedTokenExpiresAt("graceful"));
    assertEquals(200, server.get("/api/1.0/opensharing/shares", bearer).status());
  }

  private static Instant activatedTokenExpiresAt(String recipient) {
    return server
        .server()
        .transactions()
        .inTransaction(
            true,
            em ->
                em.createQuery(
                        "select t.expiresAt from RecipientTokenEntity t"
                            + " where t.recipient.name = :name and t.activated = true",
                        Instant.class)
                    .setParameter("name", recipient)
                    .getSingleResult());
  }

  private static void assertInvalid(Response response, String message) {
    assertEquals(400, response.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, response.errorCode());
    assertEquals(message, response.body().path("message").asText());
  }

  private static Instant expiresAt(String tokenId) {
    return server
        .server()
        .transactions()
        .inTransaction(
            true, em -> em.find(RecipientTokenEntity.class, tokenId).getExpiresAt());
  }

  private static String activationPath(Response response) {
    return URI.create(response.body().path("activationUrl").asText()).getPath();
  }
}
