package io.opensharing.asset.storage;

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

/**
 * A Google service account's private key, and the address that names it.
 *
 * <p>Signing a url for Google storage needs a key of the server's own, unlike S3 and Azure, whose
 * urls are derived from the credential the catalog vended. Google's signature is RSA over the
 * request, so nothing but a private key produces one, and an OAuth access token — all a catalog
 * vends — cannot be put in a url at all. The reference sharing server resolves the same problem the
 * same way, by holding a service account key for signing.
 *
 * <p>It signs and nothing else: no read goes through it, so what this key can reach is not what any
 * table is read with. A deployment that would rather not hold one leaves it unset and serves Google
 * storage in dir access mode, where the recipient gets the vended token instead.
 */
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

  /**
   * Reads the key file the configuration names, or the one {@code GOOGLE_APPLICATION_CREDENTIALS}
   * names when it does not.
   *
   * <p>The two are not held to the same standard, and deliberately. A configured path is a
   * deployment asking for signing, so a file it cannot use is a mistake to hear about at startup
   * rather than on a recipient's first read. The environment variable is ambient — it is often a
   * user credential written by {@code gcloud auth application-default login}, which has no private
   * key in it at all — and a server that shares nothing on Google storage should not be stopped by
   * what happens to be exported around it. So that one is taken if it works and logged if it does
   * not.
   *
   * @return null when nothing names a usable key, which serves Google storage in dir access mode
   * @throws IllegalStateException when the configured path names a file that is not a usable key
   */
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
          "GOOGLE_APPLICATION_CREDENTIALS names nothing this can sign a url with ({}), so no url is "
              + "signed for gs paths; set storage.gcs-service-account-key-file to insist on one",
          e.getMessage());
      return null;
    }
  }

  private static GcsSigningKey read(String file) {
    String json;
    try {
      json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(
          "the Google service account key file '" + file + "' cannot be read", e);
    }
    return parse(json, file);
  }

  static GcsSigningKey parse(String json, String source) {
    JsonNode key;
    try {
      key = new ObjectMapper().readTree(json);
    } catch (IOException e) {
      throw new IllegalStateException("'" + source + "' is not a JSON service account key", e);
    }
    String email = text(key, "client_email", source);
    return new GcsSigningKey(email, privateKeyOf(text(key, "private_key", source), source));
  }

  private static String text(JsonNode key, String field, String source) {
    JsonNode value = key.get(field);
    if (value == null || value.asText().isBlank()) {
      throw new IllegalStateException(
          "the service account key '" + source + "' states no '" + field + "'");
    }
    return value.asText();
  }

  /** The key as PEM-wrapped PKCS#8, which is how a service account key file carries it. */
  private static PrivateKey privateKeyOf(String pem, String source) {
    String base64 =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
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
