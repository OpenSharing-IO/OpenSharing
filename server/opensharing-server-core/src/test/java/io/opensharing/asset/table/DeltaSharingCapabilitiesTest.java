package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.delta.kernel.internal.actions.Protocol;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeltaSharingCapabilitiesTest {

  @Test
  void defaultsToParquetWithoutAHeader() {
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose(null, 1));
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose("", 1));
    assertEquals(
        "responseformat=parquet", DeltaSharingCapabilities.responded(ResponseFormat.PARQUET));
    assertEquals("responseformat=delta", DeltaSharingCapabilities.responded(ResponseFormat.DELTA));
    assertEquals(
        "responseformat=parquet;includeendstreamaction=true",
        DeltaSharingCapabilities.responded(ResponseFormat.PARQUET, true));
    assertFalse(DeltaSharingCapabilities.includeEndStreamAction(null));
    assertTrue(
        DeltaSharingCapabilities.includeEndStreamAction("includeendstreamaction=true"));
  }

  @Test
  void answersInParquetOnlyForReaderVersion1TablesWhenTheClientAcceptsIt() {
    assertEquals(ResponseFormat.DELTA, DeltaSharingCapabilities.choose("responseformat=delta", 1));
    assertEquals(
        ResponseFormat.PARQUET, DeltaSharingCapabilities.choose("responseformat=parquet", 1));
    assertEquals(
        ResponseFormat.PARQUET,
        DeltaSharingCapabilities.choose("responseFormat=delta,parquet", 1));
    assertEquals(
        ResponseFormat.DELTA,
        DeltaSharingCapabilities.choose(
            "RESPONSEFORMAT=PARQUET,DELTA;readerfeatures=deletionvectors", 3));
    assertEquals(
        ResponseFormat.DELTA, DeltaSharingCapabilities.choose("responseformat=parquet", 2));
    assertEquals(ResponseFormat.DELTA, DeltaSharingCapabilities.choose(null, 3));
  }

  @Test
  void rejectsUnknownFormatsAndIgnoresUnknownKeys() {
    assertThrows(ApiException.class, () -> choose("responseformat=json"));
    assertThrows(ApiException.class, () -> choose("responseformat="));
    assertThrows(ApiException.class, () -> choose("includeendstreamaction=yes"));
    assertEquals(ResponseFormat.PARQUET, choose("not-a-capability"));
    assertEquals(ResponseFormat.PARQUET, choose("unknown=true"));
    assertEquals(ResponseFormat.DELTA, choose("unknown=true;responseformat=delta;=x"));
    assertEquals(ResponseFormat.PARQUET, choose("asyncquery=true"));
    assertEquals(ResponseFormat.DELTA, choose("responseformat=delta;asyncquery=true"));
    assertEquals(ResponseFormat.PARQUET, choose("readerfeatures=notAFeature"));
  }

  private static ResponseFormat choose(String header) {
    return DeltaSharingCapabilities.choose(header, 1);
  }

  private static final String REMEDY =
      " Query with responseFormat=delta and advertise the features in delta-sharing-capabilities,"
          + " or ask your data provider to disable them.";

  private static void require(String header, ResponseFormat format, Protocol protocol) {
    DeltaSharingCapabilities.requireReaderFeatures(
        DeltaSharingCapabilities.declared(header), format, protocol, 7);
  }

  private static void assertUnsupported(
      String message, String header, ResponseFormat format, Protocol protocol) {
    ApiException error =
        assertThrows(ApiException.class, () -> require(header, format, protocol));
    assertEquals(400, error.getStatus());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, error.getErrorCode());
    assertEquals(message, error.getMessage());
  }

  @Test
  void queryRequiresReaderFeaturesOnlyFromClientsThatAcceptDelta() {
    Protocol basic = new Protocol(1, 2);
    Protocol columnMapping = new Protocol(2, 5);
    Protocol advanced =
        new Protocol(3, 7, Set.of("columnMapping", "deletionVectors"), Set.of("columnMapping"));
    assertDoesNotThrow(() -> require(null, ResponseFormat.PARQUET, basic));
    assertDoesNotThrow(() -> require("responseformat=delta", ResponseFormat.DELTA, basic));
    assertDoesNotThrow(
        () ->
            require(
                "responseformat=delta;readerfeatures=columnmapping,deletionvectors,newfeature",
                ResponseFormat.DELTA,
                advanced));
    assertDoesNotThrow(
        () -> require("responseformat=delta", ResponseFormat.DELTA, columnMapping));
    assertDoesNotThrow(() -> require("responseformat=parquet", ResponseFormat.DELTA, advanced));
    assertDoesNotThrow(() -> require(null, ResponseFormat.DELTA, advanced));
    // Deletion vectors stay in a protocol after they are turned off, so their property decides.
    assertDoesNotThrow(
        () ->
            require(
                "responseformat=delta;readerfeatures=columnmapping",
                ResponseFormat.DELTA,
                advanced));
  }

  @Test
  void namesTheReaderFeaturesTheClientDidNotAdvertise() {
    Protocol columnMapping = new Protocol(2, 5);
    Protocol advanced =
        new Protocol(
            3,
            7,
            Set.of("deletionVectors", "timestampNtz", "columnMapping"),
            Set.of("columnMapping"));
    // Every feature, once and in a fixed order, and the table version they were found in.
    assertUnsupported(
        "Table features columnmapping, timestampntz are found in table version: 7." + REMEDY,
        "responseformat=delta",
        ResponseFormat.DELTA,
        advanced);
    assertUnsupported(
        "Table features timestampntz are found in table version: 7." + REMEDY,
        "responseformat=delta;readerfeatures=notAFeature, COLUMNMAPPING",
        ResponseFormat.DELTA,
        advanced);
    // A client that accepts only parquet cannot read a table with reader features.
    assertUnsupported(
        "Table features columnmapping, deletionvectors, timestampntz are found in table version:"
            + " 7."
            + REMEDY,
        "responseformat=parquet",
        ResponseFormat.PARQUET,
        advanced);
    assertUnsupported(
        "Table features reader version 2 are found in table version: 7." + REMEDY,
        "responseformat=parquet",
        ResponseFormat.PARQUET,
        columnMapping);
  }

  @Test
  void aTablePropertyThatEnablesAFeatureNeedsTheFeatureAdvertised() {
    Map<String, String> enabled =
        Map.of(
            "delta.enableDeletionVectors", "true",
            "delta.columnMapping.mode", "name",
            "delta.appendOnly", "true");
    ApiException error =
        assertThrows(
            ApiException.class,
            () ->
                DeltaSharingCapabilities.requireSupportedProperties(
                    DeltaSharingCapabilities.declared("responseformat=delta"), enabled, 3));
    assertEquals(
        "Table features delta.columnMapping.mode, delta.enableDeletionVectors are found in table"
            + " version: 3."
            + REMEDY,
        error.getMessage());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, error.getErrorCode());

    // Advertised, off, absent and unrelated properties all pass.
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireSupportedProperties(
                DeltaSharingCapabilities.declared(
                    "responseformat=delta;readerfeatures=ColumnMapping,deletionVectors"),
                enabled,
                3));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireSupportedProperties(
                DeltaSharingCapabilities.declared("responseformat=delta"),
                Map.of(
                    "delta.enableDeletionVectors", " false ",
                    "delta.columnMapping.mode", "none",
                    "delta.appendOnly", "true"),
                3));
    // Delta reads the off values in any case.
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireSupportedProperties(
                DeltaSharingCapabilities.declared("responseformat=delta"),
                Map.of(
                    "delta.enableDeletionVectors", "False",
                    "delta.columnMapping.mode", "NONE"),
                3));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireSupportedProperties(
                DeltaSharingCapabilities.declared(null), Map.of(), 3));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireSupportedProperties(
                DeltaSharingCapabilities.declared(null), null, 3));
  }
}
