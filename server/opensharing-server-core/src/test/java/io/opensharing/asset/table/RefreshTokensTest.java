package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.asset.table.RefreshTokens.RefreshToken;
import io.opensharing.http.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

  @Test
  void encodesAndDecodesRefreshToken() {
    RefreshToken token = new RefreshToken("orders-id", 7, 1000);
    assertEquals(token, RefreshTokens.decode(RefreshTokens.encode(token)));
  }

  @Test
  void roundTripsTableIdAndVersion() {
    String token = RefreshTokens.encode("orders-id", 7, Instant.now().plusSeconds(60));
    assertEquals(7, RefreshTokens.versionOf(token, "orders-id"));
  }

  @Test
  void rejectsExpiredAndMismatchedTokens() {
    String expired = RefreshTokens.encode("orders-id", 1, Instant.now().minusSeconds(1));
    assertThrows(ApiException.class, () -> RefreshTokens.versionOf(expired, "orders-id"));
    String token = RefreshTokens.encode("orders-id", 1, Instant.now().plusSeconds(60));
    assertThrows(ApiException.class, () -> RefreshTokens.versionOf(token, "salaries-id"));
    assertThrows(
        ApiException.class, () -> RefreshTokens.versionOf("not-a-token", "orders-id"));
  }
}
