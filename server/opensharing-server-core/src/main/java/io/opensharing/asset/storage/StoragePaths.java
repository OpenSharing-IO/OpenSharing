package io.opensharing.asset.storage;

/**
 * Whether a path is one of a shared table's own.
 *
 * <p>Every grant this server hands out is scoped to a location, so a path outside that location is
 * one the provider never shared — and the two places that can be handed such a path, a Delta log
 * naming a file and a catalog naming an Iceberg metadata document, are equally able to name one by
 * accident or on purpose. They ask the same question here so that neither is stricter than the
 * other, and each says its own refusal, which is the only part that differs.
 */
public final class StoragePaths {

  private StoragePaths() {}

  /**
   * Whether a location is on the filesystem the server runs on, written either as a bare path or
   * with a {@code file:} scheme.
   *
   * <p>Storage like this is reached without a credential, and a catalog is entitled to say so: Unity
   * Catalog answers a vend for a local table with every credential block empty, which means there is
   * nothing to hand out rather than that anything went wrong — its own reader takes the same answer
   * and reads the table. Every other scheme names a cloud, where an empty answer is a catalog that
   * has not been told about the bucket, and saying nothing was needed would turn a misconfiguration
   * into a read that fails much further down.
   */
  public static boolean isLocal(String location) {
    if (location == null || location.isBlank()) {
      return false;
    }
    int scheme = location.indexOf(':');
    return scheme < 0 || location.regionMatches(true, 0, "file:", 0, 5);
  }

  /** Whether a path resolved against a root stays under it. */
  public static boolean isInside(String path, String root) {
    if (path == null || root == null || root.isBlank()) {
      return false;
    }
    String prefix = root.endsWith("/") ? root : root + "/";
    return path.startsWith(prefix) && !climbsOut(path);
  }

  /**
   * Whether a path steps back up out of wherever it starts. Only a whole segment counts: a file
   * named {@code v2..metadata.json} climbs nowhere, and refusing it would refuse a name storage
   * allows.
   */
  public static boolean climbsOut(String path) {
    for (String segment : path.split("/")) {
      if (segment.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
