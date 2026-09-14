package io.opensharing.http;

import io.opensharing.config.OpenSharingProperties;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Applies the protocol's pagination contract to a repository query: a bounded page size, an opaque
 * {@code pageToken}, and a {@code nextPageToken} that is absent once results are exhausted.
 *
 * <p>The Iceberg REST catalog paginates the same way under other names — {@code pageSize} for
 * {@code maxResults} — so what is said about a page size here names no parameter, and each
 * endpoint keeps its own spelling.
 */
@Component
public class Listings {

  private final OpenSharingProperties.Pagination pagination;

  public Listings(OpenSharingProperties properties) {
    this.pagination = properties.getPagination();
  }

  public <E, T> ListResponse<T> page(
      Integer pageSize,
      String pageToken,
      Function<Pageable, Page<E>> query,
      Function<E, T> mapper) {
    int offset = PageTokens.offsetOf(pageToken);
    if (pageSize != null && pageSize == 0) {
      Page<E> probe = query.apply(new OffsetPageable(offset, 1));
      return ListResponse.of(List.of(), probe.hasContent() ? PageTokens.encode(offset) : null);
    }
    Page<E> page = query.apply(new OffsetPageable(offset, resolvePageSize(pageSize)));
    return ListResponse.of(
        page.getContent().stream().map(mapper).toList(), PageTokens.nextToken(page, offset));
  }

  private int resolvePageSize(Integer pageSize) {
    if (pageSize == null) {
      return pagination.getDefaultMaxResults();
    }
    if (pageSize < 0) {
      throw ApiException.invalidParameter("the requested page size must not be negative");
    }
    return Math.min(pageSize, pagination.getMaxMaxResults());
  }
}
