package io.opensharing.share;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
          throw new UnsupportedOperationException();
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
