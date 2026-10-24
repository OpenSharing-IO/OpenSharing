package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.delta.kernel.internal.actions.Protocol;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.http.ApiException;
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

  @Test
  void queryRequiresReaderFeaturesOnlyForDeltaAdvancedTables() {
    Protocol basic = new Protocol(1, 2);
    Protocol columnMapping = new Protocol(2, 5);
    Protocol advanced =
        new Protocol(3, 7, Set.of("columnMapping", "deletionVectors"), Set.of("columnMapping"));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                null, ResponseFormat.PARQUET, basic));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=delta", ResponseFormat.DELTA, basic));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=delta;readerfeatures=columnmapping,deletionvectors,newfeature",
                ResponseFormat.DELTA,
                advanced));
    assertDoesNotThrow(
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=delta", ResponseFormat.DELTA, columnMapping));
    assertThrows(
        ApiException.class,
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=parquet", ResponseFormat.PARQUET, advanced));
    assertThrows(
        ApiException.class,
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=parquet", ResponseFormat.PARQUET, columnMapping));
    assertThrows(
        ApiException.class,
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=delta", ResponseFormat.DELTA, advanced));
    assertThrows(
        ApiException.class,
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=delta;readerfeatures=columnmapping",
                ResponseFormat.DELTA,
                advanced));
    assertThrows(
        ApiException.class,
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=delta;readerfeatures=notAFeature",
                ResponseFormat.DELTA,
                advanced));
  }
}
