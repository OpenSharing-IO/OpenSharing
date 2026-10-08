package io.opensharing.recipient;

import java.time.Duration;

/**
 * Lifetimes of issued recipient tokens.
 *
 * @param defaultTtl lifetime of a new token when the request does not set one; null for never
 * @param rotationGrace how long replaced tokens keep working after a rotation that does not set one
 */
public record RecipientTokenSettings(Duration defaultTtl, Duration rotationGrace) {

  public static final RecipientTokenSettings DEFAULTS =
      new RecipientTokenSettings(Duration.ofDays(90), Duration.ZERO);
}
