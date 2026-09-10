package io.opensharing;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Serves the Iceberg REST catalog end to end against Iceberg tables on disk, over the same HTTP
 * surface a recipient's engine would call.
 *
 * <p>Each table here is a metadata document and nothing else, which is all this catalog reads: the
 * manifests and data files it names are the recipient's business, reached with the credentials
 * handed over beside it.
 */
class IcebergCatalogApiTest extends ServerTestBase {

  private static Path tables;

  private String share;
  private String token;

  @DynamicPropertySource
  static void pointTheCatalogAtIcebergTablesOnDisk(DynamicPropertyRegistry registry)
      throws IOException {
    tables = Files.createTempDirectory("iceberg-tables");
    writeTable("orders", "0001");
    writeTable("events", "0002");
    Files.createDirectories(tables.resolve("elsewhere"));
    Files.writeString(tables.resolve("elsewhere/stolen.metadata.json"), "{}");

    Path catalog = Files.createTempFile("iceberg-catalog", ".yml");
    Files.writeString(
        catalog,
        """
        credentials:
          provider: AWS
          mode: FAKE
          ttlSeconds: 900
        assets:
          - identifier: main.sales.orders
            type: TABLE
            storageLocation: %1$s/orders
            metadataLocation: %1$s/orders/metadata/00001-orders.metadata.json
            format: iceberg
          - identifier: main.sales.ledger
            type: TABLE
            storageLocation: %1$s/orders
            format: delta
          # The catalog knows the table but cannot say where its current metadata is.
          - identifier: main.sales.unpointed
            type: TABLE
            storageLocation: %1$s/orders
            format: iceberg
          # A metadata pointer that leads out of the location the recipient was granted.
          - identifier: main.sales.escaped
            type: TABLE
            storageLocation: %1$s/orders
            metadataLocation: %1$s/elsewhere/stolen.metadata.json
            format: iceberg
          # A whole schema, whose tables are whatever the catalog says it holds.
          - identifier: main.warehouse
            type: SCHEMA
          - identifier: main.warehouse.events
            type: TABLE
            storageLocation: %1$s/events
            metadataLocation: %1$s/events/metadata/00001-events.metadata.json
            format: iceberg
        """
            .formatted(tables));
    registry.add("opensharing.catalog.local.file", () -> "file:" + catalog);
  }

  /** One Iceberg table: a metadata document naming a schema, a partition spec and one snapshot. */
  private static void writeTable(String name, String uuidSuffix) throws IOException {
    Path root = tables.resolve(name);
    Files.createDirectories(root.resolve("metadata"));
    Files.writeString(
        root.resolve("metadata/00001-" + name + ".metadata.json"),
        """
        {
          "format-version" : 2,
          "table-uuid" : "9f0c1e6a-0000-4000-8000-00000000%1$s",
          "location" : "%2$s",
          "last-sequence-number" : 1,
          "last-updated-ms" : 1735689600000,
          "last-column-id" : 3,
          "current-schema-id" : 0,
          "schemas" : [ {
            "type" : "struct",
            "schema-id" : 0,
            "fields" : [ {
              "id" : 1,
              "name" : "order_id",
              "required" : true,
              "type" : "long"
            }, {
              "id" : 2,
              "name" : "amount",
              "required" : false,
              "type" : "decimal(12, 2)"
            }, {
              "id" : 3,
              "name" : "country",
              "required" : false,
              "type" : "string"
            } ]
          } ],
          "default-spec-id" : 0,
          "partition-specs" : [ {
            "spec-id" : 0,
            "fields" : [ {
              "name" : "country",
              "transform" : "identity",
              "source-id" : 3,
              "field-id" : 1000
            } ]
          } ],
          "last-partition-id" : 1000,
          "default-sort-order-id" : 0,
          "sort-orders" : [ {
            "order-id" : 0,
            "fields" : [ ]
          } ],
          "properties" : {
            "write.format.default" : "parquet"
          },
          "current-snapshot-id" : 4004485655637928000,
          "refs" : {
            "main" : {
              "snapshot-id" : 4004485655637928000,
              "type" : "branch"
            }
          },
          "snapshots" : [ {
            "sequence-number" : 1,
            "snapshot-id" : 4004485655637928000,
            "timestamp-ms" : 1735689600000,
            "summary" : {
              "operation" : "append",
              "added-records" : "6"
            },
            "manifest-list" : "%2$s/metadata/snap-4004485655637928000-1.avro",
            "schema-id" : 0
          } ],
          "snapshot-log" : [ {
            "timestamp-ms" : 1735689600000,
            "snapshot-id" : 4004485655637928000
          } ],
          "metadata-log" : [ ]
        }
        """
            .formatted(uuidSuffix, root));
  }

  @BeforeEach
  void shareTheTables() throws Exception {
    share = createShare(unique("iceberg_share"));
    addTable(share, "sales.orders", "main.sales.orders");
    addTable(share, "sales.ledger", "main.sales.ledger");
    String recipient = unique("iceberg_recipient");
    token = createRecipientWithToken(recipient);
    grant(share, recipient);
  }

  @Test
  void handsOverTheTablesOwnMetadataDocument() throws Exception {
    JsonNode loaded = catalogGet(table("sales", "orders"));

    Path onDisk = tables.resolve("orders/metadata/00001-orders.metadata.json");
    assertEquals(onDisk.toString(), loaded.get("metadata-location").asText());
    assertEquals(
        json.readTree(Files.readString(onDisk)),
        loaded.get("metadata"),
        "the metadata is relayed as it stands, not rebuilt");
    assertEquals(2, loaded.get("metadata").get("format-version").asInt());
    assertEquals(
        "country",
        loaded
            .get("metadata")
            .get("partition-specs")
            .get(0)
            .get("fields")
            .get(0)
            .get("name")
            .asText());
  }

