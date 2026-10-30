package io.opensharing.protocol;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.opensharing.asset.table.DeltaSharingCapabilities;
import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.asset.table.FileIdHash;
import io.opensharing.asset.table.delta.DeltaTableQueryReader.ResponseOptions;
import io.opensharing.asset.table.delta.DeltaTableQueryReader.ResponseSink;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An NDJSON file response, sent while it is read. The headers go out once the reader knows the
 * table version and format, and each line as soon as it is read, without waiting for the client to
 * take the earlier ones, so lines a slow client has not received yet queue in memory. A response
 * that fails before then is an ordinary error response; one that fails later is cut short, as its
 * status is already sent.
 */
final class NdjsonResponse implements ResponseSink {

  private static final Logger log = LoggerFactory.getLogger(NdjsonResponse.class);

  private final ResponseOptions options;
  private final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
  private HttpResponseWriter writer;

  private NdjsonResponse(ResponseOptions options) {
    this.options = options;
  }

  /**
   * Runs {@code read} on a blocking thread until every line is written, the client goes away, or
   * the request times out.
   */
  static HttpResponse stream(
      ServiceRequestContext ctx, ResponseOptions options, Consumer<ResponseSink> read) {
    NdjsonResponse out = new NdjsonResponse(options);
    ctx.blockingTaskExecutor().execute(() -> out.run(read));
    return HttpResponse.of(out.response);
  }

  @Override
  public void begin(long version, ResponseFormat format) {
    ResponseHeadersBuilder headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(DeltaSharingProtocolService.NDJSON)
            .add(DeltaSharingProtocolService.DELTA_TABLE_VERSION, Long.toString(version))
            .add(DeltaSharingCapabilities.HEADER, DeltaSharingCapabilities.responded(format));
    if (options.fileIdHash() != null) {
      headers.add(FileIdHash.HEADER, options.fileIdHash());
    }
    writer = HttpResponse.streaming();
    writer.write(headers.build());
    response.complete(writer);
  }

  @Override
  public void line(String line) {
    if (!writer.tryWrite(HttpData.ofUtf8(line))) {
      throw ClosedStreamException.get();
    }
  }

  private void run(Consumer<ResponseSink> read) {
    try {
      read.accept(this);
      writer.close();
    } catch (Throwable failure) {
      fail(failure);
    }
  }

  private void fail(Throwable failure) {
    if (writer == null) {
      response.completeExceptionally(failure);
    } else if (writer.isOpen()) {
      log.error("Query response failed after it began streaming", failure);
      writer.abort(failure);
    }
  }
}
