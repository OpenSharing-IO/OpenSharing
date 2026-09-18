package io.opensharing.http;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** A repository page addressed by the absolute offset carried in a protocol page token. */
public record OffsetPageable(int offset, int pageSize) implements Pageable {

  public OffsetPageable {
    if (offset < 0 || pageSize < 1) {
      throw new IllegalArgumentException("offset must be non-negative and pageSize positive");
    }
  }

  @Override
  public int getPageNumber() {
    return offset / pageSize;
  }

  @Override
  public int getPageSize() {
    return pageSize;
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
    return new OffsetPageable(offset + pageSize, pageSize);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious() ? new OffsetPageable(Math.max(0, offset - pageSize), pageSize) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageable(0, pageSize);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    return new OffsetPageable(pageNumber * pageSize, pageSize);
  }

  @Override
  public boolean hasPrevious() {
    return offset > 0;
  }
}
