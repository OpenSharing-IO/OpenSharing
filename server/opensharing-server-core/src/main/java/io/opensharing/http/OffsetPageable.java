package io.opensharing.http;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} addressed by absolute offset. Protocol page tokens carry an offset rather than
 * a page number, so a client is free to change {@code maxResults} between requests.
 */
public final class OffsetPageable implements Pageable {

  private final int offset;
  private final int limit;

  public OffsetPageable(int offset, int limit) {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    this.offset = offset;
    this.limit = limit;
  }

  @Override
  public int getPageNumber() {
    return offset / limit;
  }

  @Override
  public int getPageSize() {
    return limit;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return Sort.unsorted();
  }

  @Override
  public Pageable next() {
    return new OffsetPageable(offset + limit, limit);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious() ? new OffsetPageable(Math.max(0, offset - limit), limit) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageable(0, limit);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    return new OffsetPageable(pageNumber * limit, limit);
  }

  @Override
  public boolean hasPrevious() {
    return offset > 0;
  }
}
