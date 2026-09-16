package io.opensharing.auth;

/**
 * Who a catalog or provider request is for.
 *
 * @param id durable catalog id, stored as share/recipient owner
 * @param name display name
 */
public record UserContext(String id, String name) {}
