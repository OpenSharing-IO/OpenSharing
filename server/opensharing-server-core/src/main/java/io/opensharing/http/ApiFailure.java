package io.opensharing.http;

import io.opensharing.exception.AssetAccessDeniedException;
import io.opensharing.exception.AssetNotFoundException;
import io.opensharing.exception.CatalogAuthenticationException;
import io.opensharing.exception.CatalogException;
import io.opensharing.exception.UnsupportedAssetTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps a failure to an HTTP status, protocol error code, and caller-facing message. */
public record ApiFailure(HttpStatus status, String errorCode, String message) {

  private static final Logger log = LoggerFactory.getLogger(ApiFailure.class);

  public static ApiFailure of(Exception e) {
    return switch (e) {
      case ApiException api ->
          new ApiFailure(api.getStatus(), api.getErrorCode(), api.getMessage());
      case AssetNotFoundException missing ->
          new ApiFailure(
              HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_DOES_NOT_EXIST, missing.getMessage());
      case AssetAccessDeniedException denied ->
          new ApiFailure(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, denied.getMessage());
      case UnsupportedAssetTypeException unsupported ->
          new ApiFailure(
              HttpStatus.BAD_REQUEST,
              ErrorCodes.INVALID_PARAMETER_VALUE,
              unsupported.getMessage());
      case CatalogAuthenticationException rejected -> {
        log.error("The sharing server could not authenticate to the catalog", rejected);
        yield new ApiFailure(
            HttpStatus.BAD_GATEWAY,
            ErrorCodes.CATALOG_ERROR,
            "the sharing server could not authenticate to the catalog");
      }
      case CatalogException failed -> {
        log.error("Catalog request failed", failed);
        yield new ApiFailure(
            HttpStatus.BAD_GATEWAY, ErrorCodes.CATALOG_ERROR, failed.getMessage());
      }
      case MethodArgumentNotValidException invalid ->
          new ApiFailure(
              HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAMETER_VALUE, describe(invalid));
      case HandlerMethodValidationException ignored -> validationFailed();
      case MethodArgumentTypeMismatchException mistyped ->
          new ApiFailure(
              HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAMETER_VALUE, describe(mistyped));
      case MissingServletRequestParameterException missing ->
          new ApiFailure(
              HttpStatus.BAD_REQUEST,
              ErrorCodes.INVALID_PARAMETER_VALUE,
              missing.getParameterName() + " is required");
      case HttpMessageNotReadableException ignored ->
          new ApiFailure(
              HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST, "request body is malformed");
      case DataIntegrityViolationException conflict -> {
        log.debug("Rejected request that violated a uniqueness constraint", conflict);
        yield new ApiFailure(
            HttpStatus.CONFLICT,
            ErrorCodes.RESOURCE_ALREADY_EXISTS,
            "the object already exists or conflicts with an existing object");
      }
      case NoResourceFoundException ignored ->
          new ApiFailure(
              HttpStatus.NOT_FOUND,
              ErrorCodes.RESOURCE_DOES_NOT_EXIST,
              "the endpoint does not exist");
      case IllegalArgumentException illegal ->
          new ApiFailure(
              HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAMETER_VALUE, illegal.getMessage());
      default -> {
        log.error("Unhandled server error", e);
        yield new ApiFailure(
            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "internal server error");
      }
    };
  }

  private static ApiFailure validationFailed() {
    return new ApiFailure(
        HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAMETER_VALUE, "request validation failed");
  }

  private static String describe(MethodArgumentNotValidException invalid) {
    return invalid.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .orElse("request validation failed");
  }

  private static String describe(MethodArgumentTypeMismatchException mistyped) {
    Class<?> wanted = mistyped.getRequiredType();
    boolean numeric = wanted == Integer.class || wanted == Long.class;
    return numeric
        ? mistyped.getName() + " must be a number"
        : mistyped.getName() + " is not a valid value";
  }
}
