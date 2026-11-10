package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import org.junit.jupiter.api.Test;

class OwnershipTest {

  private static final UserContext ALICE = UserContext.fromUserIdAndName("alice-id", "alice");
  private static final UserContext ADMIN = new UserContext("admin-id", null, "admin", true);

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

  @Test
  void adminTransferPasses() {
    assertDoesNotThrow(
        () -> Ownership.checkAdminTransferOwner(ADMIN, "share 's'", "bob-id", false));
  }

  @Test
  void transferDeniedForNonAdmin() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> Ownership.checkAdminTransferOwner(ALICE, "share 's'", "bob-id", false));

    assertEquals(403, e.getStatus());
    assertEquals(ErrorCodes.PERMISSION_DENIED, e.getErrorCode());
  }

  @Test
  void transferWithOtherChangesRejectedForAdminAndNonAdmin() {
    for (UserContext user : new UserContext[] {ADMIN, ALICE}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> Ownership.checkAdminTransferOwner(user, "share 's'", "bob-id", true));

      assertEquals(400, e.getStatus());
      assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, e.getErrorCode());
      assertEquals("ownerId must be changed on its own, without other updates", e.getMessage());
    }
  }

  @Test
  void transferToBlankOwnerRejected() {
    for (String newOwnerId : new String[] {null, "", " "}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> Ownership.checkAdminTransferOwner(ADMIN, "share 's'", newOwnerId, false));

      assertEquals(400, e.getStatus());
      assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, e.getErrorCode());
      assertEquals("ownerId must not be blank", e.getMessage());
    }
  }
}
