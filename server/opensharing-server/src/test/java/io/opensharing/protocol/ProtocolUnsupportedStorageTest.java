package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads of tables in storage this build does not read, through the local catalog. */
class ProtocolUnsupportedStorageTest extends ProtocolApiSupport {

  @TempDir static Path directory;

  @BeforeAll
  static void startUnsupportedStorageServer() throws IOException {
    String catalog =
        """
        credentials:
          provider: AZURE
        principals:
          - bearerToken: alice-token
            userId: catalog-alice-id
            userName: alice
            privileges: [CREATE_SHARE, CREATE_RECIPIENT, USE_SHARE, USE_RECIPIENT]
        assets:
          - identifier: main.lake
            type: SCHEMA
          - identifier: main.lake.hdfs
            attributes:
              subtype: MANAGED
            storageLocation: hdfs://namenode:8020/table
            format: delta
        """;
    Path file = Files.writeString(directory.resolve("local-catalog.yml"), catalog);
    Properties properties = new Properties();
    properties.setProperty("catalog.local.file", file.toString());
    restartServer(properties);
  }

  @Test
  void rejectsReadsBeforeTheResponseStarts() {
    createShare("unsupported-storage");
    addObject("unsupported-storage", "TABLE", "main.lake.hdfs", "lake.hdfs");
    String bearer = createAndActivateRecipient("unsupported-storage-partner");
    grant("unsupported-storage", "unsupported-storage-partner");

    String tables = PROTOCOL + "/shares/unsupported-storage/schemas/lake/tables/hdfs";
    String message = "this build cannot read tables in 'hdfs' storage";
    assertNotImplemented(message, server.get(tables + "/version", bearer));
    assertNotImplemented(message, server.get(tables + "/metadata", bearer));
    assertNotImplemented(message, server.post(tables + "/query", bearer, "{}"));
    assertNotImplemented(
        message, server.get(tables + "/changes?startingVersion=0", bearer));
  }

  private static void assertNotImplemented(String message, Response response) {
    assertEquals(501, response.status());
    assertEquals(ErrorCodes.NOT_IMPLEMENTED, response.errorCode());
    assertEquals(message, response.body().path("message").asText());
  }
}
