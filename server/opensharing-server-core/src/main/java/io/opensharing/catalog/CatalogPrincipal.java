package io.opensharing.catalog;

/**
 * Principal returned by {@link CatalogConnector#authorize}.
 *
 * @param id durable catalog id, stored as share/recipient owner
 * @param name display name
 */
public record CatalogPrincipal(String id, String name) {}
