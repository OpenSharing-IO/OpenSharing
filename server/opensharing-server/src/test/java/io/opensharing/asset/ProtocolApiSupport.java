package io.opensharing.asset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.asset.table.DeltaKernel;
import io.opensharing.asset.table.DeltaTableMetadataReader;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Provider setup shared by recipient protocol API tests. Catalog lookup and provider auth go
 * through {@code LocalCatalogConnector} and {@code classpath:local-catalog.yml}.
 */
@AutoConfigureMockMvc
@Import(ProtocolApiSupport.StubDeltaKernel.class)
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

  /**
   * In-process Query Table Version and Metadata tests stub Kernel. Cloud integration tests set
   * {@code opensharing.test.stub-protocol-dependencies=false} to use the real readers.
   */
  @TestConfiguration
  static class StubDeltaKernel {

    private static final String STUB_METADATA =
        """
        {"protocol":{"minReaderVersion":1}}
        {"metaData":{"id":"stub-table","format":{"provider":"parquet"},"schemaString":"{\\"type\\":\\"struct\\",\\"fields\\":[{\\"name\\":\\"id\\",\\"type\\":\\"long\\",\\"nullable\\":true,\\"metadata\\":{}}]}","partitionColumns":[],"location":"s3://test/main.sales.orders/","accessModes":["url","dir"]}}
        """;

    @Bean
    @Primary
    @ConditionalOnProperty(
        name = "opensharing.test.stub-protocol-dependencies",
        havingValue = "true",
        matchIfMissing = true)
    DeltaKernel testDeltaKernel(CatalogConnector catalog) {
      return new DeltaKernel(catalog) {
        @Override
        public long getVersion(ResolvedAsset table, Instant timestamp, AuthContext auth) {
          return timestamp == null ? 123 : 45;
        }
      };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(
        name = "opensharing.test.stub-protocol-dependencies",
        havingValue = "true",
        matchIfMissing = true)
    DeltaTableMetadataReader testDeltaTableMetadataReader(DeltaKernel kernel) {
      return new DeltaTableMetadataReader(kernel) {
        @Override
        public DeltaTableMetadataReader.Result read(
            ResolvedAsset table, Long version, Instant timestamp, AuthContext auth) {
          if (version != null && timestamp != null) {
            throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
          }
          return new DeltaTableMetadataReader.Result(version == null && timestamp == null ? 123 : 45, STUB_METADATA);
        }
      };
    }
  }
}
