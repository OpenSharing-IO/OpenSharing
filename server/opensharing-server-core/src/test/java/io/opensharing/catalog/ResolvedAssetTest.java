package io.opensharing.catalog;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResolvedAssetTest {

  @Test
  void onlyTablesHaveTableProperties() {
    assertNull(
        ResolvedAsset.builder(AssetType.SCHEMA, "main.sales").build().additionalProperties());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResolvedAsset.builder(AssetType.SCHEMA, "main.sales")
                .additionalProperties(new TableProperties(TableSubtype.MANAGED, DataSourceFormat.DELTA))
                .build());
  }
}
