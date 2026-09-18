package io.opensharing.recipient;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ErrorCodes;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:provider-recipients;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class RecipientAdminControllerTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  @Autowired private MockMvc mvc;

  @Test
  void requiresACatalogAuthorizedPrincipal() throws Exception {
    // Missing bearer token is unauthenticated.
    mvc.perform(get(RECIPIENTS).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
  }

  @Test
  void createsListsUpdatesAndDeletesARecipient() throws Exception {
    // Owner creates a TOKEN recipient and gets an activation URL.
    mvc.perform(
            post(RECIPIENTS)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"comment\":\"partner\",\"authenticationType\":\"TOKEN\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("acme"))
        .andExpect(jsonPath("$.authenticationType").value("TOKEN"))
        .andExpect(jsonPath("$.activationUrl").exists())
        .andExpect(
            jsonPath("$.activationUrl")
                .value(startsWith("http://localhost/api/1.0/opensharing/activations/")))
        .andExpect(jsonPath("$.id").exists());

    // Owner lists recipients including activation URLs.
    mvc.perform(get(RECIPIENTS).header("Authorization", "Bearer alice-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("acme"))
        .andExpect(
            jsonPath("$.items[0].activationUrl")
                .value(startsWith("http://localhost/api/1.0/opensharing/activations/")));

    // Get by name is case-insensitive and allowed for any catalog principal.
    mvc.perform(get(RECIPIENTS + "/ACME").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("acme"))
        .andExpect(jsonPath("$.comment").value("partner"))
        .andExpect(jsonPath("$.authenticationType").value("TOKEN"))
        .andExpect(
            jsonPath("$.activationUrl")
                .value(startsWith("http://localhost/api/1.0/opensharing/activations/")));

    // Non-owner cannot update.
    mvc.perform(
            patch(RECIPIENTS + "/acme")
                .header("Authorization", "Bearer bob-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"stolen\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    // Owner updates the comment.
    mvc.perform(
            patch(RECIPIENTS + "/acme")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comment").value("updated"));

    // Owner deletes the recipient.
    mvc.perform(delete(RECIPIENTS + "/acme").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNoContent());

    // Deleted recipient is 404.
    mvc.perform(get(RECIPIENTS + "/acme").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void rejectsOidcUntilImplemented() throws Exception {
    // OIDC is reserved and rejected at create.
    mvc.perform(
            post(RECIPIENTS)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"oidc-partner\",\"authenticationType\":\"OIDC\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  void createsARecipientWithAnExplicitTokenLifetime() throws Exception {
    // Create can set the first token's lifetime in seconds.
    String created =
        mvc.perform(
                post(RECIPIENTS)
                    .header("Authorization", "Bearer alice-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Timed","authenticationType":"TOKEN","tokenExpirationDays":1}
                        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Activating the issued URL puts that expiration on the profile.
    String activationPath = URI.create(JsonPath.read(created, "$.activationUrl")).getPath();
    String profile =
        mvc.perform(get(activationPath))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expirationTime").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Instant expiresAt = Instant.parse(JsonPath.read(profile, "$.expirationTime"));
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    assertTrue(remaining.compareTo(Duration.ofHours(23)) >= 0);
    assertTrue(remaining.compareTo(Duration.ofHours(25)) <= 0);
  }

  @Test
  void rotatesARecipientToken() throws Exception {
    // Create a recipient with its initial pending activation.
    String created =
        mvc.perform(
                post(RECIPIENTS)
                    .header("Authorization", "Bearer alice-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Rotate\",\"authenticationType\":\"TOKEN\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String oldActivation = URI.create(JsonPath.read(created, "$.activationUrl")).getPath();

    // Non-owner cannot rotate the token.
    mvc.perform(
            post(RECIPIENTS + "/rotate/rotate-token")
                .header("Authorization", "Bearer bob-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    // Owner replaces it with a one-hour token and no grace window.
    String rotated =
        mvc.perform(
                post(RECIPIENTS + "/rotate/rotate-token")
                    .header("Authorization", "Bearer alice-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"tokenExpirationDays":1,"existingTokenExpireInSeconds":0}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.tokenId").exists())
            .andExpect(jsonPath("$.recipientName").value("rotate"))
            .andExpect(jsonPath("$.activationUrl").exists())
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Rotation invalidates the old pending activation immediately.
    mvc.perform(get(oldActivation))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));

    // The replacement activation yields the requested profile.
    String newActivation = URI.create(JsonPath.read(rotated, "$.activationUrl")).getPath();
    mvc.perform(get(newActivation))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shareCredentialsVersion").value(1))
        .andExpect(jsonPath("$.bearerToken").value(matchesPattern("[A-Za-z0-9_-]{64}")))
        .andExpect(jsonPath("$.expirationTime").exists());
  }

}
