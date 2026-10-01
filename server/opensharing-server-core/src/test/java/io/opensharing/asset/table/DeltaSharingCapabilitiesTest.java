package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
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
}
