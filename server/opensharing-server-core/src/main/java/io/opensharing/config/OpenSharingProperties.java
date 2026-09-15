package io.opensharing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenSharing server configuration. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  /** URL prefix the recipient-facing protocol endpoints are mounted under. */
  private String protocolPrefix = "/api/1.0/opensharing";

  private final Hosting hosting = new Hosting();
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
