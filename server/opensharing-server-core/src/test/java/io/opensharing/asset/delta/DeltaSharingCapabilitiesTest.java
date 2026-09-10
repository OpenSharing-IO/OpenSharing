package io.opensharing.asset.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opensharing.http.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The negotiation between what a client says it can read and what a table needs, which is the one
 * place a read can be refused for reasons that have nothing to do with permission.
 */
class DeltaSharingCapabilitiesTest {

  private static final DeltaSnapshot ORDINARY = table(1, 2, Map.of());

  /** A table that uses deletion vectors, and says so where it matters: in its own properties. */
  private static final DeltaSnapshot WITH_VECTORS =
      table(3, 7, Map.of("delta.enableDeletionVectors", "true"));

  /** The same protocol, with the feature switched off — an ordinary table to read. */
  private static final DeltaSnapshot NAMING_VECTORS =
      table(3, 7, Map.of("delta.enableDeletionVectors", "false"));

  @Test
  void readsTheHeaderWhicheverWayItIsWritten() {
    DeltaSharingCapabilities capabilities =
        DeltaSharingCapabilities.parse(
            "ResponseFormat=Delta,Parquet;ReaderFeatures=DeletionVectors;"
                + "includeEndStreamAction=true;somethingNewer=1");

    assertEquals(Set.of("delta", "parquet"), capabilities.responseFormats());
    assertEquals(Set.of("deletionvectors"), capabilities.readerFeatures());
    assertTrue(capabilities.includeEndStreamAction());
  }

  @Test
  void answersInParquetToAClientThatSaidNothing() {
    assertEquals(
        DeltaResponseFormat.PARQUET, DeltaSharingCapabilities.defaults().chooseFormat(ORDINARY));
  }

  @Test
  void answersInTheOneFormatAClientNamed() {
    assertEquals(
        DeltaResponseFormat.DELTA,
        DeltaSharingCapabilities.parse("responseformat=delta").chooseFormat(ORDINARY));
  }

  @Test
  void prefersParquetForAClientThatReadsEitherAndATableThatNeedsNothingMore() {
    assertEquals(
        DeltaResponseFormat.PARQUET,
        DeltaSharingCapabilities.parse("responseformat=delta,parquet").chooseFormat(ORDINARY));
  }

  @Test
  void movesToDeltaForATableParquetCannotDescribe() {
    assertEquals(
        DeltaResponseFormat.DELTA,
        DeltaSharingCapabilities.parse(
                "responseformat=delta,parquet;readerfeatures=deletionvectors")
            .chooseFormat(WITH_VECTORS));
  }

  @Test
  void servesATableThatOnlyNamesAFeatureAsTheOrdinaryTableItIs() {
    DeltaSharingCapabilities parquetOnly = DeltaSharingCapabilities.defaults();

    assertEquals(
        DeltaResponseFormat.PARQUET,
        parquetOnly.chooseFormat(NAMING_VECTORS),
        "the reader version alone is not what a table needs; its properties are");
    assertEquals(
        1,
        parquetOnly.reportedProtocol(NAMING_VECTORS.protocol()).minReaderVersion(),
        "and a client that reads no features is told a version it can act on");
  }

  @Test
  void tellsAClientThatReadsFeaturesWhatTheLogSays() {
    DeltaSnapshot.Protocol reported =
        DeltaSharingCapabilities.parse("responseformat=delta;readerfeatures=deletionvectors")
            .reportedProtocol(WITH_VECTORS.protocol());

    assertEquals(3, reported.minReaderVersion());
    assertEquals(List.of("deletionVectors"), reported.readerFeatures());
  }

  @Test
  void refusesParquetForATableParquetCannotDescribe() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                DeltaSharingCapabilities.parse(
                        "responseformat=parquet;readerfeatures=deletionvectors")
                    .chooseFormat(WITH_VECTORS));

    assertEquals(501, e.getStatus().value());
    assertTrue(e.getMessage().contains("responseformat=delta"), e.getMessage());
  }

  @Test
  void refusesATableWhoseFeaturesTheClientNeverClaimed() {
    for (String header : List.of("responseformat=delta", "responseformat=parquet")) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> DeltaSharingCapabilities.parse(header).chooseFormat(WITH_VECTORS));

      assertEquals(400, e.getStatus().value(), header);
      assertTrue(e.getMessage().contains("deletionVectors"), e.getMessage());
    }
  }

  @Test
  void refusesARequestThatAcceptsNeitherFormat() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> DeltaSharingCapabilities.parse("responseformat=avro").chooseFormat(ORDINARY));

    assertEquals(400, e.getStatus().value());
  }

  @Test
  void saysWhichFormatItAnsweredInAndWhetherTheStreamWillBeClosed() {
    assertEquals(
        "responseformat=delta",
        DeltaSharingCapabilities.parse("responseformat=delta")
            .responseHeaderValue(DeltaResponseFormat.DELTA));
    assertEquals(
        "responseformat=parquet;includeendstreamaction=true",
        DeltaSharingCapabilities.parse("includeEndStreamAction=true")
            .responseHeaderValue(DeltaResponseFormat.PARQUET));
  }

  private static DeltaSnapshot table(
      int minReaderVersion, int minWriterVersion, Map<String, String> configuration) {
    List<String> features = minReaderVersion > 1 ? List.of("deletionVectors") : List.of();
    return new DeltaSnapshot(
        0,
        0,
        new DeltaSnapshot.Protocol(minReaderVersion, minWriterVersion, features, features),
        new DeltaSnapshot.Metadata(
            "id", null, null, "parquet", Map.of(), "{}", List.of(), configuration, null),
        List.of());
  }
}
