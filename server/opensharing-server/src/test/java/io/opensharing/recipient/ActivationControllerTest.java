package io.opensharing.recipient;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.auth.TokenHashes;
import io.opensharing.http.ErrorCodes;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:recipient-activation;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class ActivationControllerTest {

  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  @Autowired private MockMvc mvc;
  @Autowired private RecipientRepository recipients;
  @Autowired private RecipientTokenRepository tokens;

  @Test
  void unknownActivationCodeIsNotFound() throws Exception {
    // Missing activation codes are 404 without a bearer token.
    mvc.perform(get("/api/1.0/opensharing/activations/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void redeemsAnActivationUrlOnce() throws Exception {
    // Owner creates a TOKEN recipient and gets an activation URL.
    String body =
        mvc.perform(
                post(RECIPIENTS)
                    .header("Authorization", "Bearer alice-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Acme\",\"authenticationType\":\"TOKEN\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String activationPath = URI.create(JsonPath.read(body, "$.activationUrl")).getPath();

    // Redeeming the URL issues a protocol profile with a bearer token.
    String profile =
        mvc.perform(get(activationPath))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                header().string("Content-Disposition", "attachment; filename=\"config.share\""))
            .andExpect(jsonPath("$.shareCredentialsVersion").value(1))
            .andExpect(jsonPath("$.bearerToken").value(matchesPattern("[A-Za-z0-9_-]{64}")))
            .andExpect(jsonPath("$.endpoint").value("http://localhost/api/1.0/opensharing"))
            .andExpect(
                jsonPath("$.expirationTime")
                    .value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
            .andExpect(jsonPath("$.icebergEndpoint").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String bearer = JsonPath.read(profile, "$.bearerToken");
    RecipientTokenEntity stored =
        tokens
            .findFirstByRecipientOrderByCreatedAtDesc(recipients.findByName("acme").orElseThrow())
            .orElseThrow();
    assertNull(stored.getActivationCode());
    assertTrue(stored.isActivated());
    assertEquals(TokenHashes.sha256(bearer), stored.getTokenHash());
    assertNotEquals(bearer, stored.getTokenHash());

    // The same code cannot be redeemed again.
    mvc.perform(get(activationPath))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));

    // After activation the recipient no longer has an activation URL.
    mvc.perform(get(RECIPIENTS + "/acme").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activationUrl").doesNotExist());
  }

}
