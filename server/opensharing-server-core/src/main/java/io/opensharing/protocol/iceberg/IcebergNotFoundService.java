package io.opensharing.protocol.iceberg;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Answers a request under the Iceberg REST catalog's path that none of its routes serves, so that
 * it fails with Iceberg's error body rather than the server-wide fallback's, which Iceberg clients
 * cannot parse. Mount it under the catalog's path with the catalog's error handling.
 */
public final class IcebergNotFoundService implements HttpService {

  @Override
  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
    throw IcebergException.notFound(
        "the Iceberg REST catalog serves no " + ctx.method() + " " + ctx.path());
  }
}
