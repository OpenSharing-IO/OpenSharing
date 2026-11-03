package io.opensharing.asset.table.signer;

import io.opensharing.asset.table.StorageLocations;
import io.opensharing.catalog.CloudProvider;
import io.opensharing.catalog.StorageCredentials;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** AWS SDK GET presigning for S3 and R2 object URLs. */
final class S3UrlSigner implements UrlSigner {

  private static final Set<String> SCHEMES =
      Stream.concat(StorageLocations.S3_SCHEMES.stream(), StorageLocations.R2_SCHEMES.stream())
          .collect(Collectors.toUnmodifiableSet());

  @Override
  public Set<String> schemes() {
    return SCHEMES;
  }

  @Override
  public SigningSession open(StorageCredentials credentials) {
    AwsCredentials aws = awsCredentials(credentials);
    S3Presigner.Builder builder =
        S3Presigner.builder().credentialsProvider(StaticCredentialsProvider.create(aws));
    if (credentials.provider() == CloudProvider.R2) {
      // R2 serves the S3 API at the account host its r2:// prefix names, in region auto.
      String host = StorageLocations.r2Object(credentials.prefix()).host();
      builder
          .endpointOverride(URI.create("https://" + host))
          .region(Region.of("auto"))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    } else {
      // Without a vended region, the SDK's default region provider chain picks one.
      String region = credentials.credentials().get(StorageCredentials.REGION);
      if (region != null && !region.isBlank()) {
        builder.region(Region.of(region));
      }
    }
    S3Presigner presigner = builder.build();
    return new SigningSession() {
      @Override
      public SignedUrl sign(String path, Duration ttl) {
        String[] bucketAndKey = bucketAndKey(path);
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

  private static String[] bucketAndKey(String path) {
    if (path.startsWith("r2://")) {
      StorageLocations.R2Object r2 = StorageLocations.r2Object(path);
      return new String[] {r2.bucket(), r2.key()};
    }
    return StorageLocations.bucketAndKey(path);
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
