package io.opensharing.asset.share;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.auth.AuthContext;
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

    // Get wraps the granted share; lookup is case-insensitive.
    mvc.perform(get(PROTOCOL_SHARES + "/PROTOCOL-GRANTED").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.share.name").value("protocol-granted"))
        .andExpect(jsonPath("$.share.displayName").value("Visible"))
        .andExpect(jsonPath("$.share.id").exists())
        .andExpect(jsonPath("$.share.objects").doesNotExist());

    // An ungranted share is reported as missing rather than forbidden.
    mvc.perform(get(PROTOCOL_SHARES + "/protocol-hidden").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
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

  @Test
  void listsManyGrantedSharesAcrossPages() throws Exception {
    int total = 25;
    String bearer = createAndActivateRecipient("protocol-many");
    for (int i = 0; i < total; i++) {
      String name = "protocol-many-%02d".formatted(i);
      createShare(name, "Share " + i);
      grant(name, "protocol-many");
    }

    String first =
        mvc.perform(
                get(PROTOCOL_SHARES)
                    .param("maxResults", "10")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].name").value("protocol-many-00"))
            .andExpect(jsonPath("$.items[9].name").value("protocol-many-09"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstToken = JsonPath.read(first, "$.nextPageToken");
    String second =
        mvc.perform(
                get(PROTOCOL_SHARES)
                    .param("maxResults", "10")
                    .param("pageToken", firstToken)
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].name").value("protocol-many-10"))
            .andExpect(jsonPath("$.items[9].name").value("protocol-many-19"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondToken = JsonPath.read(second, "$.nextPageToken");
    mvc.perform(
            get(PROTOCOL_SHARES)
                .param("maxResults", "10")
                .param("pageToken", secondToken)
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.items[0].name").value("protocol-many-20"))
        .andExpect(jsonPath("$.items[4].name").value("protocol-many-24"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL_SHARES)
                .param("maxResults", "10000")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(total))
        .andExpect(jsonPath("$.items[0].name").value("protocol-many-00"))
        .andExpect(jsonPath("$.items[24].name").value("protocol-many-24"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
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
          if ("alice-token".equals(token)) {
            return new UserContext("catalog-alice-id", "alice");
          }
          throw new CatalogAuthorizationException("invalid bearer token");
        }
      };
    }
  }
}
