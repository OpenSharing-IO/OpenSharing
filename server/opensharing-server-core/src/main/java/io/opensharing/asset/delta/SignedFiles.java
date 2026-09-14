package io.opensharing.asset.delta;

import io.opensharing.asset.storage.HadoopStorage;
import io.opensharing.asset.storage.SignedUrl;
import io.opensharing.asset.storage.UrlSigners;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Turns a file the log named into something a recipient can fetch: a signed url, an id it can cache
 * against, and when the url stops working. Both response formats need exactly this and differ only
 * in how they say it.
 */
final class SignedFiles {

  private final UrlSigners signers;
  private final Duration urlTtl;

  SignedFiles(UrlSigners signers, Duration urlTtl) {
    this.signers = signers;
    this.urlTtl = urlTtl;
  }

  Signed sign(DeltaTable table, String path) {
    SignedUrl signed = signers.sign(path, table.credentials(), urlTtl);
    return new Signed(signed.url(), fileId(table, path), signed.expiration().toEpochMilli());
  }

  /** The vector's own file, signed like any other, or null when it is inlined in the action. */
  Signed signVector(DeltaTable table, DeltaSnapshot.DeletionVector vector) {
    if (vector == null || vector.isInline()) {
      return null;
    }
    return sign(table, vector.absolutePath());
  }

  record Signed(String url, String id, long expiration) {}

  /**
   * A file's id must be the same in every response so a client can cache bytes against it. It is
   * derived from the path relative to the table root, so moving a table keeps the ids it had.
   */
  private static String fileId(DeltaTable table, String path) {
    String root = HadoopStorage.path(table.resolved().storageLocation());
    String relative = path.startsWith(root) ? path.substring(root.length()) : path;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(relative.replaceFirst("^/", "").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required to derive file ids", e);
    }
  }
}
