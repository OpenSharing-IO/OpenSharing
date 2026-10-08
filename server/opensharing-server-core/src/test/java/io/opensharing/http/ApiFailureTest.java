package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.opensharing.auth.UserContext;
import io.opensharing.catalog.Asset;
import io.opensharing.catalog.AssetType;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiFailureTest {

  private static final Asset MISSING = new Asset(AssetType.TABLE, "main.sales.missing");

  @Test
  void keepsAnExplicitApiException() {
    assertFailure(
        401,
        ErrorCodes.UNAUTHENTICATED,
        "token is missing",
        ApiException.unauthenticated("token is missing"));
    assertFailure(
        501, ErrorCodes.NOT_IMPLEMENTED, "not yet", ApiException.notImplemented("not yet"));
  }

  @Test
  void mapsMissingAssetsAsNotFound() {
    assertFailure(
        404,
        ErrorCodes.RESOURCE_DOES_NOT_EXIST,
        "TABLE 'main.sales.missing' does not exist in the catalog",
        new AssetNotFoundException(MISSING));
  }

  @Test
  void mapsDeniedAssetsAsPermissionDenied() {
    UserContext bob = UserContext.fromUserIdAndName("bob@example.com", "bob@example.com");
    assertFailure(
        403,
        ErrorCodes.PERMISSION_DENIED,
        "'bob@example.com' may not share TABLE 'main.sales.missing'",
        new AssetAccessDeniedException(MISSING, bob));
  }

  @Test
  void mapsUnsupportedAssetTypesAsBadRequest() {
    assertFailure(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "not a schema",
        new UnsupportedAssetTypeException("not a schema"));
  }

  @Test
  void hidesCatalogAuthenticationFailuresBehindBadGateway() {
    assertFailure(
        502,
        ErrorCodes.CATALOG_ERROR,
        "the sharing server could not authenticate to the catalog",
        new CatalogAuthenticationException("token for svc-sharing expired"));
  }

  @Test
  void mapsOtherCatalogFailuresAsBadGateway() {
    assertFailure(
        502,
        ErrorCodes.CATALOG_ERROR,
        "catalog unreachable",
        new CatalogException("catalog unreachable"));
    assertFailure(
        502,
        ErrorCodes.CATALOG_ERROR,
        "user lacks USE CATALOG",
        new CatalogAuthorizationException("user lacks USE CATALOG"));
  }

  @Test
  void mapsUnknownEnumValuesAsInvalidParameters() {
    assertFailure(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "items[0].type must be one of [TABLE, SCHEMA]",
        unreadable("{\"items\":[{\"type\":\"VIEW\"}]}", Body.class));
  }

  @Test
  void namesATopLevelEnumValueAsValue() {
    assertFailure(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "value must be one of [TABLE, SCHEMA]",
        unreadable("\"VIEW\"", AssetType.class));
  }

  @Test
  void mapsOtherInvalidFormatsAsMalformed() {
    InvalidFormatException invalid = unreadable("{\"count\":\"ten\"}", Counted.class);
    assertFailure(400, ErrorCodes.MALFORMED_REQUEST, "request body is malformed", invalid);
  }

  @Test
  void mapsUnparsableBodiesAsMalformed() {
    JsonProcessingException malformed =
        assertThrows(JsonProcessingException.class, () -> new ObjectMapper().readTree("{"));
    assertFailure(400, ErrorCodes.MALFORMED_REQUEST, "request body is malformed", malformed);
  }

  @Test
  void mapsIllegalArgumentsAsInvalidParameters() {
    assertFailure(
        400,
        ErrorCodes.INVALID_PARAMETER_VALUE,
        "name must not be blank",
        new IllegalArgumentException("name must not be blank"));
  }

  @Test
  void mapsConstraintViolationsAnywhereInTheCauseChainAsAlreadyExists() {
    SQLException duplicate = new SQLException("duplicate key", "23505");
    assertFailure(
        409,
        ErrorCodes.RESOURCE_ALREADY_EXISTS,
        "the object already exists or conflicts with an existing object",
        new RuntimeException("commit failed", new IllegalStateException("rollback", duplicate)));
  }

  @Test
  void mapsOtherSqlFailuresAsInternalErrors() {
    assertFailure(
        500,
        ErrorCodes.INTERNAL_ERROR,
        "internal server error",
        new RuntimeException(new SQLException("connection lost", "08006")));
    assertFailure(
        500,
        ErrorCodes.INTERNAL_ERROR,
        "internal server error",
        new RuntimeException(new SQLException("no state")));
  }

  @Test
  void hidesUnhandledFailuresBehindInternalError() {
    assertFailure(
        500,
        ErrorCodes.INTERNAL_ERROR,
        "internal server error",
        new IllegalStateException("secret detail"));
  }

  private static void assertFailure(int status, String errorCode, String message, Exception e) {
    ApiFailure failure = ApiFailure.of(e);
    assertEquals(status, failure.status());
    assertEquals(errorCode, failure.errorCode());
    assertEquals(message, failure.message());
  }

  private static InvalidFormatException unreadable(String json, Class<?> type) {
    return assertThrows(
        InvalidFormatException.class, () -> new ObjectMapper().readValue(json, type));
  }

  record Body(List<Item> items) {}

  record Item(AssetType type) {}

  record Counted(int count) {}
}
