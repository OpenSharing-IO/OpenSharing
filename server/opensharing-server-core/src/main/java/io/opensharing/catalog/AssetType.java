package io.opensharing.catalog;

/** Kinds of catalog asset. A share holds only tables; schemas are their parents in the catalog. */
public enum AssetType {
  TABLE,
  SCHEMA
}
