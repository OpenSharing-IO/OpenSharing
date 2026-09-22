package io.opensharing.share;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
      "spring.datasource.url=jdbc:h2:mem:protocol-schemas;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class ProtocolSchemaControllerTest {

  private static final String PROVIDER = "/api/1.0/opensharing/provider";
  private static final String PROTOCOL = "/api/1.0/opensharing";

  @Autowired private MockMvc mvc;

  @Test
  void listsDistinctSchemasInAGrantedShare() throws Exception {
    createShare("schema-share");
    addObject("schema-share", "TABLE", "catalog.alpha.t1", "alpha.t1");
    addObject("schema-share", "TABLE", "catalog.alpha.t2", "alpha.t2");
    addObject("schema-share", "SCHEMA", "catalog.beta", "beta");
    addObject("schema-share", "TABLE", "catalog.gamma.t3", "gamma.t3");
    createShare("hidden-share");
    addObject("hidden-share", "TABLE", "catalog.hidden.t", "hidden.t");
    String bearer = createAndActivateRecipient("schema-partner");
    grant("schema-share", "schema-partner");

    // Distinct shared-as schemas, ordered by name; an ungranted share is not listed.
    mvc.perform(
            get(PROTOCOL + "/shares/SCHEMA-SHARE/schemas")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding("UTF-8"))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].name").value("alpha"))
        .andExpect(jsonPath("$.items[0].share").value("schema-share"))
        .andExpect(jsonPath("$.items[1].name").value("beta"))
        .andExpect(jsonPath("$.items[2].name").value("gamma"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/hidden-share/schemas")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
    mvc.perform(
            get(PROTOCOL + "/shares/missing/schemas").header("Authorization", "Bearer " + bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST));
  }

  @Test
  void paginatesSchemasAndAllowsAnEmptyGrantedShare() throws Exception {
    createShare("paged-schemas");
    addObject("paged-schemas", "TABLE", "catalog.alpha.t", "alpha.t");
    addObject("paged-schemas", "TABLE", "catalog.beta.t", "beta.t");
    createShare("empty-share");
    String bearer = createAndActivateRecipient("schema-paged");
    grant("paged-schemas", "schema-paged");
    grant("empty-share", "schema-paged");

    String first =
        mvc.perform(
                get(PROTOCOL + "/shares/paged-schemas/schemas")
                    .param("maxResults", "1")
                    .header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("alpha"))
            .andExpect(jsonPath("$.nextPageToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String pageToken = JsonPath.read(first, "$.nextPageToken");
    mvc.perform(
            get(PROTOCOL + "/shares/paged-schemas/schemas")
                .param("maxResults", "1")
                .param("pageToken", pageToken)
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("beta"))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());

    mvc.perform(
            get(PROTOCOL + "/shares/empty-share/schemas")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextPageToken").doesNotExist());
  }

  private void createShare(String name) throws Exception {
    mvc.perform(
            post(PROVIDER + "/shares")
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted(name)))
        .andExpect(status().isCreated());
  }

  private void addObject(String share, String type, String name, String sharedAs) throws Exception {
    mvc.perform(
            patch(PROVIDER + "/shares/" + share)
                .header("Authorization", "Bearer alice-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"updates":[{"action":"ADD","dataObject":{"name":"%s","type":"%s","sharedAs":"%s"}}]}
                    """
                        .formatted(name, type, sharedAs)))
        .andExpect(status().isOk());
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
