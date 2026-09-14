package io.opensharing.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.data.domain.Page;

/**
 * Opaque page tokens. The encoding is an implementation detail clients must not depend on, so the
 * offset is wrapped in a versioned, base64url-encoded envelope.
 */
public final class PageTokens {

  private static final String PREFIX = "os1:";

  private PageTokens() {}

  /** Returns the offset a client asked to resume from, or 0 when no token was supplied. */
  public static int offsetOf(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      return 0;
    }
    try {
      String decoded =
          new String(
              Base64.getUrlDecoder().decode(pageToken.trim()), StandardCharsets.UTF_8);
      if (!decoded.startsWith(PREFIX)) {
        throw new IllegalArgumentException("unexpected token envelope");
      }
      int offset = Integer.parseInt(decoded.substring(PREFIX.length()));
      if (offset < 0) {
        throw new IllegalArgumentException("negative offset");
      }
      return offset;
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidParameter("pageToken is not a valid page token");
    }
  }

  /** Returns the token for the page after {@code page}, or null when the results are exhausted. */
  public static String nextToken(Page<?> page, int offset) {
    if (!page.hasNext()) {
      return null;
    }
    return encode(offset + page.getNumberOfElements());
  }

  public static String encode(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((PREFIX + offset).getBytes(StandardCharsets.UTF_8));
  }
}
