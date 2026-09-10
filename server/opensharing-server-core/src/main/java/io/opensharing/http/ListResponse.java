package io.opensharing.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Paginated list envelope shared by every {@code list} endpoint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListResponse<T>(List<T> items, String nextPageToken) {

  public static <T> ListResponse<T> of(List<T> items, String nextPageToken) {
    return new ListResponse<>(items, nextPageToken);
  }
}
