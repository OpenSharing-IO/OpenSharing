package io.opensharing.catalog;

import java.util.List;

/**
 * One page of assets returned by {@link CatalogConnector#listChildren}.
 *
 * @param assets the assets on this page; a page before the last may hold fewer than requested, or
 *     none
 * @param nextPageToken opaque token for the next page; null on the last page
 */
public record AssetPage(List<ResolvedAsset> assets, String nextPageToken) {

  public AssetPage {
    assets = List.copyOf(assets);
  }
}