  @Test
  void vendsCredentialsInTheSpellingAnIcebergClientReadsThem() throws Exception {
    JsonNode loaded = catalogGet(table("sales", "orders"));

    JsonNode config = loaded.get("config");
    assertTrue(config.get("s3.access-key-id").asText().startsWith("ASIA"), config.toString());
    assertNotNull(config.get("s3.secret-access-key"));
    assertNotNull(config.get("s3.session-token"));
    assertTrue(
        config.get("s3.session-token-expires-at-ms").asLong() > System.currentTimeMillis(),
        "a client refreshes on the expiry it is told about");

    JsonNode credential = loaded.get("storage-credentials").get(0);
    assertEquals(tables.resolve("orders").toString(), credential.get("prefix").asText());
    assertEquals(config, credential.get("config"), "the same grant, said both ways");
  }

  @Test
  void listsOnlyTheTablesThisCatalogCanLoad() throws Exception {
    JsonNode listed = catalogGet("/namespaces/sales/tables");

    assertEquals(1, listed.get("identifiers").size(), "sales.ledger is a Delta table");
    assertEquals("orders", listed.get("identifiers").get(0).get("name").asText());
    assertEquals("sales", listed.get("identifiers").get(0).get("namespace").get(0).asText());
  }

  @Test
  void reportsATableOfAnotherFormatAsOneThisCatalogDoesNotHave() throws Exception {
    call(get(url(table("sales", "ledger"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value(404))
        .andExpect(jsonPath("$.error.type").value("RESOURCE_DOES_NOT_EXIST"))
        .andExpect(jsonPath("$.error.message").value(containsString("not an Iceberg table")));
  }

  @Test
  void saysSoWhenTheCatalogCannotSayWhereTheMetadataIs() throws Exception {
    addTable(share, "sales.unpointed", "main.sales.unpointed");

    call(get(url(table("sales", "unpointed"))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.type").value("CATALOG_ERROR"))
        .andExpect(
            jsonPath("$.error.message").value(containsString("temporary-table-credentials")));
  }

  @Test
  void refusesToFetchMetadataFromOutsideTheSharedLocation() throws Exception {
    addTable(share, "sales.escaped", "main.sales.escaped");

    call(get(url(table("sales", "escaped"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.type").value("PERMISSION_DENIED"));
  }

  /** A table nobody named individually, reached through the schema that was shared instead. */
  @Test
  void loadsATableAWholeSharedSchemaBrought() throws Exception {
    adminPatch(
        "/shares/" + share,
        "{\"updates\":[{\"action\":\"ADD\",\"data_object\":{\"name\":\"main.warehouse\","
            + "\"type\":\"SCHEMA\",\"shared_as\":\"warehouse\"}}]}");

    JsonNode listed = catalogGet("/namespaces/warehouse/tables");
    assertEquals("events", listed.get("identifiers").get(0).get("name").asText());

    JsonNode loaded = catalogGet(table("warehouse", "events"));
    assertEquals(
        tables.resolve("events").toString(), loaded.get("metadata").get("location").asText());
  }

  @Test
  void pagesNamespacesTheWayTheRestOfTheProtocolPages() throws Exception {
    addTable(share, "research.trials", "main.sales.unpointed");

    JsonNode first = catalogGet("/namespaces?pageSize=1");
    assertEquals(1, first.get("namespaces").size());
    assertEquals("research", first.get("namespaces").get(0).get(0).asText());

    JsonNode second =
        catalogGet("/namespaces?pageSize=1&pageToken=" + first.get("next-page-token").asText());
    assertEquals("sales", second.get("namespaces").get(0).get(0).asText());
  }

  /** A schema holds tables, so a client asking what is under one is told nothing, not refused. */
  @Test
  void hasNoNamespacesUnderANamespace() throws Exception {
    assertEquals(0, catalogGet("/namespaces?parent=sales").get("namespaces").size());
  }

  @Test
  void hasOnlyTheOneLevelOfNamespaceAShareHas() throws Exception {
    call(get(url("/namespaces/sales\u001Forders")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.message").value(containsString("one level deep")));
  }

  @Test
  void acceptsAScanReportAndDropsIt() throws Exception {
    call(post(url(table("sales", "orders") + "/metrics"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"report-type\":\"scan-report\",\"table-name\":\"orders\","
                    + "\"snapshot-id\":4004485655637928000,\"metrics\":{}}"))
        .andExpect(status().isNoContent());

    call(post(url(table("sales", "nothing") + "/metrics"))).andExpect(status().isNotFound());
  }

  @Test
  void answersAnUnknownTableAsAnIcebergClientExpects() throws Exception {
    call(get(url(table("sales", "nothing"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value(404))
        .andExpect(jsonPath("$.error.message").value(containsString("does not exist")));
  }

  private static String table(String namespace, String name) {
    return "/namespaces/" + namespace + "/tables/" + name;
  }

  /** Every call goes under the prefix the {@code /v1/config} handshake told the client to use. */
  private String url(String path) {
    return PROTOCOL_BASE + "/iceberg/v1/shares/" + share + path;
  }

  private JsonNode catalogGet(String path) throws Exception {
    return readJson(call(get(url(path))).andExpect(status().isOk()).andReturn());
  }

  private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token));
  }
}
