package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.delta.kernel.exceptions.InvalidTableException;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.exceptions.UnsupportedProtocolVersionException;
import io.delta.kernel.exceptions.UnsupportedTableFeatureException;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How Kernel's failures reach the recipient: by whose state they report, and in what words. */
class DeltaKernelErrorsTest {

  private static final String TABLE = "file:/tables/my table/";
  private static final String UNREADABLE =
      "Unable to access table share.lake.t: something is wrong with the table. Please contact"
          + " your data provider.";

  private static void assertApiError(int status, String code, String message, Throwable actual) {
    ApiException error = assertInstanceOf(ApiException.class, actual);
    assertEquals(status, error.getStatus());
    assertEquals(code, error.getErrorCode());
    assertEquals(message, error.getMessage());
  }

  private static void assertUnreadable(RuntimeException failed) {
    SourceTableException source =
        assertInstanceOf(SourceTableException.class, DeltaKernel.classify(failed, TABLE, null));
    assertApiError(400, ErrorCodes.BAD_REQUEST, UNREADABLE, source.forRecipient("share.lake.t"));
  }

  @Test
  void aMissingLogIsTheSourceTablesState() {
    assertUnreadable(new TableNotFoundException(TABLE));
  }

  @Test
  void missingOrDeniedFilesAreTheSourceTablesState() {
    assertUnreadable(new KernelEngineException("read failed", new FileNotFoundException("x")));
    assertUnreadable(
        new KernelEngineException(
            "read failed", new IOException("wrapped", new AccessDeniedException("x"))));
  }

  @Test
  void aLogKernelRejectsIsReportedInKernelsWordsWithTheProviderToContact() {
    assertApiError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Invalid table found at the table: Cannot compute snapshot. Missing delta file version 0."
            + " Please contact your data provider.",
        DeltaKernel.classify(
            new InvalidTableException(
                TABLE, "Cannot compute snapshot. Missing delta file version 0."),
            TABLE,
            null));
  }

  @Test
  void aVersionTheTableDoesNotHaveIsReportedInKernelsWords() {
    assertApiError(
        400,
        ErrorCodes.BAD_REQUEST,
        "Cannot load table version 9 as the latest version is 2.",
        DeltaKernel.classify(
            new KernelException("Cannot load table version 9 as the latest version is 2."),
            TABLE,
            null));
    assertApiError(
        400,
        ErrorCodes.BAD_REQUEST,
        "The provided timestamp 1 is too early.",
        DeltaKernel.classify(
            new IllegalArgumentException("The provided timestamp 1 is too early."), TABLE, null));
  }

  @Test
  void unsupportedFeaturesAreNamedInAFixedOrder() {
    String remedy = "This server cannot read them. Please contact your data provider.";
    assertApiError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Table features futurefeature, otherfeature are found in table version: 3. " + remedy,
        DeltaKernel.classify(
            new UnsupportedTableFeatureException(
                TABLE, Set.of("otherFeature", "futureFeature"), "read"),
            TABLE,
            3L));
    assertApiError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Table features futurefeature are found in table. " + remedy,
        DeltaKernel.classify(
            new UnsupportedTableFeatureException(TABLE, Set.of("futureFeature"), "read"),
            TABLE,
            null));
  }

  @Test
  void anUnsupportedReaderVersionIsNamedAsAFeature() {
    assertApiError(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "Table features reader version 9 are found in table. This server cannot read them. Please"
            + " contact your data provider.",
        DeltaKernel.classify(
            new UnsupportedProtocolVersionException(
                TABLE, 9, 9, UnsupportedProtocolVersionException.ProtocolVersionType.READER),
            TABLE,
            null));
  }

  @Test
  void stripsOtherLocationsWithASchemeFromKernelWords() {
    assertEquals(
        "Invalid table found at the table: Missing the table, the table.",
        DeltaKernel.kernelError(
                new KernelException(
                    "Invalid table found at s3a://bucket/other/table: Missing"
                        + " s3://bucket/%7Ealt/table/_delta_log/0.json, file:///x/y."),
                TABLE,
                "")
            .getMessage());
  }

  @Test
  void stripsTheTablePathInEveryStorageScheme() {
    for (String table :
        new String[] {
          "s3://bucket/my tables/t/",
          "s3a://bucket/my tables/t",
          "abfss://container@account.dfs.core.windows.net/my tables/t",
          "gs://bucket/my tables/t/",
          "file:/tmp/my tables/t",
        }) {
      String escaped = table.replace(" ", "%20");
      String plain = table.replaceAll("/$", "");
      String encoded = java.net.URLEncoder.encode(plain, java.nio.charset.StandardCharsets.UTF_8);
      for (String spelling :
          new String[] {plain, plain + "/", escaped, escaped.replace(":", "%3A"), encoded}) {
        ApiException error =
            DeltaKernel.kernelError(
                new KernelException(
                    "Invalid table found at " + spelling + ": no file " + spelling + "/_delta_log"),
                table,
                "");
        assertFalse(
            error.getMessage().matches("(?s).*(bucket|container|account|/tmp|my.tables).*"),
            table + " as " + spelling + " -> " + error.getMessage());
      }
    }
  }

  @Test
  void aStorageFailureOfItsOwnStaysAServerError() {
    KernelEngineException storage =
        new KernelEngineException("read failed", new IOException("timeout"));
    assertSame(storage, DeltaKernel.classify(storage, TABLE, null));
  }

  @Test
  void aCauseChainThatLoopsEndsTheSearchForMissingFiles() {
    IOException looping = new IOException("a");
    IOException other = new IOException("b", looping);
    looping.initCause(other);
    assertSame(
        looping,
        DeltaKernel.classify(new KernelEngineException("read failed", looping), TABLE, null)
            .getCause());
  }

  @Test
  void stripsEverySpellingOfTheTablePathFromKernelWords() {
    for (String message :
        new String[] {
          "file:/tables/my table/: The timestamp is early.",
          "/tables/my table: The timestamp is early.",
          "file:/tables/my%20table/: The timestamp is early.",
          "The timestamp is early.",
        }) {
      assertEquals(
          "The timestamp is early.",
          DeltaKernel.kernelError(new KernelException(message), TABLE, "").getMessage(),
          message);
    }
    // The catalog may spell a local location with three slashes where Kernel normalizes to one.
    assertEquals(
        "Missing the table/_delta_log",
        DeltaKernel.kernelError(
                new KernelException("Missing file:/tables/my table/_delta_log"),
                "file:///tables/my table/",
                "")
            .getMessage());
    ApiException inline =
        DeltaKernel.kernelError(
            new KernelException(
                "No commit at /tables/my table/_delta_log/0.json or"
                    + " file:/tables/my%20table/_delta_log/1.json"),
            TABLE,
            "");
    assertEquals(
        "No commit at the table/_delta_log/0.json or the table/_delta_log/1.json",
        inline.getMessage());
    assertFalse(inline.getMessage().contains("tables"));
  }
}
