package io.opensharing.catalog;

import java.util.List;

/** Minimal catalog for embedded-mode tests in the core module. */
public final class StubCatalogConnector implements CatalogConnector {

  public static final StubCatalogConnector INSTANCE = new StubCatalogConnector();

  private StubCatalogConnector() {}

  @Override
  public String name() {
    return "stub";
  }

  @Override
  public ResolvedAsset resolveAsset(AssetLookup lookup, CatalogCaller caller) {
    throw new UnsupportedOperationException("stub catalog");
  }

  @Override
  public List<StorageCredentials> getStorageCredentials(
      CredentialRequest request, CatalogCaller caller) {
    return List.of();
  }
}
