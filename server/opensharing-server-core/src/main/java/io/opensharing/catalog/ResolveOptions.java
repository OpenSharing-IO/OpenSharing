package io.opensharing.catalog;

/**
 * Options for {@link CatalogConnector#resolveAsset}.
 *
 * @param readCredentials whether to include read credentials for an Iceberg table's storage
 *     location in its {@link TableProperties}
 */
public record ResolveOptions(boolean readCredentials) {

  /** No read credentials. */
  public static final ResolveOptions DEFAULT = new ResolveOptions(false);

  /** Read credentials for an Iceberg table's storage location. */
  public static final ResolveOptions WITH_READ_CREDENTIALS = new ResolveOptions(true);
}
