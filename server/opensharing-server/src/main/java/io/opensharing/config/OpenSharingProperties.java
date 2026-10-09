package io.opensharing.config;

import io.opensharing.http.Pagination;
import io.opensharing.recipient.RecipientTokenSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenSharing server configuration. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  /** Path prefix the sharing protocol is served under; profile files point here. */
  private String protocolPrefix = "/api/1.0/opensharing";

  /** Path prefix recipient activation URLs are built under. */
  private String activationPrefix = "/api/1.0/opensharing/activations";
  private final Provider provider = new Provider();
  private final RecipientTokens recipientTokens = new RecipientTokens();
  private final PageSizes pagination = new PageSizes();
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

  public Provider getProvider() {
    return provider;
  }

  public RecipientTokens getRecipientTokens() {
    return recipientTokens;
  }

  public PageSizes getPagination() {
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

    /** Lifetime of a new token when the request does not set one. */
    private Duration defaultTtl = RecipientTokenSettings.DEFAULTS.defaultTtl();

    /** How long replaced tokens keep working after a rotation that does not set one. */
    private Duration rotationGrace = RecipientTokenSettings.DEFAULTS.rotationGrace();

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

  /** Page sizes of the list APIs. */
  public static class PageSizes {

    /** Page size when the request sets no maxResults. */
    private int defaultMaxResults = Pagination.DEFAULTS.defaultMaxResults();

    /** Largest page size a request may ask for; larger requests are capped to it. */
    private int maxMaxResults = Pagination.DEFAULTS.maxMaxResults();

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
