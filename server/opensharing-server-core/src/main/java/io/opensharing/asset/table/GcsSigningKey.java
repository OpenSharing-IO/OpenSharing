package io.opensharing.asset.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Service-account private key used to V4-sign GCS object URLs. */
final class GcsSigningKey {

  private static final Logger log = LoggerFactory.getLogger(GcsSigningKey.class);

  private final String clientEmail;
  private final PrivateKey privateKey;

  private GcsSigningKey(String clientEmail, PrivateKey privateKey) {
    this.clientEmail = clientEmail;
    this.privateKey = privateKey;
  }

  String clientEmail() {
    return clientEmail;
  }

  PrivateKey privateKey() {
    return privateKey;
  }

  static GcsSigningKey configured(String path, String fromEnvironment) {
    if (path != null && !path.isBlank()) {
      return read(path.trim());
    }
    if (fromEnvironment == null || fromEnvironment.isBlank()) {
      return null;
    }
    try {
      return read(fromEnvironment.trim());
    } catch (IllegalStateException e) {
      log.info(
          "GOOGLE_APPLICATION_CREDENTIALS names nothing this can sign a url with ({}), so GCS urls "
              + "are signed from catalog-vended key files when present",
          e.getMessage());
      return null;
    }
  }

  static GcsSigningKey fromFile(String file) {
    return file == null || file.isBlank() ? null : read(file.trim());
  }

  private static GcsSigningKey read(String file) {
    try {
      return parse(Files.readString(Path.of(file), StandardCharsets.UTF_8), file);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(
          "the Google service account key file '" + file + "' cannot be read", e);
    }
  }

  static GcsSigningKey parse(String json, String source) {
    JsonNode key;
    try {
      key = new ObjectMapper().readTree(json);
    } catch (IOException e) {
      throw new IllegalStateException("'" + source + "' is not a JSON service account key", e);
    }
    return new GcsSigningKey(
        text(key, "client_email", source), privateKeyOf(text(key, "private_key", source), source));
  }

  private static String text(JsonNode key, String field, String source) {
    JsonNode value = key.get(field);
    if (value == null || value.asText().isBlank()) {
      throw new IllegalStateException(
          "the service account key '" + source + "' states no '" + field + "'");
    }
    return value.asText();
  }

  private static PrivateKey privateKeyOf(String pem, String source) {
    String keyType = "PRIVATE" + " KEY";
    String base64 =
        pem.replace("-----BEGIN " + keyType + "-----", "")
            .replace("-----END " + keyType + "-----", "")
            .replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "the private key in '" + source + "' is not a PKCS#8 RSA key", e);
    }
  }
}
