package io.opensharing.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Page sizes of list endpoints. A page token is an opaque encoding of the offset of the next page
 * in the list's order, so that order must be total.
 *
 * @param defaultMaxResults page size when the request sets no maxResults
 * @param maxMaxResults largest page size; larger requests are capped to it
 */
public record Pagination(int defaultMaxResults, int maxMaxResults) {

  public static final Pagination DEFAULTS = new Pagination(500, 1000);

  private static final String TOKEN_PREFIX = "os1:";

  public Pagination {
    if (defaultMaxResults <= 0 || maxMaxResults < defaultMaxResults) {
      throw new IllegalArgumentException(
          "page sizes must satisfy 0 < defaultMaxResults <= maxMaxResults");
    }
  }

  /** Rows {@code offset} to {@code offset + limit - 1} of a list. */
  @FunctionalInterface
  public interface Slice<E> {
    List<E> fetch(int offset, int limit);
  }

  /**
   * One page of {@code slice} starting at {@code pageToken}. A {@code maxResults} of 0 returns no
   * items, with a next page token when any remain.
   */
  public <E, T> ListResponse<T> page(
      Integer maxResults, String pageToken, Slice<E> slice, Function<E, T> mapper) {
    int size = pageSize(maxResults);
    int offset = offsetOf(pageToken);
    // One extra row tells whether another page follows.
    List<E> rows = slice.fetch(offset, size + 1);
    List<T> items = rows.stream().limit(size).map(mapper).toList();
    return new ListResponse<>(items, rows.size() > size ? encode(offset + size) : null);
  }

  private int pageSize(Integer maxResults) {
    if (maxResults == null) {
      return defaultMaxResults;
    }
    if (maxResults < 0) {
      throw ApiException.invalidParameter("maxResults must not be negative");
    }
    return Math.min(maxResults, maxMaxResults);
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
