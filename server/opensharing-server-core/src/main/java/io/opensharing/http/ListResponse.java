package io.opensharing.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Paginated list envelope shared by list endpoints. {@code nextPageToken} is omitted on the last
 * page:
 *
 * <pre>{@code
 * {"items": [<item>, ...], "nextPageToken": "<page token>"}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListResponse<T>(List<T> items, String nextPageToken) {

  public static <T> ListResponse<T> of(List<T> items) {
    return new ListResponse<>(items, null);
  }
}
