package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserContextTest {

  @Test
  void toStringHidesBearerToken() {
    UserContext user = new UserContext("alice-id", "secret-token", "alice");

    assertEquals(
        "UserContext[userId=alice-id, userAuthToken=***, userName=alice]", user.toString());
    assertEquals(
        "AuthContext[serverId=null, user=UserContext[userId=alice-id, userAuthToken=***,"
            + " userName=alice]]",
        AuthContext.of(user).toString());
  }
}
