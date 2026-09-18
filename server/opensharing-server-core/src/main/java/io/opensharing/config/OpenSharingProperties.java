package io.opensharing.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenSharing server configuration. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  private String protocolPrefix = "/api/1.0/opensharing";
  private String activationPrefix = "/api/1.0/opensharing/activations";
  private final Hosting hosting = new Hosting();
  private final Provider provider = new Provider();
  private final RecipientTokens recipientTokens = new RecipientTokens();
  private final Pagination pagination = new Pagination();
  private final Catalog catalog = new Catalog();

  public String getProtocolPrefix() {
    return protocolPrefix;
  }

  public void setProtocolPrefix(String protocolPrefix) {
    this.protocolPrefix = prefix(protocolPrefix);
  }

  public String getActivationPrefix() {
    return activationPrefix;
  }

  public void setActivationPrefix(String activationPrefix) {
    this.activationPrefix = prefix(activationPrefix);
  }

  public Hosting getHosting() {
    return hosting;
  }

  public Provider getProvider() {
    return provider;
  }

  public RecipientTokens getRecipientTokens() {
    return recipientTokens;
  }

  public Pagination getPagination() {
    return pagination;
  }

  public Catalog getCatalog() {
    return catalog;
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

  /** Standalone process vs embedded in a host. */
  public static class Hosting {

    public enum Mode {
      STANDALONE,
      EMBEDDED
    }

    private Mode mode = Mode.STANDALONE;

    public Mode getMode() {
      return mode;
    }

    public void setMode(Mode mode) {
      this.mode = mode;
    }
  }

  /** Provider-admin HTTP surface. */
  public static class Provider {

    private String basePath = "/api/1.0/opensharing/provider";

    public String getBasePath() {
      return basePath;
    }

    public void setBasePath(String basePath) {
      this.basePath = prefix(basePath);
    }
  }

  /** Issued recipient bearer tokens. */
  public static class RecipientTokens {

    private Duration defaultTtl = Duration.ofDays(90);
    private Duration rotationGrace = Duration.ZERO;

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

  /** Bounds on page sizes requested by protocol clients. */
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

  /** Which catalog implementation backs asset resolution and provider identity. */
  public static class Catalog {

    private String type;
    private final Local local = new Local();

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public Local getLocal() {
      return local;
    }

    /** YAML catalog used when {@code opensharing.catalog.type=local}. */
    public static class Local {

      private String file;

      public String getFile() {
        return file;
      }

      public void setFile(String file) {
        this.file = file;
      }
    }
  }
}
