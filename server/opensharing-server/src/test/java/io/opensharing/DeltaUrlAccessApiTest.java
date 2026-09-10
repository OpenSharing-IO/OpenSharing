package io.opensharing;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Serves the Delta read operations end to end against a real Delta log on disk, over the same HTTP
 * surface a recipient uses. The catalog is pointed at the two-commit table under
 * {@code src/test/resources/delta-table}, so these tests exercise log replay, response shaping and
 * url signing together.
 */
class DeltaUrlAccessApiTest extends ServerTestBase {

  private static final String CAPABILITIES = "delta-sharing-capabilities";
  private static final String TABLE_VERSION = "Delta-Table-Version";

  private String share;
  private String token;

  @DynamicPropertySource
  static void pointTheCatalogAtALocalDeltaTable(DynamicPropertyRegistry registry)
      throws IOException {
    Path catalog = Files.createTempFile("delta-catalog", ".yml");
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
            storageLocation: %s
            format: delta
          - identifier: main.sales.evolving
            type: TABLE
            storageLocation: %s
            format: delta
          - identifier: main.sales.vectors
            type: TABLE
            storageLocation: %s
            format: delta
          - identifier: main.sales.dormant
            type: TABLE
            storageLocation: %s
            format: delta
          - identifier: main.sales.leftover
            type: TABLE
            storageLocation: %s
            format: delta
          - identifier: main.sales.stocked
            type: TABLE
            storageLocation: %s
            format: delta
          - identifier: main.sales.forecast
            type: TABLE
            storageLocation: /tmp/forecast
            format: iceberg
          - identifier: main.sales.notes
            type: TABLE
            storageLocation: /tmp/notes
            format: parquet
        """
            .formatted(
                tableRoot("orders"),
                tableRoot("evolving"),
                tableRoot("vectors"),
                tableRoot("dormant"),
                tableRoot("leftover"),
                tableRoot("stocked")));
    registry.add("opensharing.catalog.local.file", () -> "file:" + catalog);
  }

  private static Path tableRoot(String name) throws IOException {
    return new ClassPathResource("delta-table/" + name).getFile().toPath();
  }

  @BeforeEach
  void shareTheTable() throws Exception {
    share = createShare(unique("delta_share"));
    addTable(share, "sales.orders", "main.sales.orders");
    String recipient = unique("delta_recipient");
    token = createRecipientWithToken(recipient);
    grant(share, recipient);
  }

  @Test
  void offersUrlAccessOnADeltaTable() throws Exception {
    JsonNode tables = protocolGet(token, "/shares/" + share + "/all-tables");
    JsonNode orders = tables.get("items").get(0);

    List<String> modes = new ArrayList<>();
    orders.get("accessModes").forEach(mode -> modes.add(mode.asText()));
    assertEquals(List.of("dir", "url"), modes, "a Delta table this server can read offers both");
  }

  @Test
  void answersTheTableVersionInAHeaderWithNoBody() throws Exception {
    MvcResult result =
        perform(get(protocol("/version"))).andReturn();

    assertEquals(200, result.getResponse().getStatus());
    assertEquals("2", result.getResponse().getHeader(TABLE_VERSION));
    assertEquals("", result.getResponse().getContentAsString());
  }

  @Test
  void answersMetadataAsProtocolThenMetaData() throws Exception {
    MvcResult result = perform(get(protocol("/metadata"))).andReturn();
    List<JsonNode> lines = ndjson(result);

    assertEquals(2, lines.size());
    assertEquals(1, lines.get(0).get("protocol").get("minReaderVersion").asInt());
    JsonNode metadata = lines.get(1).get("metaData");
    assertEquals("11111111-2222-3333-4444-555555555555", metadata.get("id").asText());
    assertEquals("parquet", metadata.get("format").get("provider").asText());
    assertEquals("country", metadata.get("partitionColumns").get(0).asText());
    assertTrue(metadata.get("schemaString").asText().contains("order_id"));
    assertEquals("2", result.getResponse().getHeader(TABLE_VERSION));
    assertEquals("responseformat=parquet", result.getResponse().getHeader(CAPABILITIES));
  }

  @Test
  void namesTheTableLocationSoADirectoryClientCanUseIt() throws Exception {
    JsonNode metadata = ndjson(perform(get(protocol("/metadata"))).andReturn()).get(1).get("metaData");

    assertTrue(metadata.get("location").asText().endsWith("delta-table/orders"));
    assertEquals("dir", metadata.get("accessModes").get(0).asText());
  }

  @Test
  void answersQueryWithOneFilePerActiveAddAction() throws Exception {
    List<JsonNode> lines = ndjson(perform(post(protocol("/query")).content("{}")).andReturn());

    assertEquals(4, lines.size(), "protocol, metaData and one line per file");
    JsonNode metadata = lines.get(1).get("metaData");
    assertEquals(2, metadata.get("numFiles").asLong());
    assertEquals(2345 + 1300, metadata.get("size").asLong());

    JsonNode file = lines.get(2).get("file");
    assertNotNull(file.get("url").asText());
    assertNotNull(file.get("id").asText());
    assertTrue(file.get("size").asLong() > 0);
    assertTrue(file.get("stats").asText().contains("numRecords"));
    assertTrue(
        file.get("expirationTimestamp").asLong() > Instant.now().toEpochMilli(),
        "a url that has already expired is no use to a recipient");
  }

  /**
   * The one table here whose data files are really on disk, so that a query can be followed all the
   * way to the bytes. Every other fixture is a log over files that were never written, which is
   * enough for the rest because nothing in this server opens a data file — but it leaves the last
   * step of a read, that the url handed out resolves to the file the log described, untested.
   *
   * <p>The bytes are checked for length and for being parquet, and not parsed: what a column holds
   * is parquet's business, while whether a recipient can reach it at the advertised size is this
   * server's.
   */
  @Test
  void handsOutUrlsThatResolveToTheFilesTheLogDescribed() throws Exception {
    addTable(share, "sales.stocked", "main.sales.stocked");

    List<JsonNode> lines =
        ndjson(perform(post(protocolOf("stocked", "/query")).content("{}")).andReturn());

    assertEquals(2, lines.get(1).get("metaData").get("numFiles").asLong());
    assertEquals(808 + 797, lines.get(1).get("metaData").get("size").asLong());

    List<JsonNode> files =
        lines.stream().filter(line -> line.has("file")).map(line -> line.get("file")).toList();
    assertEquals(2, files.size());
    long rows = 0;
    for (JsonNode file : files) {
      byte[] bytes = Files.readAllBytes(Path.of(URI.create(file.get("url").asText())));

      assertEquals(
          file.get("size").asLong(),
          bytes.length,
          "a recipient sizes its read from the response before fetching");
      assertEquals("PAR1", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
      assertEquals(
          "PAR1",
          new String(bytes, bytes.length - 4, 4, StandardCharsets.US_ASCII),
          "a truncated file would still open with the magic");
      rows +=
          Long.parseLong(
              file.get("stats").asText().replaceAll(".*\"numRecords\":(\\d+).*", "$1"));
    }
    assertEquals(5, rows, "the rows the two files actually hold");
  }

  @Test
  void keepsAFileIdStableAcrossRequests() throws Exception {
    String first = fileIdsOf(ndjson(perform(post(protocol("/query")).content("{}")).andReturn()));
    String second = fileIdsOf(ndjson(perform(post(protocol("/query")).content("{}")).andReturn()));

    assertEquals(first, second, "a client caches bytes against the id, so it must not move");
  }

  @Test
  void readsAnEarlierVersionAndStampsItOnTheFiles() throws Exception {
    List<JsonNode> lines =
        ndjson(perform(post(protocol("/query")).content("{\"version\":0}")).andReturn());

    assertEquals(3, lines.size(), "version 0 has one file");
    assertEquals(0, lines.get(1).get("metaData").get("version").asLong());
    assertEquals(0, lines.get(2).get("file").get("version").asLong());
    assertEquals(
        new ClassPathResource("delta-table/orders/_delta_log/00000000000000000000.json")
            .getFile()
            .lastModified(),
        lines.get(2).get("file").get("timestamp").asLong(),
        "the timestamp of the version the file belongs to, not of the file");
  }

  @Test
  void closesTheStreamWhenTheClientAsksItTo() throws Exception {
    MvcResult result =
        perform(
                post(protocol("/query"))
                    .content("{}")
                    .header(CAPABILITIES, "includeEndStreamAction=true"))
            .andReturn();
    List<JsonNode> lines = ndjson(result);

    JsonNode last = lines.get(lines.size() - 1).get("endStreamAction");
    assertNotNull(last, "the last line closes the stream");
    assertTrue(last.get("minUrlExpirationTimestamp").asLong() > Instant.now().toEpochMilli());
    assertEquals(
        "responseformat=parquet;includeendstreamaction=true",
        result.getResponse().getHeader(CAPABILITIES),
        "a client that must watch for the last line is told that it will come");
  }

  @Test
  void answersInDeltaFormatWhenTheClientAsksForIt() throws Exception {
    MvcResult result =
        perform(get(protocol("/metadata")).header(CAPABILITIES, "responseFormat=delta")).andReturn();
    List<JsonNode> lines = ndjson(result);

    assertEquals("responseformat=delta", result.getResponse().getHeader(CAPABILITIES));
    JsonNode protocol = lines.get(0).get("protocol").get("deltaProtocol");
    assertEquals(1, protocol.get("minReaderVersion").asInt());
    assertEquals(2, protocol.get("minWriterVersion").asInt(), "the whole action, not just a reader");

    JsonNode metadata = lines.get(1).get("metaData");
    assertTrue(
        metadata.get("location").asText().endsWith("delta-table/orders"),
        "where the table lives is the sharing server's to say, so it sits beside the action");
    JsonNode delta = metadata.get("deltaMetadata");
    assertEquals("11111111-2222-3333-4444-555555555555", delta.get("id").asText());
    assertEquals("country", delta.get("partitionColumns").get(0).asText());
    assertTrue(delta.get("schemaString").asText().contains("order_id"));
  }

  @Test
  void wrapsEachFileAsTheLogsOwnActionInDeltaFormat() throws Exception {
    JsonNode parquet = ndjson(perform(post(protocol("/query")).content("{}")).andReturn()).get(2);
    List<JsonNode> lines =
        ndjson(
            perform(
                    post(protocol("/query"))
                        .content("{}")
                        .header(CAPABILITIES, "responseFormat=delta"))
                .andReturn());

    JsonNode file = lines.get(2).get("file");
    assertEquals(
        parquet.get("file").get("id").asText(),
        file.get("id").asText(),
        "the same file, so the same id whichever format asked for it");
    assertTrue(file.get("expirationTimestamp").asLong() > Instant.now().toEpochMilli());

    JsonNode add = file.get("deltaSingleAction").get("add");
    assertEquals(
        parquet.get("file").get("url").asText(),
        add.get("path").asText(),
        "where parquet format puts a url, delta format puts it in the log's own path field");
    assertTrue(add.get("dataChange").asBoolean());
    assertTrue(add.get("modificationTime").asLong() > 0);
    assertTrue(add.get("stats").asText().contains("numRecords"));
  }

  @Test
  void acceptsAClientThatCanTakeEitherFormat() throws Exception {
    MvcResult result =
        perform(get(protocol("/metadata")).header(CAPABILITIES, "responseFormat=delta,parquet"))
            .andReturn();

    assertEquals(200, result.getResponse().getStatus());
    assertEquals(
        "responseformat=parquet",
        result.getResponse().getHeader(CAPABILITIES),
        "a table with nothing advanced about it is answered in the format every client reads");
  }

  @Test
  void answersInDeltaFormatWhenNothingElseCanCarryTheTable() throws Exception {
    addTable(share, "sales.vectors", "main.sales.vectors");

    MvcResult result =
        perform(
                get(protocolOf("vectors", "/metadata"))
                    .header(
                        CAPABILITIES,
                        "responseFormat=delta,parquet;readerFeatures=deletionVectors"))
            .andReturn();

    assertEquals("responseformat=delta", result.getResponse().getHeader(CAPABILITIES));
    assertEquals(
        List.of("deletionVectors"),
        readerFeatures(ndjson(result).get(0).get("protocol").get("deltaProtocol")));
  }

  @Test
  void refusesEitherFormatToAClientThatCannotReadWhatTheTableUses() throws Exception {
    addTable(share, "sales.vectors", "main.sales.vectors");

    for (String header : List.of("responseFormat=parquet", "responseFormat=delta")) {
      perform(get(protocolOf("vectors", "/metadata")).header(CAPABILITIES, header))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value(containsString("deletionVectors")));
    }
  }

  @Test
  void refusesParquetForATableUsingWhatThatFormatCannotSay() throws Exception {
    addTable(share, "sales.vectors", "main.sales.vectors");

    perform(
            get(protocolOf("vectors", "/metadata"))
                .header(CAPABILITIES, "responseFormat=parquet;readerFeatures=deletionVectors"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.message").value(containsString("responseformat=delta")));
  }

  @Test
  void servesATableThatOnlyNamesAFeatureToAnyClient() throws Exception {
    addTable(share, "sales.dormant", "main.sales.dormant");

    List<JsonNode> lines =
        ndjson(perform(post(protocolOf("dormant", "/query")).content("{}")).andReturn());

    assertEquals(
        1,
        lines.get(0).get("protocol").get("minReaderVersion").asInt(),
        "the table names deletion vectors and uses none, so a parquet client is told what it can act "
            + "on rather than a version it would refuse");
    assertEquals(1, lines.get(1).get("metaData").get("numFiles").asLong());
    assertNotNull(lineWith(lines, "file").get("url").asText());
  }

  @Test
  void refusesAFileWhoseDeletionVectorOutlivedTheSettingThatMadeIt() throws Exception {
    addTable(share, "sales.leftover", "main.sales.leftover");

    // The table says the feature is off, so the format was settled as parquet, and only the file
    // itself reveals that some of its rows are gone.
    perform(get(protocolOf("leftover", "/metadata"))).andExpect(status().isOk());
    perform(post(protocolOf("leftover", "/query")).content("{}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.message").value(containsString("deletion vector")));
  }

  @Test
  void signsADeletionVectorAsItSignsTheFileItBelongsTo() throws Exception {
    addTable(share, "sales.vectors", "main.sales.vectors");

    List<JsonNode> lines =
        ndjson(
            perform(
                    post(protocolOf("vectors", "/query"))
                        .content("{}")
                        .header(CAPABILITIES, "responseFormat=delta;readerFeatures=deletionVectors"))
                .andReturn());

    JsonNode file = lines.get(2).get("file");
    assertNotNull(file.get("deletionVectorFileId"), "a client caches a vector as it caches a file");
    JsonNode vector = file.get("deltaSingleAction").get("add").get("deletionVector");
    assertEquals("p", vector.get("storageType").asText(), "a url is a path, not the log's own name");
    assertTrue(
        vector
            .get("pathOrInlineDv")
            .asText()
            .endsWith("deletion_vector_d3c4b5a6-1111-4222-8333-444455556666.bin"),
        vector.get("pathOrInlineDv").asText());
    assertEquals(2, vector.get("cardinality").asLong());
  }

  @Test
  void echoesTheFileIdSchemeItUsedAndRejectsOthers() throws Exception {
    for (String scheme : List.of("PARQUET", "delta")) {
      MvcResult result =
          perform(post(protocol("/query")).content("{}").header("fileidhash", scheme)).andReturn();
      assertEquals(scheme.toLowerCase(), result.getResponse().getHeader("fileidhash"));
    }

    perform(post(protocol("/query")).content("{}").header("fileidhash", "sha256"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refusesAVersionAndATimestampTogether() throws Exception {
    perform(get(protocol("/metadata?version=0&timestamp=2024-01-01T00:00:00Z")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answersForAnIcebergTableThatTheFormatIsNotServedYet() throws Exception {
    addTable(share, "sales.forecast", "main.sales.forecast");

    // The endpoint belongs to the table, not to Delta, so asking about an Iceberg table is a
    // reasonable request this server cannot yet answer — not a bad one.
    perform(get(PROTOCOL_BASE + "/shares/" + share + "/schemas/sales/tables/forecast/metadata"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.message").value(containsString("Iceberg REST catalog")));
  }

  @Test
  void answersForATableWhoseFormatNoOneServes() throws Exception {
    addTable(share, "sales.notes", "main.sales.notes");

    perform(get(PROTOCOL_BASE + "/shares/" + share + "/schemas/sales/tables/notes/version"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.message").value(containsString("parquet")));
  }

  @Test
  void servesTheChangeDataFeedAsAddsChangesAndRemovals() throws Exception {
    List<JsonNode> lines = ndjson(perform(get(protocol("/changes?startingVersion=2"))).andReturn());

    assertEquals(5, lines.size(), "protocol, metaData and the three changes of commit 2");
    JsonNode removed = lineWith(lines, "remove");
    assertEquals(2, removed.get("version").asInt());
    assertEquals("NL", removed.get("partitionValues").get("country").asText());
    assertNotNull(removed.get("url").asText());

    JsonNode changed = lineWith(lines, "cdf");
    assertTrue(changed.get("url").asText().contains("_change_data"), changed.get("url").asText());
    assertTrue(changed.get("expirationTimestamp").asLong() > Instant.now().toEpochMilli());

    assertTrue(lineWith(lines, "add").get("stats").asText().contains("numRecords"));
  }

  @Test
  void spansTheWholeHistoryWhenNoWindowIsGiven() throws Exception {
    List<JsonNode> lines = ndjson(perform(get(protocol("/changes"))).andReturn());

    assertEquals(7, lines.size(), "protocol, metaData and every change across the three commits");
  }

  @Test
  void stampsAChangeFeedWithTheVersionItsFilesStartAt() throws Exception {
    MvcResult result = perform(get(protocol("/changes?startingVersion=2"))).andReturn();

    assertEquals(
        "2",
        result.getResponse().getHeader(TABLE_VERSION),
        "a reader carries on from where the response starts, not from where it ends");
  }

  @Test
  void answersAStartingVersionQueryWithWhatEachCommitChanged() throws Exception {
    MvcResult result =
        perform(post(protocol("/query")).content("{\"startingVersion\":1}")).andReturn();
    List<JsonNode> lines = ndjson(result);

    assertEquals("1", result.getResponse().getHeader(TABLE_VERSION));
    assertEquals(0, lines.stream().filter(line -> line.has("file")).count(), "not a snapshot");
    assertEquals(
        List.of(1L, 2L),
        lines.stream()
            .filter(line -> line.has("add"))
            .map(line -> line.get("add").get("version").asLong())
            .toList(),
        "the file commit 1 added, then the one commit 2 added in the removed file's place");
    assertEquals(2, lineWith(lines, "remove").get("version").asInt());
    assertEquals(
        0,
        lines.stream().filter(line -> line.has("cdf")).count(),
        "the recorded row-level changes belong to the changes endpoint");
  }

  @Test
  void refusesAStartingVersionAlongsideATimeTravel() throws Exception {
    perform(post(protocol("/query")).content("{\"startingVersion\":1,\"version\":2}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsASchemaChangeInsideAStartingVersionQuery() throws Exception {
    addTable(share, "sales.evolving", "main.sales.evolving");

    List<JsonNode> lines =
        ndjson(
            perform(
                    post(protocolOf("evolving", "/query"))
                        .content("{\"startingVersion\":0,\"endingVersion\":1}"))
                .andReturn());

    JsonNode changed = lines.get(lines.size() - 2).get("metaData");
    assertEquals(1, changed.get("version").asLong(), "the commit that changed the schema");
    assertTrue(changed.get("schemaString").asText().contains("note"));
    assertFalse(
        lines.get(1).get("metaData").get("schemaString").asText().contains("note"),
        "the response opens with the schema the reader is starting from");
  }

  @Test
  void reportsASchemaChangeInTheChangeFeedWhenAsked() throws Exception {
    addTable(share, "sales.evolving", "main.sales.evolving");

    List<JsonNode> lines =
        ndjson(
            perform(
                    get(
                        protocolOf(
                            "evolving",
                            "/changes?startingVersion=0&endingVersion=1"
                                + "&includeHistoricalMetadata=true")))
                .andReturn());

    List<Long> schemaVersions =
        lines.stream()
            .filter(line -> line.has("metaData"))
            .map(line -> line.get("metaData").get("version").asLong())
            .toList();
    assertEquals(List.of(0L, 1L), schemaVersions, "the window's own schema, then the change to it");
  }

  @Test
  void followsAWindowPastAProtocolChangeThatChangesNothingParquetSays() throws Exception {
    addTable(share, "sales.evolving", "main.sales.evolving");

    List<JsonNode> lines =
        ndjson(
            perform(get(protocolOf("evolving", "/changes?includeHistoricalMetadata=true")))
                .andReturn());

    assertEquals(
        3,
        lines.stream().filter(line -> line.has("add")).count(),
        "the table raised its reader version mid-window without turning anything on, which a "
            + "reader of these lines has no reason to hear about");
    assertEquals(1, lines.get(0).get("protocol").get("minReaderVersion").asInt());
  }

  @Test
  void reportsAProtocolChangeInDeltaFormatWhenAsked() throws Exception {
    addTable(share, "sales.evolving", "main.sales.evolving");

    List<JsonNode> lines =
        ndjson(
            perform(
                    get(
                            protocolOf(
                                "evolving",
                                "/changes?includeHistoricalMetadata=true"
                                    + "&includeHistoricalProtocol=true"))
                        .header(
                            CAPABILITIES, "responseFormat=delta;readerFeatures=deletionVectors"))
                .andReturn());

    JsonNode raised =
        lines.stream()
            .filter(line -> line.has("protocol") && line.get("protocol").has("version"))
            .reduce((first, second) -> second)
            .orElseThrow()
            .get("protocol");
    assertEquals(2, raised.get("version").asLong());
    assertEquals(3, raised.get("deltaProtocol").get("minReaderVersion").asInt());
  }

  @Test
  void refusesAChangeWindowNamedTwoWays() throws Exception {
    perform(get(protocol("/changes?startingVersion=0&startingTimestamp=2024-01-01T00:00:00Z")))
        .andExpect(status().isBadRequest());
  }

  private static JsonNode lineWith(List<JsonNode> lines, String action) {
    return lines.stream()
        .filter(line -> line.has(action))
        .map(line -> line.get(action))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no '" + action + "' line in " + lines));
  }

  private String protocol(String operation) {
    return protocolOf("orders", operation);
  }

  private String protocolOf(String table, String operation) {
    return PROTOCOL_BASE + "/shares/" + share + "/schemas/sales/tables/" + table + operation;
  }

  private static List<String> readerFeatures(JsonNode protocol) {
    List<String> features = new ArrayList<>();
    protocol.get("readerFeatures").forEach(feature -> features.add(feature.asText()));
    return features;
  }

  private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON));
  }

  private List<JsonNode> ndjson(MvcResult result) throws Exception {
    assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    List<JsonNode> lines = new ArrayList<>();
    for (String line : result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        lines.add(json.readTree(line));
      }
    }
    return lines;
  }

  private static String fileIdsOf(List<JsonNode> lines) {
    return lines.stream()
        .filter(line -> line.has("file"))
        .map(line -> line.get("file").get("id").asText())
        .sorted()
        .reduce("", String::concat);
  }
}
