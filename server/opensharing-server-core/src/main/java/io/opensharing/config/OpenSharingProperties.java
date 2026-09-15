package io.opensharing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenSharing server configuration. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  private final Hosting hosting = new Hosting();
  private final Provider provider = new Provider();
  private final Catalog catalog = new Catalog();

  public Hosting getHosting() {
    return hosting;
  }

  public Provider getProvider() {
    return provider;
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
