package io.opensharing.runtime;

import io.opensharing.principal.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Maps a provider-admin HTTP request to the {@link Caller} behind it when OpenSharing is embedded in
 * a host that already authenticated the user.
 *
 * <p>Unity Catalog OSS can implement this to turn the current UC principal into a {@link Caller}
 * without maintaining a parallel {@code opensharing.admin.principals} list or duplicating bearer
 * tokens for catalog access.
 */
@FunctionalInterface
public interface ProviderIdentityResolver {

  Optional<Caller> resolve(HttpServletRequest request);
}
