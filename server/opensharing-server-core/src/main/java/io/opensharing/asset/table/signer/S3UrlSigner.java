package io.opensharing.asset.table.signer;

import io.opensharing.catalog.StorageCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** AWS SDK GET presigning for S3 object URLs. */
final class S3UrlSigner implements UrlSigner {

  @Override
  public Set<String> schemes() {
    return Set.of("s3", "s3a", "s3n");
  }

  @Override
  public SigningSession open(StorageCredentials credentials) {
    AwsCredentials aws = awsCredentials(credentials);
    S3Presigner.Builder builder =
        S3Presigner.builder().credentialsProvider(StaticCredentialsProvider.create(aws));
    // Without a vended region, the SDK's default region provider chain picks one.
    String region = credentials.credentials().get(StorageCredentials.REGION);
    if (region != null && !region.isBlank()) {
      builder.region(Region.of(region));
    }
    S3Presigner presigner = builder.build();
    return new SigningSession() {
      @Override
      public SignedUrl sign(String path, Duration ttl) {
        String[] bucketAndKey = StoragePaths.bucketAndKey(path);
        Instant expiration = Instant.now().plus(ttl);
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

      @Override
      public void close() {
        presigner.close();
      }
    };
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
