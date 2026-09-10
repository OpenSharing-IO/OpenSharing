package io.opensharing.config;

import io.opensharing.principal.PrincipalType;
import io.opensharing.runtime.HostingMode;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server configuration under the {@code opensharing} prefix. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  /** URL prefix the recipient-facing protocol endpoints are mounted under. */
  private String protocolPrefix = "/api/2.1/opensharing";

  private final Hosting hosting = new Hosting();

  private final Admin admin = new Admin();
  private final Provider provider = new Provider();
  private final Activation activation = new Activation();
  private final RecipientTokens recipientTokens = new RecipientTokens();
  private final AssetCredentials assetCredentials = new AssetCredentials();
  private final Pagination pagination = new Pagination();
  private final Storage storage = new Storage();
  private final Delta delta = new Delta();
  private final Catalog catalog = new Catalog();

  public String getProtocolPrefix() {
    return protocolPrefix;
  }

  public void setProtocolPrefix(String protocolPrefix) {
    this.protocolPrefix = prefix(protocolPrefix);
  }

  public Hosting getHosting() {
    return hosting;
  }

  /**
   * A url prefix without its trailing slash, kept that way here so that everything appending to
   * one — a filter's url pattern, an OpenAPI path match, a route the server builds — appends to a
   * known shape instead of each trimming first.
   */
  private static String prefix(String value) {
    return value != null && value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  public Admin getAdmin() {
    return admin;
  }

  public Provider getProvider() {
    return provider;
  }

  public Activation getActivation() {
    return activation;
  }

  public RecipientTokens getRecipientTokens() {
    return recipientTokens;
  }

  public AssetCredentials getAssetCredentials() {
    return assetCredentials;
  }

  public Pagination getPagination() {
    return pagination;
  }

  public Storage getStorage() {
    return storage;
  }

  public Delta getDelta() {
    return delta;
  }

  public Catalog getCatalog() {
    return catalog;
  }

  /** Whether OpenSharing runs standalone or embedded in a host process. */
  public static class Hosting {

    private Mode mode = Mode.STANDALONE;

    public Mode getMode() {
      return mode;
    }

    public void setMode(Mode mode) {
      this.mode = mode == null ? Mode.STANDALONE : mode;
    }

    public HostingMode toHostingMode() {
      return switch (getMode()) {
        case STANDALONE -> HostingMode.STANDALONE;
        case EMBEDDED -> HostingMode.EMBEDDED;
      };
    }

    public enum Mode {
      STANDALONE,
      EMBEDDED
    }
  }

  /** Provider principals recognized at startup. See {@link Provider} for the API's own settings. */
  public static class Admin {

    /**
     * Provider principals the server recognizes. Each entry is registered in the database at startup,
     * and its bearer token is both the admin login and the credential presented to the catalog.
     */
    private List<Principal> principals = List.of();

    public List<Principal> getPrincipals() {
      return principals;
    }

    public void setPrincipals(List<Principal> principals) {
      this.principals = principals == null ? List.of() : List.copyOf(principals);
    }

    /** One username and credential the server provisions at startup. */
    public static class Principal {

      private String name;
      private String bearerToken;
      private PrincipalType type;

      public String getName() {
        return name;
      }

      public void setName(String name) {
        this.name = name;
      }

      public String getBearerToken() {
        return bearerToken;
      }

      public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
      }

      public PrincipalType getType() {
        return type;
      }

      public void setType(PrincipalType type) {
        this.type = type;
      }
    }
  }

  /** Provider-admin API settings. */
  public static class Provider {

    /** URL prefix for the provider-admin API. */
    private String basePath = "/api/2.1/opensharing/provider";

    public String getBasePath() {
      return basePath;
    }

    public void setBasePath(String basePath) {
      this.basePath = prefix(basePath);
    }
  }

  /** One-time activation URLs that hand a profile file to a recipient. */
  public static class Activation {

    private String basePath = "/api/2.1/opensharing/activation";

    /** How long an unused activation link stays valid. */
    private Duration ttl = Duration.ofHours(72);

    /** Base URL recipients can reach, used to build activation URLs and the profile endpoint. */
    private String externalBaseUrl = "http://localhost:8080";

    public String getBasePath() {
      return basePath;
    }

    public void setBasePath(String basePath) {
      this.basePath = prefix(basePath);
    }

    public Duration getTtl() {
      return ttl;
    }

    public void setTtl(Duration ttl) {
      this.ttl = ttl;
    }

    public String getExternalBaseUrl() {
      return externalBaseUrl;
    }

    public void setExternalBaseUrl(String externalBaseUrl) {
      this.externalBaseUrl = externalBaseUrl;
    }
  }

  /** Bearer tokens issued to recipients. */
  public static class RecipientTokens {

    /** Lifetime applied when a token is minted without an explicit expiration. Null never expires. */
    private Duration defaultTtl = Duration.ofDays(90);

    /**
     * How long a superseded token keeps working when a rotation request does not say. Gives the
     * recipient a window to install the new profile file; zero cuts it off at once.
     */
    private Duration rotationGrace = Duration.ofHours(24);

    public Duration getDefaultTtl() {
      return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
      this.defaultTtl = defaultTtl;
    }

    public Duration getRotationGrace() {
      return rotationGrace;
    }

    public void setRotationGrace(Duration rotationGrace) {
      this.rotationGrace = rotationGrace;
    }
  }

  /** Lifetime requested from the catalog when vending storage credentials. */
  public static class AssetCredentials {

    private Duration ttl = Duration.ofHours(1);

    public Duration getTtl() {
      return ttl;
    }

    public void setTtl(Duration ttl) {
      this.ttl = ttl;
    }
  }

  /** Bounds on {@code maxResults} for list endpoints. */
  public static class Pagination {

    private int defaultMaxResults = 500;
    private int maxMaxResults = 1000;

    public int getDefaultMaxResults() {
      return defaultMaxResults;
    }

    public void setDefaultMaxResults(int defaultMaxResults) {
      this.defaultMaxResults = defaultMaxResults;
    }

    public int getMaxMaxResults() {
      return maxMaxResults;
    }

    public void setMaxMaxResults(int maxMaxResults) {
      this.maxMaxResults = maxMaxResults;
    }
  }

  /** Reaching the storage a shared table lives in, whatever the table's format. */
  public static class Storage {

    /** Region used to sign S3 urls when the catalog's credentials do not name one. */
    private String s3Region = "us-east-1";

    /**
     * Path to a Google service account key file, whose private key signs urls for {@code gs} paths.
     *
     * <p>Blank leaves {@code GOOGLE_APPLICATION_CREDENTIALS} to say where the key is, which is how
     * the reference sharing server is pointed at one. With neither, no url is signed for Google
     * storage and such a table is served in dir access mode only.
     */
    private String gcsServiceAccountKeyFile;

    public String getS3Region() {
      return s3Region;
    }

    public void setS3Region(String s3Region) {
      this.s3Region = s3Region;
    }

    public String getGcsServiceAccountKeyFile() {
      return gcsServiceAccountKeyFile;
    }

    public void setGcsServiceAccountKeyFile(String gcsServiceAccountKeyFile) {
      this.gcsServiceAccountKeyFile = gcsServiceAccountKeyFile;
    }
  }

  /** Serving Delta tables by url: reading their log and handing out signed file urls. */
  public static class Delta {

    /**
     * Whether to read Delta logs and serve {@code query}. Turning this off leaves dir access mode,
     * where a recipient reads the log itself with vended credentials, and stops {@code url} being
     * advertised on any table.
     */
    private boolean urlAccessEnabled = true;

    /** How long a signed file url stays valid. Never outlives the credentials it was signed with. */
    private Duration urlTtl = Duration.ofHours(1);

    public boolean isUrlAccessEnabled() {
      return urlAccessEnabled;
    }

    public void setUrlAccessEnabled(boolean urlAccessEnabled) {
      this.urlAccessEnabled = urlAccessEnabled;
    }

    public Duration getUrlTtl() {
      return urlTtl;
    }

    public void setUrlTtl(Duration urlTtl) {
      this.urlTtl = urlTtl;
    }
  }

  /** Which {@code CatalogConnector} to run with, and what it needs to reach its catalog. */
  public static class Catalog {

    /** Connector id: {@code local} or {@code unity}. */
    private String type = "local";

    private final Local local = new Local();
    private final Unity unity = new Unity();

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public Local getLocal() {
      return local;
    }

    public Unity getUnity() {
      return unity;
    }

    /** File-backed connector for local development. */
    public static class Local {

      /** Spring resource location of the catalog file, e.g. {@code file:./catalog.yml}. */
      private String file = "classpath:local-catalog.yml";

      public String getFile() {
        return file;
      }

      public void setFile(String file) {
        this.file = file;
      }
    }

    /** Open-source Unity Catalog, over its REST API. */
    public static class Unity {

      /**
       * Base url of the Unity Catalog API, including the path it is served under, such as {@code
       * http://localhost:8081/api/2.1/unity-catalog}. Required to run with this connector.
       *
       * <p>No credential goes with it. Each request is made as the principal it concerns, with the
       * credential held for them, so what this server can see in the catalog is never more than what
       * the provider asking could see themselves.
       */
      private String uri;

      /** How long to wait for the catalog to accept a connection. */
      private Duration connectTimeout = Duration.ofSeconds(5);

      /** How long to wait for a response, once connected. */
      private Duration requestTimeout = Duration.ofSeconds(30);

      /**
       * This server's own identity, presented instead of a bearer token when it has to ask the
       * catalog on behalf of a share's owner (a recipient's read, made long after the owner's own
       * request is over) rather than as a live caller. Must match the secret Unity Catalog itself
       * is configured with ({@code server.opensharing.server-secret}). Only required if a recipient
       * is ever actually served against this catalog; blank otherwise.
       */
      private String serverSecret;

      public String getUri() {
        return uri;
      }

      public void setUri(String uri) {
        this.uri = uri;
      }

      public Duration getConnectTimeout() {
        return connectTimeout;
      }

      public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
      }

      public Duration getRequestTimeout() {
        return requestTimeout;
      }

      public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
      }

      public String getServerSecret() {
        return serverSecret;
      }

      public void setServerSecret(String serverSecret) {
        this.serverSecret = serverSecret;
      }
    }
  }
}
