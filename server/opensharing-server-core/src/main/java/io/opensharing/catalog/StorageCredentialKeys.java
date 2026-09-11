package io.opensharing.catalog;

/** Keys used in {@link StorageCredentials#credentials()}. */
public final class StorageCredentialKeys {

  public static final String ACCESS_KEY_ID = "accessKeyId";
  public static final String SECRET_ACCESS_KEY = "secretAccessKey";
  public static final String SESSION_TOKEN = "sessionToken";
  public static final String SAS_TOKEN = "sasToken";
  public static final String OAUTH_TOKEN = "oauthToken";
  public static final String REGION = "region";

  private StorageCredentialKeys() {}
}
