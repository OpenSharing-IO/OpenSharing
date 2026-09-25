package io.opensharing.asset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Provider setup shared by recipient protocol API tests. Catalog lookup and provider auth go
 * through {@code LocalCatalogConnector} and {@code classpath:local-catalog-protocol.yml}.
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog-protocol.yml"
    })
public abstract class ProtocolApiSupport {

  protected static final String PROVIDER = "/api/1.0/opensharing/provider";
  protected static final String PROTOCOL = "/api/1.0/opensharing";
  protected static final String ALICE = "alice-token";

  @Autowired protected MockMvc mvc;

  protected void createShare(String name) throws Exception {
    createShare(name, null);
  }

  protected void createShare(String name, String displayName) throws Exception {
    String body =
        displayName == null
            ? "{\"name\":\"%s\"}".formatted(name)
            : "{\"name\":\"%s\",\"displayName\":\"%s\"}".formatted(name, displayName);
    mvc.perform(
            post(PROVIDER + "/shares")
                .header("Authorization", "Bearer " + ALICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
  }

  protected void addObject(String share, String type, String name, String sharedAs)
      throws Exception {
    mvc.perform(
            patch(PROVIDER + "/shares/" + share)
                .header("Authorization", "Bearer " + ALICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"updates":[{"action":"ADD","dataObject":{"name":"%s","type":"%s","sharedAs":"%s"}}]}
                    """
                        .formatted(name, type, sharedAs)))
        .andExpect(status().isOk());
  }

  protected String createAndActivateRecipient(String name) throws Exception {
    String created =
        mvc.perform(
                post(PROVIDER + "/recipients")
                    .header("Authorization", "Bearer " + ALICE)
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

  protected void grant(String share, String recipient) throws Exception {
    mvc.perform(
            patch(PROVIDER + "/shares/" + share + "/permissions")
                .header("Authorization", "Bearer " + ALICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"changes":[{"recipientName":"%s","add":["SELECT"]}]}
                    """
                        .formatted(recipient)))
        .andExpect(status().isOk());
  }
}
