package io.opensharing.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * The JSON format of every OpenSharing endpoint, used instead of the host server's own mapper so
 * responses look the same in every host: null fields are left out, enum values are read in any
 * case, unknown fields are ignored, and dates are ISO-8601 strings.
 */
public final class ApiJson {

  public static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .serializationInclusion(JsonInclude.Include.NON_NULL)
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .addModule(new JavaTimeModule())
          .build();

  private ApiJson() {}

  /** {@code body} as a JSON response with {@code headers}, which set its status. */
  public static HttpResponse response(ResponseHeaders headers, Object body) {
    try {
      return HttpResponse.of(
          headers.toBuilder().contentType(MediaType.JSON_UTF_8).build(),
          HttpData.wrap(MAPPER.writeValueAsBytes(body)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to write an OpenSharing response", e);
    }
  }
}
