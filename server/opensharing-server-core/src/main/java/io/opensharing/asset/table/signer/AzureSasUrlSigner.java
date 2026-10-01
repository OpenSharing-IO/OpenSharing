package io.opensharing.asset.table.signer;

import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.common.sas.SasProtocol;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Azure blob URLs: append a catalog SAS when present, otherwise mint a read-only HTTPS blob SAS
 * from an account key.
 */
@Component
public class AzureSasUrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("abfs", "abfss", "wasb", "wasbs");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    Instant expiration = Instant.now().plus(ttl);
    String sas = credentials.credentials().get(StorageCredentials.SAS_TOKEN);
    if (sas != null && !sas.isBlank()) {
      // Catalog SAS is already minted; its lifetime is credentials.expiration(), not the sign TTL.
      Instant sasExpiry = credentials.expiration() == null ? expiration : credentials.expiration();
      return new SignedUrl(httpsUrl(path) + withQuery(sas), sasExpiry);
    }
    String accountKey = credentials.credentials().get(StorageCredentials.AZURE_ACCOUNT_KEY);
    if (accountKey == null || accountKey.isBlank()) {
      throw ApiException.invalidParameter(
          "Azure credentials have neither a SAS token nor an account key");
    }
    try {
      return new SignedUrl(httpsUrl(path) + withQuery(blobSas(path, accountKey, expiration)), expiration);
    } catch (RuntimeException e) {
      throw ApiException.invalidParameter("Azure account key cannot sign a blob SAS");
    }
  }

  private static String blobSas(String path, String accountKey, Instant expiration) {
    StoragePaths.AzureBlob parsed = StoragePaths.azureBlob(path);
    String account = parsed.host().split("\\.")[0];
    return new BlobServiceSasSignatureValues(
            OffsetDateTime.ofInstant(expiration, ZoneOffset.UTC),
            new BlobSasPermission().setReadPermission(true))
        .setProtocol(SasProtocol.HTTPS_ONLY)
        .setContainerName(parsed.container())
        .setBlobName(parsed.blob())
        .generateSasQueryParameters(new StorageSharedKeyCredential(account, accountKey))
        .encode();
  }

  private static String httpsUrl(String path) {
    StoragePaths.AzureBlob parsed = StoragePaths.azureBlob(path);
    return "https://"
        + parsed.host().replace(".dfs.", ".blob.")
        + "/"
        + parsed.container()
        + encodePath(parsed.blob());
  }

  static String encodePath(String blob) {
    StringBuilder path = new StringBuilder();
    for (String segment : blob.split("/", -1)) {
      path.append('/').append(percentEncode(segment));
    }
    return path.toString();
  }

  private static String percentEncode(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  private static String withQuery(String sas) {
    return sas.startsWith("?") ? sas : "?" + sas;
  }
}
