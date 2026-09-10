package io.opensharing.asset.storage;

import com.google.cloud.hadoop.util.AccessTokenProvider;
import org.apache.hadoop.conf.Configuration;

/**
 * Hands Google's Hadoop connector the OAuth token the catalog minted for one table, which is how a
 * Delta log on Google Cloud Storage gets read with the same access a recipient is given.
 *
 * <p>The connector takes credentials only through a provider class it instantiates itself, so the
 * token cannot be passed in directly: it travels in the {@link Configuration} the read is made with,
 * under a key of this server's own, and is picked up here. Each read builds its own configuration, so
 * one table's token is never visible to another's.
 */
public class VendedGcsToken implements AccessTokenProvider {

  /** Where {@link #setConf} looks for the token, set beside the connector's own settings. */
  public static final String TOKEN = "opensharing.gcs.oauth.token";

  /** When the token stops working, in epoch millis, so the connector can stop using it. */
  public static final String EXPIRES_AT = "opensharing.gcs.oauth.expires-at";

  private Configuration conf;
  private AccessToken token;

  @Override
  public AccessToken getAccessToken() {
    return token;
  }

  /**
   * There is nothing to refresh: a vended token is as long-lived as the grant behind it, and only the
   * catalog can mint another. A read that outlives its token fails, and the next read gets a new one.
   */
  @Override
  public void refresh() {}

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
    this.token = new AccessToken(conf.get(TOKEN), conf.getLong(EXPIRES_AT, Long.MAX_VALUE));
  }

  @Override
  public Configuration getConf() {
    return conf;
  }
}
