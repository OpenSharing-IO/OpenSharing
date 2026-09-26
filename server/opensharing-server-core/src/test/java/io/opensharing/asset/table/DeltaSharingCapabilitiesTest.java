package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import org.junit.jupiter.api.Test;

class DeltaSharingCapabilitiesTest {

  @Test
  void defaultsToParquetWithoutAHeader() {
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose(null, true));
    assertEquals(ResponseFormat.PARQUET, DeltaSharingCapabilities.choose("", false));
  }

  @Test
  void respectsASingleRequestedFormat() {
    assertEquals(
        ResponseFormat.DELTA,
        DeltaSharingCapabilities.choose("responseformat=delta", false));
    assertEquals(
        ResponseFormat.PARQUET,
        DeltaSharingCapabilities.choose("responseformat=parquet", true));
  }

  @Test
  void prefersDeltaWhenBothAreListedAndTheTableNeedsIt() {
    assertEquals(
        ResponseFormat.PARQUET,
        DeltaSharingCapabilities.choose("responseFormat=delta,parquet", false));
    assertEquals(
        ResponseFormat.DELTA,
        DeltaSharingCapabilities.choose(
            "RESPONSEFORMAT=DELTA,PARQUET;readerfeatures=deletionvectors", true));
  }
}
