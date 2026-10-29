package io.opensharing.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opensharing.util.Pagination;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProtocolPaginationTest {

  private static final List<Integer> ROWS = IntStream.range(0, 10).boxed().toList();

  @Test
  void returnsNoItemsButATokenForAZeroPageSize() {
    ListResponse<Integer> empty = page(ROWS, 0, null);
    assertEquals(List.of(), empty.items());
    assertEquals(List.of(0, 1), page(ROWS, 2, empty.nextPageToken()).items());

    ListResponse<Integer> later = page(ROWS, 0, page(ROWS, 4, null).nextPageToken());
    assertEquals(List.of(4, 5), page(ROWS, 2, later.nextPageToken()).items());
  }

  @Test
  void returnsNoTokenForAZeroPageSizeOfAnEmptyList() {
    ListResponse<Integer> empty = page(List.of(), 0, null);
    assertEquals(List.of(), empty.items());
    assertNull(empty.nextPageToken());
  }

  @Test
  void pagesOtherSizesLikeProviderLists() {
    assertEquals(ROWS, page(ROWS, null, null).items());
    assertEquals(List.of(0, 1, 2), page(ROWS, 3, null).items());
  }

  private static ListResponse<Integer> page(List<Integer> rows, Integer maxResults, String token) {
    return Pagination.protocolPage(
        maxResults,
        token,
        (offset, limit) ->
            rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size())),
        Function.identity());
  }
}
