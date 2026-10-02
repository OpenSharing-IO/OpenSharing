package io.opensharing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenSharing server configuration. */
@ConfigurationProperties(prefix = "opensharing")
public class OpenSharingProperties {

  private final Hosting hosting = new Hosting();
  private final Catalog catalog = new Catalog();

  public Hosting getHosting() {
    return hosting;
  }

  public Catalog getCatalog() {
    return catalog;
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
