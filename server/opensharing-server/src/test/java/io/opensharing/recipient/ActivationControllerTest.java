package io.opensharing.recipient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ErrorCodes;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    mvc.perform(get(activationPath))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shareCredentialsVersion").value(1))
        .andExpect(jsonPath("$.endpoint").value("http://localhost/api/1.0/opensharing"))
        .andExpect(jsonPath("$.bearerToken").exists())
        .andExpect(jsonPath("$.expirationTime").doesNotExist())
        .andExpect(jsonPath("$.icebergEndpoint").doesNotExist());

    // The same code cannot be redeemed again.
    mvc.perform(get(activationPath))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));

    // After activation the recipient no longer has an activation URL.
    mvc.perform(get(RECIPIENTS + "/acme").header("Authorization", "Bearer alice-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activationUrl").doesNotExist());
  }

  @TestConfiguration
  static class CatalogAuthorization {

    @Bean
    @Primary
    CatalogConnector testCatalogConnector() {
      return new CatalogConnector() {
        @Override
        public String name() {
          return "test";
        }

        @Override
        public ResolvedAsset resolveAsset(AssetLookup lookup, UserContext user) {
          return ResolvedAsset.builder(lookup.type(), lookup.identifier()).build();
        }

        @Override
        public List<StorageCredentials> getStorageCredentials(
            CredentialRequest request, UserContext user) {
          return List.of();
        }

        @Override
        public UserContext authorize(String bearerToken, String privilege) {
          return switch (bearerToken) {
            case "alice-token" -> new UserContext("catalog-alice-id", "alice");
            case "bob-token" -> new UserContext("catalog-bob-id", "bob");
            default -> throw new CatalogAuthorizationException("invalid bearer token");
          };
        }
      };
    }
  }
}
