package io.opensharing.asset.table.signer;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.SharedAccessProtocols;
import com.microsoft.azure.storage.StorageCredentialsSharedAccessSignature;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions;
import com.microsoft.azure.storage.blob.SharedAccessBlobPolicy;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.http.ApiException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
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
      Instant sasExpiry = credentials.expiration() == null ? expiration : credentials.expiration();
      return new SignedUrl(httpsUrl(path) + queryPrefix(sas) + stripQuestion(sas), sasExpiry);
    }
    String accountKey = credentials.credentials().get(StorageCredentials.AZURE_ACCOUNT_KEY);
    if (accountKey == null || accountKey.isBlank()) {
      throw ApiException.invalidParameter(
          "Azure credentials have neither a SAS token nor an account key");
    }
    try {
      return new SignedUrl(signedBlobUrl(path, accountKey, expiration), expiration);
    } catch (URISyntaxException | InvalidKeyException | StorageException | RuntimeException e) {
      throw ApiException.invalidParameter("Azure account key cannot sign a blob SAS");
    }
  }

  private static String signedBlobUrl(String path, String accountKey, Instant expiration)
      throws URISyntaxException, InvalidKeyException, StorageException {
    StoragePaths.AzureBlob parsed = StoragePaths.azureBlob(path);
    String[] hostParts = parsed.host().split("\\.", 3);
    if (hostParts.length != 3) {
      throw ApiException.invalidParameter(
          "Azure host is not account.endpoint.suffix: " + parsed.host());
    }
    CloudStorageAccount account =
        CloudStorageAccount.parse(
            String.join(
                ";",
                "DefaultEndpointsProtocol=https",
                "AccountName=" + hostParts[0],
                "AccountKey=" + accountKey,
                "EndpointSuffix=" + hostParts[2]));
    CloudBlockBlob blob =
        account
            .createCloudBlobClient()
            .getContainerReference(parsed.container())
            .getBlockBlobReference(parsed.blob());
    SharedAccessBlobPolicy policy = new SharedAccessBlobPolicy();
    policy.setPermissions(EnumSet.of(SharedAccessBlobPermissions.READ));
    policy.setSharedAccessExpiryTime(Date.from(expiration));
    String token =
        blob.generateSharedAccessSignature(
            policy,
            /* headers */ null,
            /* groupPolicyIdentifier */ null,
            /* ipRange */ null,
            SharedAccessProtocols.HTTPS_ONLY);
    return new StorageCredentialsSharedAccessSignature(token)
        .transformUri(blob.getUri())
        .toString();
  }

  private static String httpsUrl(String path) {
    StoragePaths.AzureBlob parsed = StoragePaths.azureBlob(path);
    String encodedBlob = encodePath(parsed.blob());
    return "https://"
        + parsed.host().replace(".dfs.", ".blob.")
        + "/"
        + parsed.container()
        + encodedBlob;
  }

  /** Percent-encode blob path segments the way a blob HTTPS URL does. */
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

  private static String queryPrefix(String sas) {
    return sas.startsWith("?") ? "" : "?";
  }

  private static String stripQuestion(String sas) {
    return sas.startsWith("?") ? sas.substring(1) : sas;
  }
}
