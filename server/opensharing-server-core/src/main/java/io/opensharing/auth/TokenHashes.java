package io.opensharing.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex of a bearer token. The plaintext is never persisted. */
public final class TokenHashes {

  private TokenHashes() {}

  /** Lowercase hex, 64 characters. */
  public static String sha256(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // Every Java runtime must provide SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
