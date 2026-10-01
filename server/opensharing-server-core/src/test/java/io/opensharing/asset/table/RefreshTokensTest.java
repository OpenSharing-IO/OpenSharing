package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.http.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

  @Test
  void roundTripsTableIdAndVersion() {
    String token = RefreshTokens.encode("main.sales.orders", 7, Instant.now().plusSeconds(60));
    assertEquals(7, RefreshTokens.versionOf(token, "main.sales.orders"));
  }

  @Test
  void rejectsExpiredAndMismatchedTokens() {
    String expired = RefreshTokens.encode("main.sales.orders", 1, Instant.now().minusSeconds(1));
    assertThrows(ApiException.class, () -> RefreshTokens.versionOf(expired, "main.sales.orders"));
    String token = RefreshTokens.encode("main.sales.orders", 1, Instant.now().plusSeconds(60));
    assertThrows(ApiException.class, () -> RefreshTokens.versionOf(token, "main.hr.salaries"));
    assertThrows(ApiException.class, () -> RefreshTokens.versionOf("not-a-token", "main.sales.orders"));
  }
}
