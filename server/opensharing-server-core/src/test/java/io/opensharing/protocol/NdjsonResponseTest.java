package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.delta.DeltaTableQueryReader.ResponseOptions;
import io.opensharing.asset.table.delta.DeltaTableQueryReader.ResponseSink;
import io.opensharing.http.ApiException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NdjsonResponseTest {

  private static final ResponseOptions OPTIONS = new ResponseOptions(null, null);

  @Test
  void sendsTheHeadersThenEveryLine() {
    AggregatedHttpResponse response =
        stream(
                sink -> {
                  sink.begin(3, ResponseFormat.PARQUET);
                  sink.line("{\"a\":1}\n");
                  sink.line("{\"b\":2}\n");
                })
            .aggregate()
            .join();

    assertEquals(200, response.status().code());
    assertEquals("3", response.headers().get(DeltaSharingProtocolService.DELTA_TABLE_VERSION));
    assertEquals(
        DeltaSharingCapabilities.responded(ResponseFormat.PARQUET),
        response.headers().get(DeltaSharingCapabilities.HEADER));
    assertEquals("{\"a\":1}\n{\"b\":2}\n", response.contentUtf8());
  }

  @Test
  void streamsEveryLinePastTheUnconsumedLimit() {
    String line = "x".repeat(99) + "\n";
    AggregatedHttpResponse response =
        stream(
                sink -> {
                  sink.begin(7, ResponseFormat.DELTA);
                  for (int i = 0; i < 2000; i++) {
                    sink.line(line);
                  }
                })
            .aggregate()
            .join();

    assertEquals(200, response.status().code());
    assertEquals("7", response.headers().get(DeltaSharingProtocolService.DELTA_TABLE_VERSION));
    assertEquals(line.repeat(2000), response.contentUtf8());
  }

  @Test
  void allowsFiveMinutesFromTheRequestStart() {
    ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/query"));
    NdjsonResponse.stream(ctx, OPTIONS, sink -> sink.begin(1, ResponseFormat.PARQUET))
        .aggregate()
        .join();

    assertEquals(Duration.ofMinutes(5).toMillis(), ctx.requestTimeoutMillis());
  }

  @Test
  void failsTheResponseWhenTheReadFailsBeforeItBegins() {
    ApiException invalid = ApiException.invalidParameter("bad version");
    HttpResponse response =
        stream(
            sink -> {
              throw invalid;
            });

    CompletionException failed =
        assertThrows(CompletionException.class, () -> response.aggregate().join());
    assertSame(invalid, failed.getCause());
  }

  @Test
  void abortsTheStreamWhenTheReadFailsAfterItBegins() {
    IllegalStateException broken = new IllegalStateException("log unreadable");
    HttpResponse response =
        stream(
            sink -> {
              sink.begin(1, ResponseFormat.PARQUET);
              sink.line("{\"a\":1}\n");
              throw broken;
            });

    CompletionException failed =
        assertThrows(CompletionException.class, () -> response.aggregate().join());
    assertSame(broken, failed.getCause());
  }

  private static HttpResponse stream(Consumer<ResponseSink> read) {
    ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/query"));
    return NdjsonResponse.stream(ctx, OPTIONS, read);
  }
}
