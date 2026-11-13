package io.opensharing.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.delta.DeltaTableQueryReader.ResponseOptions;
import io.opensharing.asset.table.delta.DeltaTableQueryReader.ResponseSink;
import io.opensharing.exception.CatalogAuthorizationException;
import io.opensharing.http.ApiException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NdjsonResponseTest {

  private static final ResponseOptions OPTIONS = new ResponseOptions(null, null, false);
  private static final ResponseOptions END_STREAM = new ResponseOptions(null, null, true);

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
        DeltaSharingCapabilities.responded(ResponseFormat.PARQUET, false),
        response.headers().get(DeltaSharingCapabilities.HEADER));
    assertEquals("{\"a\":1}\n{\"b\":2}\n", response.contentUtf8());
  }

  @Test
  void writesEveryLineWithoutWaitingForTheClient() throws InterruptedException {
    String line = "x".repeat(99) + "\n";
    CountDownLatch written = new CountDownLatch(1);
    HttpResponse streamed =
        stream(
            sink -> {
              sink.begin(7, ResponseFormat.DELTA);
              for (int i = 0; i < 2000; i++) {
                sink.line(line);
              }
              written.countDown();
            });

    assertTrue(written.await(10, TimeUnit.SECONDS));
    AggregatedHttpResponse response = streamed.aggregate().join();
    assertEquals(200, response.status().code());
    assertEquals("7", response.headers().get(DeltaSharingProtocolService.DELTA_TABLE_VERSION));
    assertEquals(line.repeat(2000), response.contentUtf8());
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
  void endsTheStreamWithAnInvalidJsonLineWhenTheReadFailsAfterItBegins() {
    AggregatedHttpResponse response =
        stream(
                sink -> {
                  sink.begin(1, ResponseFormat.PARQUET);
                  sink.line("{\"a\":1}\n");
                  throw new IllegalStateException("log unreadable");
                })
            .aggregate()
            .join();

    // The status is sent, so the client is made to fail on a line that is not JSON.
    assertEquals(200, response.status().code());
    assertEquals(
        """
        {"a":1}
        Forcing_invalid_json_to_fail_streaming_rpc_due_to_error_INTERNAL_ERROR__internal_server_error{
        """,
        response.contentUtf8());
  }

  @Test
  void endsAStreamThatFailsAfterItBeginsWithTheErrorWhenAsked() {
    AggregatedHttpResponse response =
        stream(
                END_STREAM,
                sink -> {
                  sink.begin(1, ResponseFormat.PARQUET);
                  sink.line("{\"a\":1}\n");
                  throw ApiException.invalidParameter("change data was not recorded");
                })
            .aggregate()
            .join();

    assertEquals(200, response.status().code());
    assertEquals(
        DeltaSharingCapabilities.responded(ResponseFormat.PARQUET, true),
        response.headers().get(DeltaSharingCapabilities.HEADER));
    assertEquals(
        """
        {"a":1}
        {"endStreamAction":{"errorMessage":"INVALID_PARAMETER_VALUE: change data was not recorded","httpStatusErrorCode":400}}
        """,
        response.contentUtf8());
  }

  @Test
  void hidesCatalogFailuresInTheEndStreamAction() {
    AggregatedHttpResponse response =
        stream(
                END_STREAM,
                sink -> {
                  sink.begin(1, ResponseFormat.PARQUET);
                  throw new CatalogAuthorizationException("'owner-id' lacks SELECT");
                })
            .aggregate()
            .join();

    assertEquals(200, response.status().code());
    assertEquals(
        """
        {"endStreamAction":{"errorMessage":"CATALOG_ERROR: the catalog failed to serve the request","httpStatusErrorCode":502}}
        """,
        response.contentUtf8());
  }


  private static HttpResponse stream(Consumer<ResponseSink> read) {
    return stream(OPTIONS, read);
  }

  private static HttpResponse stream(ResponseOptions options, Consumer<ResponseSink> read) {
    ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/query"));
    return NdjsonResponse.stream(ctx, options, read);
  }
}
