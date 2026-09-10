package io.opensharing.asset.storage;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import io.opensharing.http.ErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Reads one small file out of a shared table's storage, for when the server has to look inside a
 * table to answer a request — an Iceberg table's metadata JSON, which a recipient is given the
 * contents of rather than a pointer to.
 *
 * <p>It reads through {@link UrlSigners}, so the server reaches a file the same way it lets a
 * recipient reach one: sign it from the credentials the catalog minted, then fetch that url over
 * plain HTTPS. Nothing else is needed to reach a cloud store — no filesystem driver, no cloud SDK —
 * and the read cannot outreach the grant it was signed from.
 */
@Component
public class StorageReader {

  private static final Logger log = LoggerFactory.getLogger(StorageReader.class);

  /** Long enough to fetch one file, short enough that the url is useless if it leaks into a log. */
  private static final Duration URL_TTL = Duration.ofMinutes(2);

  /**
   * A ceiling on what the server will hold in memory for a caller. An Iceberg metadata JSON is
   * kilobytes for most tables and megabytes for a wide one with long history, so this is far above
   * anything real and only there to bound a pathological file.
   */
  private static final long MAX_BYTES = 32L * 1024 * 1024;

  private final UrlSigners signers;
  private final HttpClient http;

  public StorageReader(UrlSigners signers) {
    this.signers = signers;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * @param path an absolute storage path
   * @param credentials the catalog's grant for the location that path is under
   * @throws ApiException if the file is missing, too large, or its storage cannot be reached
   */
  public byte[] read(String path, StorageCredentials credentials) {
    URI url = URI.create(signers.sign(path, credentials, URL_TTL).url());
    return "file".equalsIgnoreCase(url.getScheme()) ? fromDisk(path, url) : fetch(path, url);
  }

  private byte[] fromDisk(String path, URI url) {
    try {
      return bounded(path, Files.newInputStream(Path.of(url)));
    } catch (NoSuchFileException e) {
      throw ApiException.notFound("'" + path + "' does not exist in the table's storage");
    } catch (IOException e) {
      log.warn("Could not read '{}'", path, e);
      throw unreachable(path, rootMessage(e));
    }
  }

  private byte[] fetch(String path, URI url) {
    HttpRequest request =
        HttpRequest.newBuilder(url).GET().timeout(Duration.ofSeconds(30)).build();
    try {
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() == 404) {
          throw ApiException.notFound("'" + path + "' does not exist in the table's storage");
        }
        if (response.statusCode() != 200) {
          throw unreachable(path, "its storage answered " + response.statusCode());
        }
        return bounded(path, body);
      }
    } catch (IOException e) {
      log.warn("Could not read '{}' from its storage", path, e);
      throw unreachable(path, rootMessage(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unreachable(path, "the read was interrupted");
    }
  }

  /** Reads a byte past the ceiling, which is the only way to tell a file at it from one over it. */
  private static byte[] bounded(String path, InputStream stream) throws IOException {
    try (stream) {
      byte[] bytes = stream.readNBytes((int) (MAX_BYTES + 1));
      if (bytes.length > MAX_BYTES) {
        throw ApiException.invalidParameter(
            "'" + path + "' is larger than the " + MAX_BYTES + " bytes this server will read");
      }
      return bytes;
    }
  }

  /**
   * The innermost complaint, which is the one that says what went wrong; the wrappers around it name
   * only the layers it came up through, and a caller reading the message cares about neither.
   */
  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }

  private static ApiException unreachable(String path, String because) {
    return new ApiException(
        HttpStatus.BAD_GATEWAY,
        ErrorCodes.INTERNAL_ERROR,
        "'" + path + "' could not be read: " + because);
  }
}
