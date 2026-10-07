package io.opensharing.asset.table;

import io.opensharing.asset.table.DeltaSharingCapabilities.ResponseFormat;
import io.opensharing.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Parses {@code fileidhash} and hashes file paths the way the OSS server does: {@code parquet} is
 * MD5, {@code delta} is SHA-256. When the header is omitted, the hash follows the responded format.
 */
public final class FileIdHash {

  public static final String HEADER = "fileidhash";

  private static final Set<String> VALID = Set.of("parquet", "delta");

  private FileIdHash() {}

  /** Lowercase {@code parquet} or {@code delta}, or {@code null} when the header is absent. */
  public static String parse(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String normalized = header.trim().toLowerCase(Locale.ROOT);
    if (!VALID.contains(normalized)) {
      throw ApiException.invalidParameter(
          "Unsupported fileidhash: '" + header + "'. Supported: parquet, delta");
    }
    return normalized;
  }

  public static String hash(String path, String fileIdHash, ResponseFormat format) {
    boolean sha256 =
        "delta".equals(fileIdHash)
            || (fileIdHash == null && format == ResponseFormat.DELTA);
    return digest(sha256 ? "SHA-256" : "MD5", path);
  }

  private static String digest(String algorithm, String path) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(path.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
