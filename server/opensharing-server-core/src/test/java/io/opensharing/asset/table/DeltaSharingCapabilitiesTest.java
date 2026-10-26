package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.http.ApiException;
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
  void rejectsUnknownFormatsKeysAndMalformedHeader() {
    assertThrows(ApiException.class, () -> choose("responseformat=json"));
    assertThrows(ApiException.class, () -> choose("responseformat="));
    assertThrows(ApiException.class, () -> choose("not-a-capability"));
    assertThrows(ApiException.class, () -> choose("unknown=true"));
    assertEquals(ResponseFormat.PARQUET, choose("asyncquery=true"));
    assertEquals(ResponseFormat.DELTA, choose("responseformat=delta;asyncquery=true"));
    assertEquals(ResponseFormat.PARQUET, choose("readerfeatures=notAFeature"));
  }

  private static ResponseFormat choose(String header) {
    return DeltaSharingCapabilities.choose(header, 1);
  }
}
