package io.opensharing.catalog;

/** Kinds of catalog asset. A share holds only tables; schemas are the catalog's parents of them. */
public enum AssetType {
  TABLE,
  SCHEMA
}
