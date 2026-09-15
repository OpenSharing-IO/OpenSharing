package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SecretsTest {

  private static final int TOKEN_BYTES = 32;
  private static final int RANDOM_PART_LENGTH = (TOKEN_BYTES * 8 + 5) / 6;
  private static final Pattern URL_SAFE_BASE64 = Pattern.compile("^[A-Za-z0-9_-]+$");

  @Test
  void hashesDeterministically() {
    assertEquals(Secrets.sha256("hello"), Secrets.sha256("hello"));
    assertNotEquals(Secrets.sha256("hello"), Secrets.sha256("world"));
  }

  @Test
  void mintsPrefixedTokens() {
    String token = Secrets.newToken();
    assertTrue(token.startsWith(Secrets.TOKEN_PREFIX));
    assertEquals(Secrets.TOKEN_PREFIX.length() + RANDOM_PART_LENGTH, token.length());

    String randomPart = token.substring(Secrets.TOKEN_PREFIX.length());
    assertTrue(URL_SAFE_BASE64.matcher(randomPart).matches());
    assertEquals(TOKEN_BYTES, Base64.getUrlDecoder().decode(randomPart).length);
    assertNotEquals(token, Secrets.newToken());
  }

  @Test
  void mintsActivationNonces() {
    String nonce = Secrets.newActivationNonce();
    assertEquals(RANDOM_PART_LENGTH, nonce.length());
    assertTrue(URL_SAFE_BASE64.matcher(nonce).matches());
    assertEquals(TOKEN_BYTES, Base64.getUrlDecoder().decode(nonce).length);
    assertNotEquals(nonce, Secrets.newActivationNonce());
  }
}
