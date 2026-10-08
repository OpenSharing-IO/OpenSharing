package io.opensharing.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.exception.UnsupportedAssetTypeException;
import java.sql.SQLException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a failure to an HTTP status, protocol error code, and caller-facing message. Hosts map
 * their own framework's failures first and pass everything else here.
 */
public record ApiFailure(int status, String errorCode, String message) {

  private static final Logger log = LoggerFactory.getLogger(ApiFailure.class);

  public static ApiFailure of(Exception e) {
    return switch (e) {
      case ApiException api ->
          new ApiFailure(api.getStatus(), api.getErrorCode(), api.getMessage());
      case AssetNotFoundException missing ->
          new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.getMessage());
      case AssetAccessDeniedException denied ->
          new ApiFailure(403, ErrorCodes.PERMISSION_DENIED, denied.getMessage());
      case UnsupportedAssetTypeException unsupported ->
          new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, unsupported.getMessage());
      case CatalogAuthenticationException rejected -> {
        log.error("The sharing server could not authenticate to the catalog", rejected);
        yield new ApiFailure(
            502,
            ErrorCodes.CATALOG_ERROR,
            "the sharing server could not authenticate to the catalog");
      }
      case CatalogException failed -> {
        log.error("Catalog request failed", failed);
        yield new ApiFailure(502, ErrorCodes.CATALOG_ERROR, failed.getMessage());
      }
      case JsonProcessingException unreadable -> describe(unreadable);
      case IllegalArgumentException illegal ->
          new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, illegal.getMessage());
      default -> {
        if (violatesConstraint(e)) {
          log.debug("Rejected request that violated a uniqueness constraint", e);
          yield new ApiFailure(
              409,
              ErrorCodes.RESOURCE_ALREADY_EXISTS,
              "the object already exists or conflicts with an existing object");
        }
        log.error("Unhandled server error", e);
        yield new ApiFailure(500, ErrorCodes.INTERNAL_ERROR, "internal server error");
      }
    };
  }

  /** An unknown enum value is a bad parameter, not a malformed body. */
  private static ApiFailure describe(JsonProcessingException unreadable) {
    if (unreadable instanceof InvalidFormatException invalid
        && invalid.getTargetType() != null
        && invalid.getTargetType().isEnum()) {
      return new ApiFailure(
          400,
          ErrorCodes.INVALID_PARAMETER_VALUE,
          fieldPath(invalid)
              + " must be one of "
              + Arrays.toString(invalid.getTargetType().getEnumConstants()));
    }
    return new ApiFailure(400, ErrorCodes.MALFORMED_REQUEST, "request body is malformed");
  }

  /**
   * The JSON path of the invalid value, such as {@code a.b[0].c}, or {@code value}
   * when the body itself is the invalid value.
   */
  private static String fieldPath(InvalidFormatException invalid) {
    StringBuilder path = new StringBuilder();
    for (JsonMappingException.Reference reference : invalid.getPath()) {
      // A reference names an object field, or has a null field name and indexes into an array.
      if (reference.getFieldName() != null) {
        path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
      } else {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.isEmpty() ? "value" : path.toString();
  }

  // SQLSTATE class 23 is an integrity constraint violation in every database. Hosts wrap it in
  // their own persistence exceptions, so look for it anywhere in the cause chain.
  private static boolean violatesConstraint(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().startsWith("23")) {
        return true;
      }
    }
    return false;
  }
}
