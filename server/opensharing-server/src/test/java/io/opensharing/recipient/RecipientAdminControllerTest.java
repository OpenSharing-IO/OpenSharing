package io.opensharing.recipient;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ErrorCodes;
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

}
