package io.opensharing.share;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
      "spring.datasource.url=jdbc:h2:mem:protocol-shares;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class ProtocolShareControllerTest {

  private static final String PROVIDER = "/api/1.0/opensharing/provider";
  private static final String PROTOCOL_SHARES = "/api/1.0/opensharing/shares";

  @Autowired private MockMvc mvc;

  @Test
  void authenticatesARecipientAndListsOnlyGrantedShares() throws Exception {
    // Create two shares and grant only one to the recipient.
    createShare("protocol-granted", "Visible");
    createShare("protocol-hidden", "Hidden");
    String bearer = createAndActivateRecipient("protocol-partner");
    grant("protocol-granted", "protocol-partner");

    // The protocol list exposes the granted share and its protocol metadata only.
    mvc.perform(get(PROTOCOL_SHARES).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("protocol-granted"))
        .andExpect(jsonPath("$.items[0].displayName").value("Visible"))
        .andExpect(jsonPath("$.items[0].id").exists())
        .andExpect(jsonPath("$.items[0].objects").doesNotExist())
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
  }

  @Test
  void rejectsMissingInvalidAndSupersededTokens() throws Exception {
    // Protocol requests require an activated recipient bearer.
    mvc.perform(get(PROTOCOL_SHARES))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
    mvc.perform(get(PROTOCOL_SHARES).header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));

    // Rotation with the default zero grace period invalidates the old bearer immediately.
    String bearer = createAndActivateRecipient("protocol-rotated");
    mvc.perform(
            post(PROVIDER + "/recipients/protocol-rotated/rotate-token")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(get(PROTOCOL_SHARES).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));
  }

  @Test
  void paginatesGrantedSharesWithOpaqueTokens() throws Exception {
    // Grant two ordered shares to one recipient.
    createShare("protocol-page-a", "A");
    createShare("protocol-page-b", "B");
    String bearer = createAndActivateRecipient("protocol-paged");
    grant("protocol-page-a", "protocol-paged");
    grant("protocol-page-b", "protocol-paged");

    // Resume from the opaque token without repeating the first item.
    String first =
        mvc.perform(
                get(PROTOCOL_SHARES)
                    .param("maxResults", "1")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstName = JsonPath.read(first, "$.items[0].name");
    String pageToken = JsonPath.read(first, "$.nextPageToken");
    String second =
        mvc.perform(
                get(PROTOCOL_SHARES)
                    .param("maxResults", "1")
                    .param("pageToken", pageToken)
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextPageToken").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertNotEquals(firstName, JsonPath.read(second, "$.items[0].name"));

    // Malformed pagination input is rejected.
    mvc.perform(
            get(PROTOCOL_SHARES)
                .param("pageToken", "not-a-token")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PARAMETER_VALUE));
  }

  private void createShare(String name, String displayName) throws Exception {
    mvc.perform(
            post(PROVIDER + "/shares")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"%s","displayName":"%s"}
                    """
                        .formatted(name, displayName)))
        .andExpect(status().isCreated());
  }

  private String createAndActivateRecipient(String name) throws Exception {
    String created =
        mvc.perform(
                post(PROVIDER + "/recipients")
                    .header("Authorization", "Bearer alice-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"%s","authenticationType":"TOKEN"}
                        """
                            .formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String activationPath = URI.create(JsonPath.read(created, "$.activationUrl")).getPath();
    String profile =
        mvc.perform(get(activationPath))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(profile, "$.bearerToken");
  }

  private void grant(String share, String recipient) throws Exception {
    mvc.perform(
            patch(PROVIDER + "/shares/" + share + "/permissions")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"%s","add":["SELECT"]}]}
                    """
                        .formatted(recipient)))
        .andExpect(status().isOk());
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
          if ("alice-token".equals(bearerToken)) {
            return new UserContext("catalog-alice-id", "alice");
          }
          throw new CatalogAuthorizationException("invalid bearer token");
        }
      };
    }
  }
}
