package io.opensharing.protocol;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
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
 * An NDJSON file response, sent while it is read so a large table is never held in memory. Lines
 * go out in chunks of about {@link #CHUNK} characters, each once the client has taken the last.
 * A response that fails before its first chunk is an ordinary error response; one that fails
 * later is cut short, as its status is already sent.
 */
final class NdjsonResponse implements ResponseSink {

  private static final Logger log = LoggerFactory.getLogger(NdjsonResponse.class);
  private static final int CHUNK = 64 * 1024;

  private final ResponseOptions options;
  private final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
  private final StringBuilder buffer = new StringBuilder();
  private ResponseHeaders headers;
  private HttpResponseWriter writer;

  private NdjsonResponse(ResponseOptions options) {
    this.options = options;
  }

  /** Runs {@code read} on a blocking thread, which holds it until the client has every chunk. */
  static HttpResponse stream(
      ServiceRequestContext ctx, ResponseOptions options, Consumer<ResponseSink> read) {
    NdjsonResponse out = new NdjsonResponse(options);
    ctx.blockingTaskExecutor().execute(() -> out.run(read));
    return HttpResponse.of(out.response);
  }

  @Override
  public void begin(long version, ResponseFormat format) {
    ResponseHeadersBuilder builder =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(DeltaSharingProtocolService.NDJSON)
            .add(DeltaSharingProtocolService.DELTA_TABLE_VERSION, Long.toString(version))
            .add(DeltaSharingCapabilities.HEADER, DeltaSharingCapabilities.responded(format));
    if (options.fileIdHash() != null) {
      builder.add(FileIdHash.HEADER, options.fileIdHash());
    }
    headers = builder.build();
  }

  @Override
  public void line(String line) {
    buffer.append(line);
    if (buffer.length() >= CHUNK) {
      if (writer == null) {
        writer = HttpResponse.streaming();
        writer.write(headers);
        response.complete(writer);
      }
      send();
    }
  }

  private void run(Consumer<ResponseSink> read) {
    try {
      read.accept(this);
      if (writer == null) {
        response.complete(HttpResponse.of(headers, HttpData.ofUtf8(buffer.toString())));
      } else {
        if (!buffer.isEmpty()) {
          send();
        }
        writer.close();
      }
    } catch (Throwable failure) {
      fail(failure);
    }
  }

  private void send() {
    writer.write(HttpData.ofUtf8(buffer.toString()));
    buffer.setLength(0);
    writer.whenConsumed().join();
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
