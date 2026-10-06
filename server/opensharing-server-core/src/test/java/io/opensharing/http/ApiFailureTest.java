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
import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiFailureTest {

  @Test
  void mapsCatalogMissesAndDenials() {
    Asset lookup = new Asset(AssetType.TABLE, "main.sales.missing");
    ApiFailure missing = ApiFailure.of(new AssetNotFoundException(lookup));
    assertEquals(404, missing.status());
    assertEquals(ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.errorCode());

    UserContext bob = UserContext.fromUserIdAndName("bob@example.com", "bob@example.com");
    ApiFailure denied = ApiFailure.of(new AssetAccessDeniedException(lookup, bob));
    assertEquals(403, denied.status());
    assertEquals(ErrorCodes.PERMISSION_DENIED, denied.errorCode());
  }

  @Test
  void mapsCatalogFailuresAsBadGateway() {
    ApiFailure failure = ApiFailure.of(new CatalogException("catalog unreachable"));
    assertEquals(502, failure.status());
    assertEquals(ErrorCodes.CATALOG_ERROR, failure.errorCode());
    assertEquals("catalog unreachable", failure.message());
  }

  @Test
  void mapsUnsupportedAssetTypesAsBadRequest() {
    ApiFailure failure = ApiFailure.of(new UnsupportedAssetTypeException("not a schema"));
    assertEquals(400, failure.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, failure.errorCode());
  }

  @Test
  void keepsAnExplicitApiException() {
    ApiFailure failure = ApiFailure.of(ApiException.unauthenticated("token is missing"));
    assertEquals(401, failure.status());
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
    ApiFailure failure = ApiFailure.of(invalid);
    assertEquals(400, failure.status());
    assertEquals(ErrorCodes.INVALID_PARAMETER_VALUE, failure.errorCode());
    assertEquals("items[0].type must be one of [TABLE, SCHEMA]", failure.message());
  }

  @Test
  void mapsOtherUnreadableBodiesAsMalformed() {
    JsonProcessingException malformed =
        assertThrows(JsonProcessingException.class, () -> new ObjectMapper().readTree("{"));
    ApiFailure failure = ApiFailure.of(malformed);
    assertEquals(400, failure.status());
    assertEquals(ErrorCodes.MALFORMED_REQUEST, failure.errorCode());
  }

  @Test
  void mapsConstraintViolationsAsAlreadyExists() {
    SQLException duplicate = new SQLException("duplicate key", "23505");
    ApiFailure failure = ApiFailure.of(new RuntimeException("commit failed", duplicate));
    assertEquals(409, failure.status());
    assertEquals(ErrorCodes.RESOURCE_ALREADY_EXISTS, failure.errorCode());
  }

  record Body(List<Item> items) {}

  record Item(AssetType type) {}
}
