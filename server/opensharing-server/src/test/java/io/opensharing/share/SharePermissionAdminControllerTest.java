package io.opensharing.share;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opensharing.auth.AuthContext;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.AssetLookup;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ErrorCodes;
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
      "spring.datasource.url=jdbc:h2:mem:provider-share-permissions;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class SharePermissionAdminControllerTest {

  private static final String SHARES = "/api/1.0/opensharing/provider/shares";
  private static final String RECIPIENTS = "/api/1.0/opensharing/provider/recipients";

  @Autowired private MockMvc mvc;

  @Test
  void grantsAndRevokesSelect() throws Exception {
    // Create the share that will be granted.
    mvc.perform(
            post(SHARES)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sales\"}"))
        .andExpect(status().isCreated());

    // Create the recipient that will receive SELECT.
    mvc.perform(
            post(RECIPIENTS)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"authenticationType\":\"TOKEN\"}"))
        .andExpect(status().isCreated());

    // Non-owner cannot grant.
    mvc.perform(
            patch(SHARES + "/sales/permissions")
                .header("Authorization", "Bearer bob-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"acme","add":["SELECT"]}]}
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PERMISSION_DENIED));

    // Owner grants SELECT (recipient name is case-insensitive).
    mvc.perform(
            patch(SHARES + "/sales/permissions")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"Acme","add":["SELECT"]}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].recipientName").value("acme"))
        .andExpect(jsonPath("$.items[0].shareName").value("sales"))
        .andExpect(jsonPath("$.items[0].privilege").value("SELECT"));

    // Granting the same privilege again is idempotent.
    mvc.perform(
            patch(SHARES + "/sales/permissions")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"acme","add":["SELECT"]}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    // Listing permissions is allowed for any catalog principal.
    mvc.perform(get(SHARES + "/sales/permissions").header("Authorization", "Bearer bob-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].privilege").value("SELECT"));

    // Owner revokes SELECT.
    mvc.perform(
            patch(SHARES + "/sales/permissions")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"acme","remove":["SELECT"]}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());

    // Revoking a privilege that is not granted is 404.
    mvc.perform(
            patch(SHARES + "/sales/permissions")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"acme","remove":["SELECT"]}]}
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
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
        public ResolvedAsset resolveAsset(AssetLookup lookup, AuthContext auth) {
          return ResolvedAsset.builder(lookup.type(), lookup.identifier()).build();
        }

        @Override
        public List<StorageCredentials> getStorageCredentials(
            CredentialRequest request, AuthContext auth) {
          return List.of();
        }

        @Override
        public UserContext authorize(AuthContext auth, String privilege) {
          String token = auth.user() == null ? null : auth.user().bearerToken();
          return switch (token) {
            case "alice-token" -> new UserContext("catalog-alice-id", "alice");
            case "bob-token" -> new UserContext("catalog-bob-id", "bob");
            default -> throw new CatalogAuthorizationException("invalid bearer token");
          };
        }
      };
    }
  }
}
