package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Azure blob URLs: append a catalog SAS when present, otherwise mint a read-only blob SAS from an
 * account key (local integration tests).
 */
@Component
public class AzureSasUrlSigner implements UrlSigner {

  private static final String SAS_VERSION = "2020-12-06";
  private static final DateTimeFormatter EXPIRY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  @Override
  public Set<String> schemes() {
    return Set.of("abfs", "abfss", "wasb", "wasbs");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    Instant expiration = Instant.now().plus(ttl);
    String sas = credentials.credentials().get(StorageCredentials.SAS_TOKEN);
    if (sas != null && !sas.isBlank()) {
      Instant sasExpiry = credentials.expiration() == null ? expiration : credentials.expiration();
      return new SignedUrl(httpsUrl(path) + queryPrefix(sas) + stripQuestion(sas), sasExpiry);
    }
    String accountKey = credentials.credentials().get(StorageCredentials.AZURE_ACCOUNT_KEY);
    if (accountKey == null || accountKey.isBlank()) {
      throw ApiException.invalidParameter(
          "Azure credentials have neither a SAS token nor an account key");
    }
    return new SignedUrl(httpsUrl(path) + "?" + blobSas(path, accountKey, expiration), expiration);
  }

  private static String httpsUrl(String path) {
    URI uri = URI.create(path);
    String container = uri.getUserInfo();
    String host = uri.getHost();
    if (container == null || host == null) {
      throw ApiException.invalidParameter(
          "'" + path + "' is not an Azure path of the form scheme://container@account.host/blob");
    }
    String blob = uri.getPath() == null ? "" : uri.getPath();
    return "https://" + host.replace(".dfs.", ".blob.") + "/" + container + blob;
  }

  private static String blobSas(String path, String accountKey, Instant expiration) {
    URI uri = URI.create(path);
    String account = uri.getHost().split("\\.")[0];
    String container = uri.getUserInfo();
    String blob = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    String se = EXPIRY.format(expiration);
    String canonicalizedResource = "/blob/" + account + "/" + container + "/" + blob;
    String stringToSign =
        String.join(
            "\n",
            "r",
            "",
            se,
            canonicalizedResource,
            "",
            "",
            "https",
            SAS_VERSION,
            "b",
            "",
            "",
            "",
            "",
            "",
            "",
            "");
    String sig = S3UrlSigner.encode(hmac(accountKey, stringToSign));
    return "sv="
        + SAS_VERSION
        + "&spr=https&se="
        + S3UrlSigner.encode(se)
        + "&sr=b&sp=r&sig="
        + sig;
  }

  private static String hmac(String accountKey, String stringToSign) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(Base64.getDecoder().decode(accountKey), "HmacSHA256"));
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
      throw ApiException.invalidParameter("Azure account key cannot sign a blob SAS");
    }
  }

  private static String queryPrefix(String sas) {
    return sas.startsWith("?") ? "" : "?";
  }

  private static String stripQuestion(String sas) {
    return sas.startsWith("?") ? sas.substring(1) : sas;
  }
}
