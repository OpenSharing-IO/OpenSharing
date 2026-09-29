package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** AWS SDK GET presigning for S3 object URLs. */
@Component
public class S3UrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("s3", "s3a", "s3n");
  }

  @Override
  public SignedUrl sign(String path, StorageCredentials credentials, Duration ttl) {
    String[] bucketAndKey = StoragePaths.bucketAndKey(path);
    AwsCredentials aws = awsCredentials(credentials);
    Instant expiration = Instant.now().plus(ttl);
    try (S3Presigner presigner =
        S3Presigner.builder()
            .region(Region.of(credentials.require(StorageCredentials.REGION)))
            .credentialsProvider(StaticCredentialsProvider.create(aws))
            .build()) {
      String url =
          presigner
              .presignGetObject(
                  GetObjectPresignRequest.builder()
                      .signatureDuration(ttl)
                      .getObjectRequest(
                          GetObjectRequest.builder()
                              .bucket(bucketAndKey[0])
                              .key(bucketAndKey[1])
                              .build())
                      .build())
              .url()
              .toString();
      return new SignedUrl(url, expiration);
    }
  }

  private static AwsCredentials awsCredentials(StorageCredentials credentials) {
    String session = credentials.credentials().get(StorageCredentials.SESSION_TOKEN);
    if (session != null && !session.isBlank()) {
      return AwsSessionCredentials.create(
          credentials.require(StorageCredentials.ACCESS_KEY_ID),
          credentials.require(StorageCredentials.SECRET_ACCESS_KEY),
          session);
    }
    return AwsBasicCredentials.create(
        credentials.require(StorageCredentials.ACCESS_KEY_ID),
        credentials.require(StorageCredentials.SECRET_ACCESS_KEY));
  }
}
