package io.opensharing.share;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:provider-shares;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class ShareAdminControllerTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";

  @Autowired private MockMvc mvc;

  @Test
  void requiresACatalogAuthorizedPrincipal() throws Exception {
    // Missing bearer token is unauthenticated.
    mvc.perform(get(SHARES).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));

    // A token the catalog does not know is unauthenticated, and names the Bearer scheme.
    mvc.perform(get(SHARES).header("Authorization", "Bearer nobody-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
  }

  @Test
  void rejectsADuplicateShareNameInAnyCase() throws Exception {
    // Owner creates a share.
    create("alice-token", "{\"name\":\"taxes\"}").andExpect(status().isCreated());

    // The same name in another case already exists, even for another caller.
    create("bob-token", "{\"name\":\"TAXES\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_ALREADY_EXISTS));
  }

  @Test
  void rejectsInvalidShareNames() throws Exception {
    // Blank names fail request validation.
    create("alice-token", "{\"name\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // Names with a space or a forward slash are rejected.
    create("alice-token", "{\"name\":\"bad name\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
    create("alice-token", "{\"name\":\"bad/name\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  void rejectsNullPropertyValues() throws Exception {
    // Creating with a null property value is rejected.
    create("alice-token", "{\"name\":\"nulls\",\"properties\":{\"team\":null}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));

    // Updating with a null property value is rejected too.
    create("alice-token", "{\"name\":\"nulls\"}").andExpect(status().isCreated());
    mvc.perform(
            patch(SHARES + "/nulls")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"properties\":{\"team\":null}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  @Test
  void updatesOnlyTheFieldsInTheBody() throws Exception {
    // Owner creates a share with every field set.
    create(
            "alice-token",
            "{\"name\":\"travel\",\"displayName\":\"Travel\",\"comment\":\"v1\","
                + "\"properties\":{\"team\":\"finance\"}}")
        .andExpect(status().isCreated());

    // Owner updates only the comment; the other fields are kept.
    mvc.perform(
            patch(SHARES + "/travel")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"v2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comment").value("v2"))
        .andExpect(jsonPath("$.displayName").value("Travel"))
        .andExpect(jsonPath("$.properties.team").value("finance"));
  }

  @Test
  void onlyTheOwnerDeletesAShare() throws Exception {
    // Owner creates a share.
    create("alice-token", "{\"name\":\"vendors\"}").andExpect(status().isCreated());

    // Non-owner cannot delete.
    mvc.perform(delete(SHARES + "/vendors").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    // The share is still there.
    mvc.perform(get(SHARES + "/vendors").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isOk());
  }

  @Test
  void answersMissingSharesWithNotFound() throws Exception {
    // Updating a missing share is 404.
    mvc.perform(
            patch(SHARES + "/missing")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"x\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));

    // Deleting a missing share is 404.
    mvc.perform(delete(SHARES + "/missing").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void createsListsUpdatesAndDeletesAShare() throws Exception {
    // Owner creates a share; name is persisted lowercase.
    mvc.perform(
            post(SHARES)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sales\",\"comment\":\"orders\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("sales"))
        .andExpect(jsonPath("$.id").exists());

    // Owner lists shares.
    mvc.perform(get(SHARES).header("Authorization", "Bearer alice-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("sales"));

    // Get by name is case-insensitive and allowed for any catalog principal.
    mvc.perform(get(SHARES + "/SALES").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("sales"))
        .andExpect(jsonPath("$.comment").value("orders"));

    // Non-owner cannot update.
    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer bob-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"stolen\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    // Owner updates the comment.
    mvc.perform(
            patch(SHARES + "/sales")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comment").value("updated"));

    // Owner deletes the share.
    mvc.perform(delete(SHARES + "/sales").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNoContent());

    // Deleted share is 404.
    mvc.perform(get(SHARES + "/sales").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  private ResultActions create(String token, String body) throws Exception {
    return mvc.perform(
        post(SHARES)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
