package io.opensharing.catalog;

/**
 * Asset types a share can hold. {@link #TABLE} and {@link #SCHEMA} are accepted; the rest are here
 * because they are part of the model and are rejected explicitly rather than silently absent.
 *
 * <p>{@link #SCHEMA} is the odd one, in that sharing it shares nothing directly: it stands for the
 * tables the catalog holds in it, which is why it needs a catalog that can be asked what those are.
 */
public enum AssetType {
  TABLE,
  VOLUME,
  MODEL,
  SKILL,
  SCHEMA
}
