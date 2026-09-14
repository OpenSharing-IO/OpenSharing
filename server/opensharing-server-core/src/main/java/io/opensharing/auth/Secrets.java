package io.opensharing.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Generates bearer tokens and activation nonces, and hashes them for storage. */
public final class Secrets {

  private static final String TOKEN_PREFIX = "os_";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;

  private Secrets() {}

  /** A new recipient bearer token. Returned to the caller once and never stored in clear text. */
  public static String newToken() {
    return TOKEN_PREFIX + randomUrlSafe();
  }

  /** A new single-use activation nonce. */
  public static String newActivationNonce() {
    return randomUrlSafe();
  }

  /** Lowercase hex SHA-256, the form persisted for tokens and nonces. */
  public static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }

  /** Constant-time comparison, for secrets compared outside the database. */
  public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String randomUrlSafe() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
