package io.opensharing.catalog;

/**
 * Who a bearer token belongs to, as the catalog itself answers it — see {@link
 * CatalogConnector#authorize}.
 *
 * @param id the catalog's own durable id for this principal, which is what this server keeps
 *     against a share or recipient it creates, rather than the name, so a principal renamed at the
 *     catalog is not read back as a different owner
 * @param name a display name for the principal, for showing back to an administrator — never a
 *     credential, and never enough on its own to authenticate as them again
 */
public record CatalogPrincipal(String id, String name) {}
