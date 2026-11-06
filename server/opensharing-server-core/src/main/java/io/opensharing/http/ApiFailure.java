package io.opensharing.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogAuthorizationException;
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
    if (e instanceof ApiException api) {
      return new ApiFailure(api.getStatus(), api.getErrorCode(), api.getMessage());
    }
    if (e instanceof AssetNotFoundException missing) {
      return new ApiFailure(404, ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.getMessage());
    }
    if (e instanceof AssetAccessDeniedException denied) {
      return new ApiFailure(403, ErrorCodes.PERMISSION_DENIED, denied.getMessage());
    }
    if (e instanceof UnsupportedAssetTypeException unsupported) {
      return new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, unsupported.getMessage());
    }
    if (e instanceof CatalogAuthenticationException rejected) {
      return new ApiFailure(401, ErrorCodes.UNAUTHENTICATED, rejected.getMessage());
    }
    if (e instanceof CatalogAuthorizationException refused) {
      return new ApiFailure(403, ErrorCodes.PERMISSION_DENIED, refused.getMessage());
    }
    if (e instanceof CatalogException failed) {
      return catalogFailure(failed);
    }
    if (e instanceof JsonProcessingException unreadable) {
      return describe(unreadable);
    }
    if (e instanceof IllegalArgumentException illegal) {
      return new ApiFailure(400, ErrorCodes.INVALID_PARAMETER_VALUE, illegal.getMessage());
    }
    if (violatesConstraint(e)) {
      log.debug("Rejected request that violated a uniqueness constraint", e);
      return new ApiFailure(
          409,
          ErrorCodes.RESOURCE_ALREADY_EXISTS,
          "the object already exists or conflicts with an existing object");
    }
    log.error("Unhandled server error", e);
    return new ApiFailure(500, ErrorCodes.INTERNAL_ERROR, "internal server error");
  }

  /**
   * Maps a failure of a recipient's request. The server calls the catalog as the share's owner on
   * the recipient's behalf, so what the catalog reports, such as who the owner is, stays in the
   * log.
   */
  public static ApiFailure ofRecipientRequest(Exception e) {
    return e instanceof CatalogException failed ? catalogFailure(failed) : of(e);
  }

  // A catalog's message can name its configuration, such as an environment variable, so it stays
  // in the log.
  private static ApiFailure catalogFailure(CatalogException failed) {
    log.error("Catalog request failed", failed);
    return new ApiFailure(502, ErrorCodes.CATALOG_ERROR, "the catalog failed to serve the request");
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
