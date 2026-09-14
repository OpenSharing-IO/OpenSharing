package io.opensharing.http;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;

/**
 * A page cut from a list already in memory, addressed by the absolute offset {@link OffsetPageable}
 * carries.
 *
 * <p>{@link PageImpl} decides whether anything remains by page number, which is only right when every
 * offset is a multiple of the page size. A client may change {@code maxResults} between requests, so
 * offsets here are arbitrary and the question is answered by counting instead — otherwise a client
 * that reduced its page size would be handed a token to a page that does not exist.
 */
public final class OffsetPage<T> extends PageImpl<T> {

  private final long offset;
  private final long total;

  public static <T> OffsetPage<T> of(List<T> all, Pageable pageable) {
    int from = (int) Math.min(pageable.getOffset(), all.size());
    int to = (int) Math.min((long) from + pageable.getPageSize(), all.size());
    return new OffsetPage<>(all.subList(from, to), pageable, all.size());
  }

  private OffsetPage(List<T> content, Pageable pageable, long total) {
    super(content, pageable, total);
    this.offset = pageable.getOffset();
    this.total = total;
  }

  @Override
  public boolean hasNext() {
    return offset + getNumberOfElements() < total;
  }
}
