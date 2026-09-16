package io.opensharing.auth;

import io.opensharing.catalog.CatalogConnector;

/**
 * Identity returned by {@link CatalogConnector#authorize}.
 *
 * @param id durable catalog id, stored as share/recipient owner
 * @param name display name
 */
public record UserContext(String id, String name) {}
