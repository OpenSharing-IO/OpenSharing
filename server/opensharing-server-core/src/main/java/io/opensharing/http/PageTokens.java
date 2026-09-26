package io.opensharing.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.data.domain.Page;

/** Encodes a repository offset as an opaque, versioned protocol page token. */
public final class PageTokens {

  private static final String PREFIX = "os1:";

  private PageTokens() {}

  public static int offsetOf(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      return 0;
    }
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(pageToken.trim()), StandardCharsets.UTF_8);
      if (!decoded.startsWith(PREFIX)) {
        throw new IllegalArgumentException("unexpected page token");
      }
      int offset = Integer.parseInt(decoded.substring(PREFIX.length()));
      if (offset < 0) {
        throw new IllegalArgumentException("negative offset");
      }
      return offset;
    } catch (IllegalArgumentException invalid) {
      throw ApiException.invalidParameter("pageToken is not a valid page token");
    }
  }

  public static String nextToken(Page<?> page, int offset) {
    return page.hasNext() ? encode(offset + page.getNumberOfElements()) : null;
  }

  public static String encode(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((PREFIX + offset).getBytes(StandardCharsets.UTF_8));
  }
}
