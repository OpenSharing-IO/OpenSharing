package io.opensharing.catalog;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResolvedAssetTest {

  @Test
  void onlyTablesHaveAFormat() {
    assertNull(ResolvedAsset.builder(AssetType.SCHEMA, "main.sales").build().dataSourceFormat());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResolvedAsset.builder(AssetType.SCHEMA, "main.sales")
                .dataSourceFormat(DataSourceFormat.DELTA)
                .build());
  }
}
