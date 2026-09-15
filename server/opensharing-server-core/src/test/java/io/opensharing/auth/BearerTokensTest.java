package io.opensharing.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BearerTokensTest {

  @Test
  void extractsBearerTokenCaseInsensitively() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "bearer token-value");

    assertEquals("token-value", BearerTokens.from(request).orElseThrow());
  }

  @Test
  void trimsTokenWhitespace() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer   token-value  ");

    assertEquals("token-value", BearerTokens.from(request).orElseThrow());
  }

  @Test
  void rejectsMissingEmptyAndOtherSchemes() {
    assertTrue(BearerTokens.from(new MockHttpServletRequest()).isEmpty());

    MockHttpServletRequest empty = new MockHttpServletRequest();
    empty.addHeader("Authorization", "Bearer ");
    assertTrue(BearerTokens.from(empty).isEmpty());

    MockHttpServletRequest basic = new MockHttpServletRequest();
    basic.addHeader("Authorization", "Basic token-value");
    assertTrue(BearerTokens.from(basic).isEmpty());
  }
}
