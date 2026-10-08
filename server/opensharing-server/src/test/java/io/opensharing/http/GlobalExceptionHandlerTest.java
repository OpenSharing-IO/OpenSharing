package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.opensharing.catalog.AssetType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
  void mapsInvalidBodiesWithTheFirstFieldError() throws Exception {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Body(null), "body");
    result.addError(new FieldError("body", "type", "must not be null"));
    assertFailure(
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "type must not be null",
        new MethodArgumentNotValidException(maxResults(), result));
  }

  @Test
  void mapsMethodValidationFailures() throws Exception {
    MethodParameter maxResults = maxResults();
    ParameterValidationResult invalid =
        new ParameterValidationResult(
            maxResults,
            0,
            List.of(new DefaultMessageSourceResolvable(null, null, "must be greater than 0")));
    assertFailure(
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "request validation failed",
        new HandlerMethodValidationException(
            MethodValidationResult.create(new Object(), maxResults.getMethod(), List.of(invalid))));
  }

  @Test
  void mapsMistypedParameters() throws Exception {
    assertFailure(
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "maxResults must be a number",
        new MethodArgumentTypeMismatchException(
            "ten", Integer.class, "maxResults", maxResults(), null));
  }

  @Test
  void mapsMissingParameters() {
    assertFailure(
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "maxResults is required",
        new MissingServletRequestParameterException("maxResults", "Integer"));
  }

  @Test
  void mapsUnreadableBodiesThroughTheirJsonCause() {
    InvalidFormatException invalid =
        assertThrows(
            InvalidFormatException.class,
            () -> new ObjectMapper().readValue("{\"type\":\"VIEW\"}", Body.class));
    assertFailure(
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "type must be one of [TABLE, SCHEMA]",
        new HttpMessageNotReadableException(
            "unreadable", invalid, new MockHttpInputMessage(new byte[0])));
  }

  @Test
  void leavesOtherFailuresToApiFailure() {
    ApiFailure failure = GlobalExceptionHandler.failureOf(ApiException.notFound("no share"));
    assertEquals(new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, "no share"), failure);
  }

  private static void assertFailure(String errorCode, String message, Exception e) {
    assertEquals(new ApiFailure(400, errorCode, message), GlobalExceptionHandler.failureOf(e));
  }

  private static MethodParameter maxResults() throws NoSuchMethodException {
    return new MethodParameter(Endpoint.class.getDeclaredMethod("list", Integer.class), 0);
  }

  record Body(AssetType type) {}

  interface Endpoint {
    void list(Integer maxResults);
  }
}
