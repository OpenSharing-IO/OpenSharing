package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.CatalogException;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiFailureTest {

  @Test
  void mapsCatalogMissesAndDenials() {
    Asset lookup = new Asset(AssetType.TABLE, "main.sales.missing");
    ApiFailure missing = ApiFailure.of(new AssetNotFoundException(lookup));
    assertEquals(HttpStatus.NOT_FOUND, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());

    UserContext bob = UserContext.fromUserIdAndName("bob@example.com", "bob@example.com");
    ApiFailure denied = ApiFailure.of(new AssetAccessDeniedException(lookup, bob));
    assertEquals(HttpStatus.FORBIDDEN, denied.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, denied.errorCode());
  }

  @Test
  void mapsCatalogFailuresAsBadGateway() {
    ApiFailure failure = ApiFailure.of(new CatalogException("catalog unreachable"));
    assertEquals(HttpStatus.BAD_GATEWAY, failure.status());
    assertEquals(ErrorCodes.CATALOG_ERROR, failure.errorCode());
    assertEquals("catalog unreachable", failure.message());
  }

  @Test
  void mapsUnsupportedAssetTypesAsBadRequest() {
    ApiFailure failure = ApiFailure.of(new UnsupportedAssetTypeException("not a schema"));
    assertEquals(HttpStatus.BAD_REQUEST, failure.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, failure.errorCode());
  }

  @Test
  void mapsUnknownRoutes() {
    ApiFailure failure = ApiFailure.of(new NoResourceFoundException(HttpMethod.GET, "/missing"));
    assertEquals(HttpStatus.NOT_FOUND, failure.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, failure.errorCode());
    assertEquals("the endpoint does not exist", failure.message());
  }

  @Test
  void keepsAnExplicitApiException() {
    ApiFailure failure = ApiFailure.of(ApiException.unauthenticated("token is missing"));
    assertEquals(HttpStatus.UNAUTHORIZED, failure.status());
    assertEquals(ErrorCodes.UNAUTHENTICATED, failure.errorCode());
    assertEquals("token is missing", failure.message());
  }

  @Test
  void mapsUnknownEnumValuesAsInvalidParameters() {
    InvalidFormatException invalid =
        assertThrows(
            InvalidFormatException.class,
            () ->
                new ObjectMapper()
                    .readValue("{\"items\":[{\"type\":\"VIEW\"}]}", Body.class));
    ApiFailure failure = ApiFailure.of(unreadable(invalid));
    assertEquals(HttpStatus.BAD_REQUEST, failure.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, failure.errorCode());
    assertEquals("items[0].type must be one of [TABLE, SCHEMA]", failure.message());
  }

  @Test
  void mapsOtherUnreadableBodiesAsMalformed() {
    ApiFailure failure = ApiFailure.of(unreadable(null));
    assertEquals(HttpStatus.BAD_REQUEST, failure.status());
    assertEquals(ErrorCodes.MALFORMED_REQUEST, failure.errorCode());
  }

  private static HttpMessageNotReadableException unreadable(Throwable cause) {
    return new HttpMessageNotReadableException(
        "unreadable", cause, new MockHttpInputMessage(new byte[0]));
  }

  record Body(List<Item> items) {}

  record Item(AssetType type) {}
}
