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
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose(null));
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose(""));
    assertEquals("responseformat=parquet", DeltaSharingCapabilities.responded(null));
    assertEquals("responseformat=delta", DeltaSharingCapabilities.responded("responseformat=delta"));
  }

  @Test
  void respectsASingleRequestedFormat() {
    assertEquals(ResponseFormat.DELTA, DeltaSharingCapabilities.choose("responseformat=delta"));
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose("responseformat=parquet"));
  }

  @Test
  void prefersDeltaWhenBothAreListed() {
    assertEquals(
        ResponseFormat.DELTA, DeltaSharingCapabilities.choose("responseFormat=delta,parquet"));
    assertEquals(
        ResponseFormat.DELTA,
        DeltaSharingCapabilities.choose(
            "RESPONSEFORMAT=PARQUET,DELTA;readerfeatures=deletionvectors"));
  }

  @Test
  void rejectsUnknownFormatsKeysAndMalformedHeader() {
    assertThrows(ApiException.class, () -> DeltaSharingCapabilities.choose("responseformat=json"));
    assertThrows(ApiException.class, () -> DeltaSharingCapabilities.choose("responseformat="));
    assertThrows(ApiException.class, () -> DeltaSharingCapabilities.choose("not-a-capability"));
    assertThrows(ApiException.class, () -> DeltaSharingCapabilities.choose("unknown=true"));
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose("asyncquery=true"));
    assertEquals(
        ResponseFormat.DELTA,
        DeltaSharingCapabilities.choose("responseformat=delta;asyncquery=true"));
    assertEquals(
        ResponseFormat.PARQUET, DeltaSharingCapabilities.choose("readerfeatures=notAFeature"));
  }

  @Test
  void queryRequiresReaderFeaturesOnlyForDeltaAdvancedTables() {
    Protocol basic = new Protocol(1, 2);
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
                "responseformat=delta;readerfeatures=columnmapping,deletionvectors",
                ResponseFormat.DELTA,
                advanced));
    assertThrows(
        ApiException.class,
        () ->
            DeltaSharingCapabilities.requireReaderFeatures(
                "responseformat=parquet", ResponseFormat.PARQUET, advanced));
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
