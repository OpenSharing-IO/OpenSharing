package io.opensharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import io.opensharing.config.ServerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

/** A standalone server on a free port, and a client that calls it as a provider. */
public final class TestServer implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final OpenSharingServer server;
  private final WebClient client;

  private TestServer(OpenSharingServer server) {
    this.server = server;
    this.client = WebClient.of("http://127.0.0.1:" + server.port());
  }

  public static TestServer start() {
    Properties props = new Properties();
    props.setProperty("server.port", "0");
    return new TestServer(OpenSharingServer.start(new ServerProperties(props)));
  }

  public OpenSharingServer server() {
    return server;
  }

  public Response get(String path, String token) {
    return call(HttpMethod.GET, path, token, null);
  }

  public Response post(String path, String token, String json) {
    return call(HttpMethod.POST, path, token, json);
  }

  public Response patch(String path, String token, String json) {
    return call(HttpMethod.PATCH, path, token, json);
  }

  public Response delete(String path, String token) {
    return call(HttpMethod.DELETE, path, token, null);
  }

  /** Calls {@code path} with {@code token} as the bearer token, unless it is null. */
  public Response call(HttpMethod method, String path, String token, String json) {
    RequestHeadersBuilder headers = RequestHeaders.builder(method, path);
    if (token != null) {
      headers.add(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
    }
    HttpRequest request =
        json == null
            ? HttpRequest.of(headers.build())
            : HttpRequest.of(
                headers.contentType(MediaType.JSON_UTF_8).build(), HttpData.ofUtf8(json));
    return Response.of(client.execute(request).aggregate().join());
  }

  @Override
  public void close() {
    server.close();
  }

  /** A response with its body parsed as JSON, or a missing node when it has none. */
  public record Response(int status, ResponseHeaders headers, JsonNode body) {

    static Response of(AggregatedHttpResponse response) {
      try {
        return new Response(
            response.status().code(), response.headers(), JSON.readTree(response.contentUtf8()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    public String errorCode() {
      return body.path("errorCode").asText();
    }
  }
}
