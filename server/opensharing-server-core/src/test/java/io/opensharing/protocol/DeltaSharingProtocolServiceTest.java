package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeltaSharingProtocolServiceTest {

  @Test
  void givesQueryTableFiveMinutesFromTheRequestsArrival() {
    assertEquals(
        TimeUnit.MINUTES.toMillis(5),
        requestTimeoutOf(HttpMethod.POST, "/shares/s/schemas/c/tables/t/query"));
  }

  @Test
  void givesQueryTableChangesFiveMinutesFromTheRequestsArrival() {
    assertEquals(
        TimeUnit.MINUTES.toMillis(5),
        requestTimeoutOf(HttpMethod.GET, "/shares/s/schemas/c/tables/t/changes"));
  }

  private static long requestTimeoutOf(HttpMethod method, String path) {
    AtomicLong timeout = new AtomicLong();
    Server server =
        Server.builder()
            .http(0)
            .annotatedService(new DeltaSharingProtocolService(null))
            .decorator(
                (delegate, ctx, req) -> {
                  HttpResponse response = delegate.serve(ctx, req);
                  timeout.set(ctx.requestTimeoutMillis());
                  return response;
                })
            .build();
    server.start().join();
    try {
      WebClient.of("http://127.0.0.1:" + server.activeLocalPort())
          .execute(HttpRequest.of(method, path))
          .aggregate()
          .join();
    } finally {
      server.stop().join();
    }
    return timeout.get();
  }
}
