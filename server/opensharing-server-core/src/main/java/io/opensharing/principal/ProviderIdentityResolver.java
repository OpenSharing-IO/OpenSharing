package io.opensharing.principal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Resolves a provider-admin request to a {@link Caller}. */
@FunctionalInterface
public interface ProviderIdentityResolver {

  Optional<Caller> resolve(HttpServletRequest request);
}
