package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserContextTest {

  @Test
  void toStringHidesBearerToken() {
    UserContext user = new UserContext("alice-id", "secret-token", "alice");

    assertEquals(
        "UserContext[userId=alice-id, userAuthToken=***, userName=alice, admin=false]",
        user.toString());
    assertEquals(
        "AuthContext[serverId=null, user=UserContext[userId=alice-id, userAuthToken=***,"
            + " userName=alice, admin=false]]",
        AuthContext.of(user).toString());
  }

  @Test
  void usersAreNotAdminsUnlessTheCatalogSaysSo() {
    assertFalse(new UserContext("alice-id", null, "alice").admin());
    assertFalse(UserContext.fromUserIdAndName("alice-id", "alice").admin());
    assertTrue(UserContext.fromUserIdAndName("alice-id", "alice").asAdmin().admin());
    assertTrue(new UserContext("alice-id", null, "alice", true).asAdmin().admin());
  }
}
