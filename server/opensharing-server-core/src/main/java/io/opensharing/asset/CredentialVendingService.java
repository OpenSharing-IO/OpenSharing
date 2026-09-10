package io.opensharing.asset;

import io.opensharing.catalog.CredentialRequest;
import io.opensharing.catalog.ResolvedAsset;
import io.opensharing.catalog.StorageCredentialKeys;
import io.opensharing.catalog.StorageCredentials;
import io.opensharing.catalog.StorageOperation;
import io.opensharing.config.OpenSharingProperties;
import io.opensharing.http.ApiException;
import io.opensharing.protocol.AwsCredentials;
import io.opensharing.protocol.AzureUserDelegationSas;
import io.opensharing.protocol.GcpOauthToken;
import io.opensharing.protocol.R2Credentials;
import io.opensharing.protocol.TemporaryCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Vends read-only, TTL-bounded storage credentials for a shared object. The object is re-resolved in
 * the catalog first so credentials are always scoped to where it lives right now.
 */
@Service
public class CredentialVendingService {

  private static final Logger log = LoggerFactory.getLogger(CredentialVendingService.class);

  private final AssetResolutionService resolution;
  private final Duration ttl;

  public CredentialVendingService(
      AssetResolutionService resolution, OpenSharingProperties properties) {
    this.resolution = resolution;
    this.ttl = properties.getAssetCredentials().getTtl();
  }

  /**
   * <p>A table with no grant behind it does not offer dir mode at all, so reaching the refusal below
   * means a client asked for a mode the table never listed. It is still answered in words rather than
   * left to fail on an absent credential, because nothing stops a client from calling this endpoint
   * without reading what the table offers.
   *
   * @param requestedLocation an explicit location from the request body, which must be the object's
   *     own location or one of its auxiliary locations
   */
  public TemporaryCredentials vend(SharedDataObjectEntity object, String requestedLocation) {
    ResolvedAsset resolved = resolution.resolveForServing(object);
    String location = resolveRequestedLocation(resolved, requestedLocation);
    StorageCredentials minted = mint(object, resolved, location);
    if (minted == null) {
      throw ApiException.notImplemented(
          "'"
              + object.getSharedAs()
              + "' is not offered in dir access mode, because it is on storage the server reaches "
              + "without a credential and the catalog holds none to vend for it; read it by url "
              + "access mode, which is the mode its accessModes lists");
    }
    return toProtocol(minted, object);
  }

  /**
   * The credentials themselves, rather than their wire form. Reading a table's Delta log server-side
   * goes through here, so the server reads with exactly the access it would hand a recipient.
   *
   * <p>Takes the object as well as what resolving it produced, because minting is asked of the
   * catalog as the owner of the share the object is in, and only the object knows which share that
   * is.
   *
   * @return null for a table the catalog vends nothing for because nothing is needed — one on local
   *     storage — which a caller that reads the table itself can carry on with, and one that has to
   *     hand something to a recipient cannot
   */
  public StorageCredentials mint(
      SharedDataObjectEntity object, ResolvedAsset resolved, String location) {
    return resolution.vendCredentials(
        object,
        new CredentialRequest(
            resolved.type(),
            resolved.identifier(),
            resolved.catalogAssetId(),
            location,
            StorageOperation.READ,
            ttl));
  }

  private String resolveRequestedLocation(ResolvedAsset resolved, String requestedLocation) {
    if (requestedLocation == null || requestedLocation.isBlank()) {
      if (resolved.storageLocation() == null || resolved.storageLocation().isBlank()) {
        throw ApiException.notFound(
            "the catalog no longer reports a storage location for '" + resolved.identifier() + "'");
      }
      return resolved.storageLocation();
    }
    List<String> allowed =
        Stream.concat(
                Stream.ofNullable(resolved.storageLocation()),
                resolved.auxiliaryLocations().stream())
            .toList();
    if (!allowed.contains(requestedLocation)) {
      throw ApiException.invalidParameter(
          "location '" + requestedLocation + "' is not a location of this object");
    }
    return requestedLocation;
  }

  private TemporaryCredentials toProtocol(
      StorageCredentials credentials, SharedDataObjectEntity object) {
    long expiration = expirationMillis(credentials);
    log.debug(
        "Vended {} credentials for {} '{}' scoped to {} until {}",
        credentials.provider(),
        object.getType(),
        object.getName(),
        credentials.prefix(),
        Instant.ofEpochMilli(expiration));
    return switch (credentials.provider()) {
      case AWS ->
          TemporaryCredentials.aws(
              new AwsCredentials(
                  credentials.require(StorageCredentialKeys.ACCESS_KEY_ID),
                  credentials.require(StorageCredentialKeys.SECRET_ACCESS_KEY),
                  credentials.require(StorageCredentialKeys.SESSION_TOKEN)),
              expiration);
      case R2 ->
          TemporaryCredentials.r2(
              new R2Credentials(
                  credentials.require(StorageCredentialKeys.ACCESS_KEY_ID),
                  credentials.require(StorageCredentialKeys.SECRET_ACCESS_KEY),
                  credentials.require(StorageCredentialKeys.SESSION_TOKEN)),
              expiration);
      case AZURE ->
          TemporaryCredentials.azure(
              new AzureUserDelegationSas(credentials.require(StorageCredentialKeys.SAS_TOKEN)),
              expiration);
      case GCP ->
          TemporaryCredentials.gcp(
              new GcpOauthToken(credentials.require(StorageCredentialKeys.OAUTH_TOKEN)), expiration);
    };
  }

  private long expirationMillis(StorageCredentials credentials) {
    Instant expiration = credentials.expiration();
    if (expiration == null) {
      return Instant.now().plus(ttl).toEpochMilli();
    }
    return expiration.toEpochMilli();
  }
}
