package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.http.ApiException;
import org.junit.jupiter.api.Test;

class OwnershipTest {

  private static final UserContext ALICE = UserContext.fromUserIdAndName("alice-id", "alice");

  @Test
  void passesForTheOwner() {
    assertDoesNotThrow(() -> Ownership.check(ALICE, "alice-id", "share 's'"));
  }

  @Test
  void deniesAnotherUser() {
    ApiException e =
        assertThrows(ApiException.class, () -> Ownership.check(ALICE, "bob-id", "share 's'"));

    assertEquals(403, e.getStatus());
    assertEquals("user 'alice' does not own share 's'", e.getMessage());
  }

  @Test
  void deniesWhenEitherIdIsMissing() {
    UserContext anonymous = UserContext.fromUserIdAndName(null, "anonymous");

    assertThrows(ApiException.class, () -> Ownership.check(ALICE, null, "share 's'"));
    assertThrows(ApiException.class, () -> Ownership.check(anonymous, "alice-id", "share 's'"));
    assertThrows(ApiException.class, () -> Ownership.check(anonymous, null, "share 's'"));
  }
}
