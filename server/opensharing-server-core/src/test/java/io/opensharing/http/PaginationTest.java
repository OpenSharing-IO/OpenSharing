package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PaginationTest {

  private static final List<Integer> ROWS = IntStream.range(0, 1200).boxed().toList();

  @Test
  void pagesThroughTheListWithTokens() {
    ListResponse<Integer> first = page(null, null);
    assertEquals(ROWS.subList(0, 500), first.items());

    ListResponse<Integer> second = page(null, first.nextPageToken());
    assertEquals(ROWS.subList(500, 1000), second.items());

    ListResponse<Integer> last = page(null, second.nextPageToken());
    assertEquals(ROWS.subList(1000, 1200), last.items());
    assertNull(last.nextPageToken());
  }

  @Test
  void capsRequestedPageSizesAtTheMaximum() {
    assertEquals(500, page(1000, null).items().size());
    assertEquals(List.of(0, 1), page(2, null).items());
  }

  @Test
  void returnsNoItemsButATokenForAZeroPageSize() {
    ListResponse<Integer> empty = page(0, null);
    assertEquals(List.of(), empty.items());
    assertNotNull(empty.nextPageToken());
    assertEquals(List.of(0, 1), page(2, empty.nextPageToken()).items());
  }

  @Test
  void rejectsNegativePageSizesAndForeignTokens() {
    assertThrows(ApiException.class, () -> page(-1, null));
    assertThrows(ApiException.class, () -> page(null, "not-a-token"));
    assertThrows(ApiException.class, () -> page(null, "b3MxOi0x"));
  }

  private static ListResponse<Integer> page(Integer maxResults, String pageToken) {
    return Pagination.page(maxResults, pageToken, PaginationTest::slice, Function.identity());
  }

  private static List<Integer> slice(int offset, int limit) {
    int end = Math.min(offset + limit, ROWS.size());
    return ROWS.subList(Math.min(offset, end), end);
  }
}
