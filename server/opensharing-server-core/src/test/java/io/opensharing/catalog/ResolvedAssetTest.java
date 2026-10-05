package io.opensharing.catalog;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
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
                .additionalProperties(new TableProperties(DataSourceFormat.DELTA, Map.of()))
                .build());
  }
}
