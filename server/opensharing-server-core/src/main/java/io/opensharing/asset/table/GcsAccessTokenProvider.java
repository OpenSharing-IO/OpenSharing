package io.opensharing.asset.table;

import com.google.cloud.hadoop.util.AccessTokenProvider;
import java.io.IOException;
import org.apache.hadoop.conf.Configuration;

/** Supplies one catalog-vended OAuth token to the Hadoop GCS connector. */
public final class GcsAccessTokenProvider implements AccessTokenProvider {

  static final String TOKEN = "opensharing.gcs.oauth-token";
  static final String EXPIRATION = "opensharing.gcs.oauth-token-expiration";

  private Configuration configuration;

  @Override
  public AccessToken getAccessToken() {
    return new AccessToken(
        configuration.get(TOKEN), configuration.getLong(EXPIRATION, 0));
  }

  @Override
  public void refresh() throws IOException {
    // The sharing request is shorter than the credential TTL; a new request obtains a fresh token.
  }

  @Override
  public void setConf(Configuration configuration) {
    this.configuration = configuration;
  }

  @Override
  public Configuration getConf() {
    return configuration;
  }
}
