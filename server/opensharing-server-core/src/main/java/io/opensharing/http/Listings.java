package io.opensharing.http;

import io.opensharing.config.OpenSharingProperties;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** Applies protocol page-size and opaque-token semantics to a repository query. */
@Component
public class Listings {

  private final OpenSharingProperties.Pagination pagination;

  public Listings(OpenSharingProperties properties) {
    this.pagination = properties.getPagination();
  }

  public <E, T> ListResponse<T> page(
      Integer requestedSize,
      String pageToken,
      Function<Pageable, Page<E>> query,
      Function<E, T> mapper) {
    int offset = PageTokens.offsetOf(pageToken);
    if (requestedSize != null && requestedSize == 0) {
      Page<E> probe = query.apply(new OffsetPageable(offset, 1));
      return ListResponse.of(List.of(), probe.hasContent() ? PageTokens.encode(offset) : null);
    }
    Page<E> page = query.apply(new OffsetPageable(offset, pageSize(requestedSize)));
    return ListResponse.of(
        page.getContent().stream().map(mapper).toList(), PageTokens.nextToken(page, offset));
  }

  private int pageSize(Integer requestedSize) {
    if (requestedSize == null) {
      return pagination.getDefaultMaxResults();
    }
    if (requestedSize < 0) {
      throw ApiException.invalidParameter("maxResults must not be negative");
    }
    return Math.min(requestedSize, pagination.getMaxMaxResults());
  }
}
