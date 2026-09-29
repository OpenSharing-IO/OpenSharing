package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.http.ApiException;
import org.junit.jupiter.api.Test;

class FileIdHashTest {

  private static final String PATH = "date=2021-04-28/part.parquet";

  @Test
  void parsesParquetAndDeltaAndRejectsUnknown() {
    assertNull(FileIdHash.parse(null));
    assertNull(FileIdHash.parse(""));
    assertEquals("parquet", FileIdHash.parse("PARQUET"));
    assertEquals("delta", FileIdHash.parse(" delta "));
    assertThrows(ApiException.class, () -> FileIdHash.parse("md5"));
  }

  @Test
  void defaultsFollowResponseFormatAndExplicitHeaderWins() {
    String md5 = FileIdHash.hash(PATH, "parquet", ResponseFormat.PARQUET);
    String sha = FileIdHash.hash(PATH, "delta", ResponseFormat.DELTA);
    assertEquals(32, md5.length());
    assertEquals(64, sha.length());
    assertEquals(md5, FileIdHash.hash(PATH, null, ResponseFormat.PARQUET));
    assertEquals(sha, FileIdHash.hash(PATH, null, ResponseFormat.DELTA));
    assertEquals(md5, FileIdHash.hash(PATH, "parquet", ResponseFormat.DELTA));
    assertEquals(sha, FileIdHash.hash(PATH, "delta", ResponseFormat.PARQUET));
  }
}
