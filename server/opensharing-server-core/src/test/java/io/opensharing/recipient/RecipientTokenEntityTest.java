package io.opensharing.recipient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The predicate every protocol request runs through: a bearer token authenticates only while its
 * token is usable, and an activation link opens only while it is activatable.
 */
class RecipientTokenEntityTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void anActivatedTokenIsUsableUntilItExpires() {
    RecipientTokenEntity token = activated(NOW.plus(Duration.ofHours(1)));

    assertTrue(token.isUsable(NOW));
    assertFalse(token.isUsable(NOW.plus(Duration.ofHours(2))), "an expired token is not usable");
  }

  @Test
  void aTokenWithoutAnExpirationNeverExpires() {
    RecipientTokenEntity token = activated(null);

    assertTrue(token.isUsable(NOW.plus(Duration.ofDays(3650))));
  }

  @Test
  void revokingEndsATokenImmediately() {
    RecipientTokenEntity token = activated(NOW.plus(Duration.ofHours(1)));
    token.setRevokedAt(NOW);

    assertFalse(token.isUsable(NOW));
  }

  @Test
  void aSupersededTokenKeepsWorkingUntilItExpires() {
    RecipientTokenEntity token = activated(NOW.plus(Duration.ofHours(1)));
    token.setSupersededAt(NOW);

    assertTrue(token.isUsable(NOW), "rotation leaves a grace window rather than cutting access");
    assertFalse(token.isUsable(NOW.plus(Duration.ofHours(2))));
  }

  @Test
  void aTokenIsNotUsableBeforeItsActivationLinkIsOpened() {
    RecipientTokenEntity token = pending(NOW.plus(Duration.ofHours(1)));

    assertFalse(token.isUsable(NOW), "no bearer token exists until activation");
    assertTrue(token.isActivatable(NOW));
  }

  @Test
  void anActivationLinkStopsWorkingWhenItLapsesOrIsRevoked() {
    RecipientTokenEntity lapsed = pending(NOW.plus(Duration.ofHours(1)));
    assertFalse(lapsed.isActivatable(NOW.plus(Duration.ofHours(2))));

    RecipientTokenEntity revoked = pending(NOW.plus(Duration.ofHours(1)));
    revoked.setRevokedAt(NOW);
    assertFalse(revoked.isActivatable(NOW));

    RecipientTokenEntity used = pending(NOW.plus(Duration.ofHours(1)));
    used.setActivated(true);
    used.setActivationNonceHash(null);
    assertFalse(used.isActivatable(NOW), "an activation link is single use");
  }

  private static RecipientTokenEntity pending(Instant activationExpiresAt) {
    RecipientTokenEntity token = new RecipientTokenEntity();
    token.setActivationNonceHash("nonce-hash");
    token.setActivationExpiresAt(activationExpiresAt);
    token.setExpiresAt(NOW.plus(Duration.ofDays(90)));
    return token;
  }

  private static RecipientTokenEntity activated(Instant expiresAt) {
    RecipientTokenEntity token = new RecipientTokenEntity();
    token.setTokenHash("token-hash");
    token.setActivated(true);
    token.setExpiresAt(expiresAt);
    return token;
  }
}
