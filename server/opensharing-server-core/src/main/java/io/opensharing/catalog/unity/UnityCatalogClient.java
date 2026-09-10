package io.opensharing.catalog.unity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.opensharing.catalog.CatalogCaller;
import io.opensharing.catalog.CatalogCaller.Credential;
import io.opensharing.catalog.CatalogException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Speaks HTTP and JSON to a Unity Catalog, so that {@link UnityCatalogConnector} above it deals only
 * in what the answers mean.
 *
 * <p>Requests are authenticated as the {@link CatalogCaller} they are made for, one of two ways
 * depending on which {@link Credential} the caller carries:
 *
 * <ul>
 *   <li>{@link Credential.BearerToken} presents the token as {@code Authorization: Bearer}. This
 *       client holds no identity of its own in that case, which is what makes the question one the
 *       catalog answers against the privileges of the principal it actually concerns.
 *   <li>{@link Credential.OnBehalfOf} presents this connector's own configured identity ({@code
 *       serverSecret}) instead, naming the catalog user id the request should be evaluated as — for
 *       a recipient's read, made long after that user's own request (if there ever was one) is
 *       over, with no token of theirs available to present.
 * </ul>
 *
 * <p>Unity Catalog's JSON is snake_case, and the protocol this server speaks is not, so it is read
 * with a mapper of its own rather than the application's. Unknown fields are ignored: a catalog is
 * free to grow its responses, and a sharing server that fell over when it did would be a poor client.
 */
final class UnityCatalogClient {

  /** Header names Unity Catalog's on-behalf-of access reads; see {@code AuthDecorator} there. */
  private static final String SERVER_SECRET_HEADER = "X-OpenSharing-Server-Secret";

  private static final String ON_BEHALF_OF_HEADER = "X-OpenSharing-On-Behalf-Of";

  private static final Logger log = LoggerFactory.getLogger(UnityCatalogClient.class);

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .serializationInclusion(JsonInclude.Include.NON_NULL)
          .build();

  /**
   * A ceiling on how much of a failure body is repeated back. A Unity Catalog error is a line; a
   * proxy standing in front of one that has fallen over can answer with a page of HTML, and that
   * belongs in the log rather than in an error message.
   */
  private static final int MAX_ERROR_CHARS = 512;

  /**
   * A ceiling on how much of any answer is held in memory. A catalog describing a table, or a page of
   * them, runs to kilobytes; nothing legitimate approaches this. It is here because the sharing server
   * cannot be brought down by whatever is on the other end of that connection — a catalog gone wrong,
   * or something that is not a catalog at all — and a body read whole with no bound would let it.
   */
  private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

  private final URI baseUri;
  private final Duration requestTimeout;
  private final HttpClient http;
  private final String serverSecret;

  UnityCatalogClient(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
    this(baseUri, connectTimeout, requestTimeout, null);
  }

  /**
   * @param serverSecret this connector's own identity for {@link Credential.OnBehalfOf} calls, or
   *     null if it is never asked to make one — a caller reaching only for {@link
   *     Credential.BearerToken} never needs one configured at all.
   */
  UnityCatalogClient(URI baseUri, Duration connectTimeout, Duration requestTimeout, String serverSecret) {
    this.baseUri = withoutTrailingSlash(baseUri);
    this.requestTimeout = requestTimeout;
    this.serverSecret = serverSecret;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  URI baseUri() {
    return baseUri;
  }

  <T> T get(String path, Map<String, String> query, CatalogCaller caller, Class<T> type) {
    return send(request(path, query, caller).GET().build(), type, "GET " + path);
  }

  <T> T post(String path, Object body, CatalogCaller caller, Class<T> type) {
    HttpRequest request =
        request(path, Map.of(), caller)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(serialize(body), StandardCharsets.UTF_8))
            .build();
    return send(request, type, "POST " + path);
  }

  /**
   * Whose token this is, and — when {@code privilege} is given — whether they may do what they are
   * asking. Unlike every other call this client makes, the token here is not a {@link
   * CatalogCaller}'s: it is a live provider-admin request's own token, being authenticated for the
   * first time, not yet known to belong to anyone.
   *
   * @return empty when the catalog answers 401 or 403: the token itself is not one it recognizes,
   *     or it recognizes it but its own authorization layer rejects the caller outright (for
   *     example, a principal with no grants at all never reaches this server's own {authorized:
   *     false} response body — the catalog's blanket "#principal != null" check on this endpoint
   *     turns it away first, as 403). Either way that means exactly what it means for the catalog
   *     itself — reject the request, not something for this client to have an opinion about
   */
  Optional<AuthorizeResult> authorize(String bearerToken, String privilege) {
    HttpRequest request =
        HttpRequest.newBuilder(
                uriOf(
                    "/opensharing/authorize",
                    privilege == null ? Map.of() : query("privilege", privilege)))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
    try {
      return Optional.of(send(request, AuthorizeResult.class, "GET /opensharing/authorize"));
    } catch (UnityApiException e) {
      if (e.status() == 401 || e.status() == 403) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /** {@code {"user_id", "user_name", "authorized"}} from {@code POST /opensharing/authorize}. */
  record AuthorizeResult(String userId, String userName, boolean authorized) {}

  private HttpRequest.Builder request(String path, Map<String, String> query, CatalogCaller caller) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uriOf(path, query))
            .timeout(requestTimeout)
            .header("Accept", "application/json");
    return switch (caller.credential()) {
      case Credential.BearerToken(String token) -> request.header("Authorization", "Bearer " + token);
      case Credential.OnBehalfOf(String catalogUserId) -> {
        if (serverSecret == null || serverSecret.isBlank()) {
          throw new CatalogException(
              "asking the Unity Catalog on behalf of '"
                  + caller.name()
                  + "' needs this connector's own server identity configured"
                  + " (opensharing.catalog.unity.server-secret), and none is set");
        }
        yield request
            .header(SERVER_SECRET_HEADER, serverSecret)
            .header(ON_BEHALF_OF_HEADER, catalogUserId);
      }
      case Credential.None ignored ->
          throw new IllegalStateException(
              "the Unity Catalog connector was asked to make a request for '"
                  + caller.name()
                  + "' with no credential at all, which is a caller built for a different catalog"
                  + " connector reaching this one instead");
    };
  }

