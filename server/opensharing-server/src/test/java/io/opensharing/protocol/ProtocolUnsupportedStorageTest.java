package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.TestServer.Response;
import io.opensharing.http.ErrorCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads of tables in WASB storage, which this build does not read, through the local catalog. */
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
          - identifier: main.lake.wasb
            attributes:
              subtype: MANAGED
            storageLocation: wasb://container@account.blob.core.windows.net/wasb
            format: delta
          - identifier: main.lake.wasbs
            attributes:
              subtype: MANAGED
            storageLocation: wasbs://container@account.blob.core.windows.net/wasbs
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
    for (String table : List.of("wasb", "wasbs")) {
      addObject("unsupported-storage", "TABLE", "main.lake." + table, "lake." + table);
    }
    String bearer = createAndActivateRecipient("unsupported-storage-partner");
    grant("unsupported-storage", "unsupported-storage-partner");

    String tables = PROTOCOL + "/shares/unsupported-storage/schemas/lake/tables/";
    for (String table : List.of("wasb", "wasbs")) {
      String message = "this build cannot read tables in '" + table + "' storage";
      assertNotImplemented(message, server.get(tables + table + "/version", bearer));
      assertNotImplemented(message, server.get(tables + table + "/metadata", bearer));
      assertNotImplemented(message, server.post(tables + table + "/query", bearer, "{}"));
      assertNotImplemented(
          message, server.get(tables + table + "/changes?startingVersion=0", bearer));
    }
  }

  private static void assertNotImplemented(String message, Response response) {
    assertEquals(501, response.status());
    assertEquals(ErrorCodes.NOT_IMPLEMENTED, response.errorCode());
    assertEquals(message, response.body().path("message").asText());
  }
}
