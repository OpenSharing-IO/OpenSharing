package io.opensharing.asset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opensharing.asset.table.delta.DeltaKernel;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.TableActions;
import io.opensharing.asset.table.delta.DeltaTableMetadataReader;
import io.opensharing.asset.table.delta.DeltaTableQueryReader;
import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.CatalogConnector;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
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
   * In-process Query Table Version, Metadata, and Query tests stub Kernel. Cloud integration tests
   * set {@code opensharing.test.stub-protocol-dependencies=false} to use the real readers.
   */
  @TestConfiguration
  static class StubDeltaKernel {

    private static final String STUB_SCHEMA =
        "{\\\"type\\\":\\\"struct\\\",\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\",\\\"nullable\\\":true,\\\"metadata\\\":{}}]}";
    private static final String STUB_PARQUET =
        """
        {"protocol":{"minReaderVersion":1}}
        {"metaData":{"id":"stub-table","format":{"provider":"parquet"},"schemaString":"%s","partitionColumns":[],"location":"s3://test/main.sales.orders/","accessModes":["url","dir"]}}
        """
            .formatted(STUB_SCHEMA);
    private static final String STUB_DELTA =
        """
        {"protocol":{"deltaProtocol":{"minReaderVersion":1,"minWriterVersion":2}}}
        {"metaData":{"location":"s3://test/main.sales.orders/","accessModes":["url","dir"],"deltaMetadata":{"id":"stub-table","format":{"provider":"parquet"},"schemaString":"%s","partitionColumns":[]}}}
        """
            .formatted(STUB_SCHEMA);
    private static final String STUB_QUERY_PARQUET =
        STUB_PARQUET
            + """
            {"file":{"url":"https://example.invalid/stub.parquet","id":"stub-file","partitionValues":{},"size":1,"expirationTimestamp":4102444800000}}
            """;
    private static final String STUB_QUERY_DELTA =
        STUB_DELTA
            + """
            {"file":{"id":"stub-file","expirationTimestamp":4102444800000,"deltaSingleAction":{"add":{"path":"https://example.invalid/stub.parquet","partitionValues":{},"size":1,"modificationTime":0,"dataChange":true}}}}
            """;

    private static final String STUB_CHANGES =
        """
        {"add":{"url":"https://example.invalid/stub.parquet","id":"stub-file","partitionValues":{},"size":1,"version":7,"timestamp":0}}
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
            ResolvedAsset table,
            Long version,
            Instant timestamp,
            AuthContext auth,
            String capabilities) {
          if (version != null && timestamp != null) {
            throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
          }
          boolean delta =
              DeltaSharingCapabilities.choose(capabilities)
                  == DeltaSharingCapabilities.ResponseFormat.DELTA;
          return new DeltaTableMetadataReader.Result(
              version == null && timestamp == null ? 123 : 45, delta ? STUB_DELTA : STUB_PARQUET);
        }
      };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(
        name = "opensharing.test.stub-protocol-dependencies",
        havingValue = "true",
        matchIfMissing = true)
    DeltaTableQueryReader testDeltaTableQueryReader(DeltaKernel kernel) {
      return new DeltaTableQueryReader(kernel, null, null) {
        @Override
        public DeltaTableQueryReader.Result read(
            ResolvedAsset table,
            AuthContext auth,
            DeltaTableQueryReader.ResponseOptions options,
            Long version,
            Instant timestamp,
            String refreshToken,
            boolean includeRefreshToken,
            String jsonPredicateHints,
            Long limitHint) {
          if (version != null && timestamp != null) {
            throw ApiException.invalidParameter("version and timestamp are mutually exclusive");
          }
          boolean delta =
              DeltaSharingCapabilities.choose(options.capabilities())
                  == DeltaSharingCapabilities.ResponseFormat.DELTA;
          String ndjson = delta ? STUB_QUERY_DELTA : STUB_QUERY_PARQUET;
          if (includeRefreshToken || options.includeEndStreamAction()) {
            ndjson +=
                TableActions.endStreamAction(
                    includeRefreshToken ? "stub-refresh-token" : null, null, 4102444800000L);
          }
          return new DeltaTableQueryReader.Result(
              version == null && timestamp == null ? 123 : 45, ndjson);
        }

        @Override
        public DeltaTableQueryReader.Result readChanges(
            ResolvedAsset table,
            long startingVersion,
            Long endingVersion,
            AuthContext auth,
            String capabilities,
            String fileIdHash,
            boolean includeHistoricalProtocol,
            boolean includeEndStreamAction) {
          String ndjson = STUB_PARQUET + STUB_CHANGES;
          if (includeEndStreamAction) {
            ndjson += TableActions.endStreamAction(null, null, 4102444800000L);
          }
          return new DeltaTableQueryReader.Result(startingVersion, ndjson);
        }
      };
    }
  }
}