  private <T> T send(HttpRequest request, Class<T> type, String what) {
    int status;
    String body;
    try {
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      status = response.statusCode();
      body = bodyOf(response, what);
    } catch (IOException e) {
      log.error("The Unity Catalog at {} could not be reached for {}", baseUri, what, e);
      throw new CatalogException("the Unity Catalog could not be reached");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CatalogException("the request to the Unity Catalog was interrupted");
    }
    if (status / 100 != 2) {
      throw new UnityApiException(status, failureMessage(status, body, what));
    }
    return deserialize(body, type, what);
  }

  /**
   * The answer, up to {@link #MAX_BODY_BYTES}. One byte past it is enough to know the bound was
   * exceeded, and the rest is left unread: the stream is closed either way, which also tells the
   * client the connection is finished with rather than leaving it held.
   */
  private String bodyOf(HttpResponse<InputStream> response, String what) throws IOException {
    try (InputStream stream = response.body()) {
      byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        log.error(
            "The Unity Catalog answered {} with more than {} bytes, which is not an answer this "
                + "server will read",
            what,
            MAX_BODY_BYTES);
        throw new CatalogException("the Unity Catalog answered with more than this server will read");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  /**
   * What a failed Unity Catalog request is reported as, which is the status and nothing else.
   *
   * <p>The useful part — which request it was, and the catalog's own complaint — is logged instead of
   * returned, because this cannot tell who is asking. A data recipient reaches every one of these
   * paths, and they know a table only by the alias it is shared under: the internal name in the
   * request line is not theirs to learn, and neither is text the catalog wrote, which on a bad day is
   * whatever a proxy in front of it decided to serve. The status stays on the exception because that
   * is what the connector reads to tell a refusal from a breakage.
   *
   * <p>The statuses the connector goes on to explain are logged at debug, since it logs its own line
   * with the asset and caller in it; the rest are this server's problem and are logged as such.
   */
  private String failureMessage(int status, String body, String what) {
    String detail = errorMessage(body);
    if (status == 401 || status == 403 || status == 404) {
      log.debug("The Unity Catalog answered {} to {}: {}", status, what, detail);
    } else {
      log.error("The Unity Catalog answered {} to {}: {}", status, what, detail);
    }
    return "the Unity Catalog answered " + status + " to a request this server made";
  }

  private String errorMessage(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      UnityCatalogApi.ErrorResponse error =
          JSON.readValue(body, UnityCatalogApi.ErrorResponse.class);
      if (error.message() != null && !error.message().isBlank()) {
        return truncated(error.message());
      }
    } catch (IOException notTheApi) {
      // Something other than a Unity Catalog error body — a gateway's page, most likely. Whatever it
      // is, it is all there is to report.
    }
    return truncated(body.strip());
  }

  /** Cut on a character, not half of one: a lone surrogate would go on to be serialized as JSON. */
  private static String truncated(String value) {
    if (value.length() <= MAX_ERROR_CHARS) {
      return value;
    }
    int end = Character.isHighSurrogate(value.charAt(MAX_ERROR_CHARS - 1))
        ? MAX_ERROR_CHARS - 1
        : MAX_ERROR_CHARS;
    return value.substring(0, end) + "…";
  }

  private <T> T deserialize(String body, Class<T> type, String what) {
    try {
      T value = JSON.readValue(body, type);
      if (value == null) {
        throw unreadable(what, null);
      }
      return value;
    } catch (IOException e) {
      throw unreadable(what, e);
    }
  }

  /** Which request came back unreadable is for the log, for the reason {@link #failureMessage} is. */
  private static CatalogException unreadable(String what, Exception cause) {
    log.error("The Unity Catalog answered {} with a body this server could not read", what, cause);
    return new CatalogException("the Unity Catalog answered with a body this server could not read");
  }

  private String serialize(Object body) {
    try {
      return JSON.writeValueAsString(body);
    } catch (IOException e) {
      throw new CatalogException("could not build a Unity Catalog request body", e);
    }
  }

  /**
   * The base url with the path and query appended. Path segments arrive already escaped, since only
   * their author knows where one ends: an asset's full name is a single segment even though it has
   * dots in it, and encoding it here would be encoding the separators the caller put in.
   */
  private URI uriOf(String path, Map<String, String> query) {
    StringBuilder url = new StringBuilder(baseUri.toString()).append(path);
    if (!query.isEmpty()) {
      StringJoiner joiner = new StringJoiner("&", "?", "");
      query.forEach((key, value) -> joiner.add(encode(key) + "=" + encode(value)));
      url.append(joiner);
    }
    return URI.create(url.toString());
  }

  /** Percent-encoding for one url component, in the form a path or query wants it. */
  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Query parameters in the order given, since a url that varies only by order is a nuisance. */
  static Map<String, String> query(String... keysAndValues) {
    Map<String, String> query = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] != null) {
        query.put(keysAndValues[i], keysAndValues[i + 1]);
      }
    }
    return query;
  }

  private static URI withoutTrailingSlash(URI uri) {
    String value = uri.toString();
    return value.endsWith("/") ? URI.create(value.substring(0, value.length() - 1)) : uri;
  }
}
