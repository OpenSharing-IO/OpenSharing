package io.opensharing.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Pages list endpoints. A page holds {@link #MAX_RESULTS} items when the request sets no
 * maxResults or sets 0, and never more. A page token is an opaque encoding of the offset of the
 * next page in the list's order, so that order must be total.
 */
public final class Pagination {

  public static final int MAX_RESULTS = 500;

  private static final String TOKEN_PREFIX = "os1:";

  private Pagination() {}

  /** Rows {@code offset} to {@code offset + limit - 1} of a list. */
  @FunctionalInterface
  public interface Slice<E> {
    List<E> fetch(int offset, int limit);
  }

  /** One page of {@code slice} starting at {@code pageToken}. */
  public static <E, T> ListResponse<T> page(
      Integer maxResults, String pageToken, Slice<E> slice, Function<E, T> mapper) {
    int size = pageSize(maxResults);
    int offset = offsetOf(pageToken);
    // One extra row tells whether another page follows.
    List<E> rows = slice.fetch(offset, size + 1);
    List<T> items = rows.stream().limit(size).map(mapper).toList();
    return new ListResponse<>(items, rows.size() > size ? encode(offset + size) : null);
  }

  private static int pageSize(Integer maxResults) {
    if (maxResults == null || maxResults == 0) {
      return MAX_RESULTS;
    }
    if (maxResults < 0) {
      throw ApiException.invalidParameter("maxResults must not be negative");
    }
    return Math.min(maxResults, MAX_RESULTS);
  }

  private static int offsetOf(String pageToken) {
    if (pageToken == null || pageToken.isEmpty()) {
      return 0;
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(pageToken), StandardCharsets.UTF_8);
      if (decoded.startsWith(TOKEN_PREFIX)) {
        int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
        if (offset >= 0) {
          return offset;
        }
      }
    } catch (IllegalArgumentException invalid) {
      // Reported below like any other token this server did not issue.
    }
    throw ApiException.invalidParameter("pageToken is invalid");
  }

  private static String encode(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
  }
}
