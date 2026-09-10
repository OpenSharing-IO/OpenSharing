package io.opensharing.asset;

/**
 * Whether a shared object can still be served. An object is ACTIVE when it is added, and moves off
 * ACTIVE when the catalog later stops resolving it. Only ACTIVE objects appear to recipients.
 */
public enum SharedObjectStatus {
  ACTIVE,
  /** The catalog refuses to resolve the object for the sharing server. */
  PERMISSION_DENIED,
  /** The object was dropped or renamed in the catalog after it was shared. */
  SOURCE_NOT_FOUND,
  /**
   * The object still exists but is no longer something this server can share — a table replaced by a
   * view, or recreated in a format it does not serve. Shareable when it was added, so this is the
   * catalog having changed underneath rather than a mistake anyone made here.
   */
  SOURCE_NOT_SHAREABLE
}
