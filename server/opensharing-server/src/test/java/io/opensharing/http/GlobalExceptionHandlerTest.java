package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.opensharing.catalog.AssetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:http-errors;DB_CLOSE_DELAY=-1",
      "opensharing.catalog.type=local",
      "opensharing.catalog.local.file=classpath:local-catalog.yml"
    })
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mvc;

  @Test
  void answersUnknownRoutesWithTheProtocolErrorBody() throws Exception {
    mvc.perform(get("/no-such-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_DOES_NOT_EXIST))
        .andExpect(jsonPath("$.message").value("the endpoint does not exist"));
  }

  @Test
  void mapsUnknownEnumValuesInABodyAsInvalidParameters() {
    InvalidFormatException invalid =
        assertThrows(
            InvalidFormatException.class,
            () -> new ObjectMapper().readValue("{\"type\":\"VIEW\"}", Body.class));
    ApiFailure failure = GlobalExceptionHandler.failureOf(unreadable(invalid));
    assertEquals(400, failure.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, failure.errorCode());
    assertEquals("type must be one of [TABLE, SCHEMA]", failure.message());
  }

  @Test
  void mapsOtherUnreadableBodiesAsMalformed() {
    ApiFailure failure = GlobalExceptionHandler.failureOf(unreadable(null));
    assertEquals(400, failure.status());
    assertEquals(ErrorCodes.MALFORMED_REQUEST, failure.errorCode());
  }

  private static HttpMessageNotReadableException unreadable(Throwable cause) {
    return new HttpMessageNotReadableException(
        "unreadable", cause, new MockHttpInputMessage(new byte[0]));
  }

  record Body(AssetType type) {}
}
