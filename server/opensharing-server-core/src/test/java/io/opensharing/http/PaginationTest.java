package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PaginationTest {

  private static final List<String> ROWS = List.of("a", "b", "c", "d", "e");
  private static final Pagination PAGINATION = new Pagination(2, 3);

  @Test
  void pagesThroughTheListWithTokens() {
    ListResponse<String> first = page(null, null);
    assertEquals(List.of("a", "b"), first.items());

    ListResponse<String> second = page(null, first.nextPageToken());
    assertEquals(List.of("c", "d"), second.items());

    ListResponse<String> last = page(null, second.nextPageToken());
    assertEquals(List.of("e"), last.items());
    assertNull(last.nextPageToken());
  }

  @Test
  void capsRequestedPageSizesAtTheMaximum() {
    assertEquals(List.of("a", "b", "c"), page(100, null).items());
  }

  @Test
  void returnsNoItemsButATokenForAZeroPageSize() {
    ListResponse<String> empty = page(0, null);
    assertEquals(List.of(), empty.items());
    assertNotNull(empty.nextPageToken());
    assertEquals(List.of("a", "b"), page(null, empty.nextPageToken()).items());
  }

  @Test
  void rejectsNegativePageSizesAndForeignTokens() {
    assertThrows(ApiException.class, () -> page(-1, null));
    assertThrows(ApiException.class, () -> page(null, "not-a-token"));
    assertThrows(ApiException.class, () -> page(null, "b3MxOi0x"));
  }

  @Test
  void rejectsInconsistentPageSizes() {
    assertThrows(IllegalArgumentException.class, () -> new Pagination(0, 10));
    assertThrows(IllegalArgumentException.class, () -> new Pagination(10, 5));
  }

  private static ListResponse<String> page(Integer maxResults, String pageToken) {
    return PAGINATION.page(maxResults, pageToken, PaginationTest::slice, Function.identity());
  }

  private static List<String> slice(int offset, int limit) {
    int end = Math.min(offset + limit, ROWS.size());
    return ROWS.subList(Math.min(offset, end), end);
  }
}
