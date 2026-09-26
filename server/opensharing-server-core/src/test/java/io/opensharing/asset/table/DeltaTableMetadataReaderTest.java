package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.opensharing.auth.AuthContext;
import io.opensharing.catalog.AssetType;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.TableFormat;
import io.opensharing.http.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DeltaTableMetadataReaderTest {

  @Test
  void rejectsVersionAndTimestampTogether() {
    DeltaTableMetadataReader reader = new DeltaTableMetadataReader(mock(DeltaKernel.class));
    ResolvedAsset table =
        ResolvedAsset.builder(AssetType.TABLE, "main.sales.orders")
            .format(TableFormat.DELTA)
            .storageLocation("s3://test/main.sales.orders/")
            .build();

    ApiException error =
        assertThrows(
            ApiException.class,
            () -> reader.read(table, 1L, Instant.parse("2022-01-01T00:00:00Z"), AuthContext.of(null)));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
  }
}
